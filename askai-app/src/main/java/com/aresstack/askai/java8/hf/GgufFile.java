package com.aresstack.askai.java8.hf;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal GGUF header validator. It reads only the header (magic, metadata and tensor table) — never
 * the multi-gigabyte tensor data — and checks that every tensor's data range fits inside the file.
 *
 * <p>This lets AskAI reject a truncated or corrupt {@code .gguf} <em>before</em> handing it to Ollama,
 * instead of surfacing an opaque HTTP 500 like
 * {@code tensor "token_embd.weight" offset+size (…) exceeds file size (…)} from the server. It is the
 * same bound Ollama checks, done locally.</p>
 */
public final class GgufFile {

    // Sanity caps so a corrupt header cannot make us loop or allocate unboundedly.
    private static final long MAX_COUNT = 100_000_000L;
    private static final long MAX_DIMENSIONS = 8L;

    private GgufFile() {
    }

    /**
     * @throws IOException when the file is not a valid, complete GGUF model (bad magic, truncated
     *         header, or a tensor whose data extends beyond the end of the file).
     */
    public static void validate(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("GGUF file does not exist.");
        }
        long length = file.length();
        if (length < 8L) {
            throw new IOException("File is too small to be a GGUF model (" + length + " bytes). "
                    + "The download is incomplete or not a GGUF file.");
        }

        CountingInputStream in = new CountingInputStream(
                new BufferedInputStream(new FileInputStream(file), 1 << 16));
        try {
            byte[] magic = new byte[4];
            readFully(in, magic);
            if (!(magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F')) {
                throw new IOException("Not a GGUF file (bad magic bytes). "
                        + "The download is corrupt or not a GGUF model.");
            }

            long version = readU32(in);
            long tensorCount;
            long kvCount;
            if (version == 1L) {
                tensorCount = readU32(in);
                kvCount = readU32(in);
            } else {
                tensorCount = readU64(in);
                kvCount = readU64(in);
            }
            checkCount(tensorCount, "tensor");
            checkCount(kvCount, "metadata");

            long alignment = 32L;
            for (long i = 0; i < kvCount; i++) {
                String key = readString(in);
                long valueType = readU32(in);
                Long scalar = readMetadataValue(in, valueType);
                if ("general.alignment".equals(key) && scalar != null && scalar.longValue() > 0) {
                    alignment = scalar.longValue();
                }
            }

            long maxTensorEnd = 0L;
            boolean unknownType = false;
            for (long i = 0; i < tensorCount; i++) {
                readString(in); // tensor name (unused)
                long nDims = readU32(in);
                if (nDims > MAX_DIMENSIONS) {
                    throw new IOException("Corrupt GGUF header: tensor has " + nDims + " dimensions.");
                }
                long elements = 1L;
                for (long d = 0; d < nDims; d++) {
                    elements *= readU64(in);
                }
                long type = readU32(in);
                long offset = readU64(in);
                long nbytes = tensorByteSize(type, elements);
                long end;
                if (nbytes < 0L) {
                    unknownType = true;
                    end = offset; // lower bound: at least the offset must be inside the data section
                } else {
                    end = offset + nbytes;
                }
                if (end > maxTensorEnd) {
                    maxTensorEnd = end;
                }
            }

            long dataStart = alignUp(in.count(), alignment);
            long required = dataStart + maxTensorEnd;
            if (required > length) {
                throw new IOException("GGUF file is incomplete or corrupt: its tensor data needs "
                        + required + " bytes but the file is only " + length + " bytes"
                        + (unknownType ? " (contains tensor types unknown to this check, so the real requirement "
                        + "may be larger)" : "") + ". Re-download the model.");
            }
        } catch (EOFException ex) {
            throw new IOException("GGUF header is truncated (unexpected end of file). "
                    + "The download is incomplete. Re-download the model.", ex);
        } finally {
            closeQuietly(in);
        }
    }

    private static void checkCount(long count, String what) throws IOException {
        if (count < 0L || count > MAX_COUNT) {
            throw new IOException("Corrupt GGUF header: implausible " + what + " count (" + count + ").");
        }
    }

    /**
     * Reads (and mostly skips) a metadata value, returning it as a {@link Long} for the integer scalar
     * types (so {@code general.alignment} can be read) and {@code null} for everything else.
     */
    private static Long readMetadataValue(CountingInputStream in, long valueType) throws IOException {
        switch ((int) valueType) {
            case 0:  // UINT8
            case 1:  // INT8
            case 7:  // BOOL
                return Long.valueOf(readU8(in));
            case 2:  // UINT16
            case 3:  // INT16
                return Long.valueOf(readLe(in, 2));
            case 4:  // UINT32
            case 5:  // INT32
                return Long.valueOf(readLe(in, 4));
            case 6:  // FLOAT32
                skipFully(in, 4);
                return null;
            case 8:  // STRING
                skipFully(in, readU64(in));
                return null;
            case 9:  // ARRAY
                readArray(in);
                return null;
            case 10: // UINT64
            case 11: // INT64
                return Long.valueOf(readU64(in));
            case 12: // FLOAT64
                skipFully(in, 8);
                return null;
            default:
                throw new IOException("Corrupt GGUF header: unknown metadata value type " + valueType + ".");
        }
    }

    private static void readArray(CountingInputStream in) throws IOException {
        long elementType = readU32(in);
        long count = readU64(in);
        checkCount(count, "array");
        for (long i = 0; i < count; i++) {
            // Arrays never nest arrays in practice, but recurse anyway for correctness.
            readMetadataValue(in, elementType);
        }
    }

    /**
     * Returns the byte size of a tensor of {@code elements} elements for the given ggml type, or -1 when
     * the type id is unknown to this validator.
     */
    private static long tensorByteSize(long type, long elements) {
        long[] spec = GGML_TYPE_SIZES.get(Long.valueOf(type));
        if (spec == null) {
            return -1L;
        }
        long blockSize = spec[0];
        long typeSize = spec[1];
        if (blockSize <= 0L) {
            return -1L;
        }
        return (elements / blockSize) * typeSize;
    }

    /** ggml type id -> {block size in elements, bytes per block}. */
    private static final Map<Long, long[]> GGML_TYPE_SIZES = new HashMap<Long, long[]>();

    static {
        put(0, 1, 4);      // F32
        put(1, 1, 2);      // F16
        put(2, 32, 18);    // Q4_0
        put(3, 32, 20);    // Q4_1
        put(6, 32, 22);    // Q5_0
        put(7, 32, 24);    // Q5_1
        put(8, 32, 34);    // Q8_0
        put(9, 32, 40);    // Q8_1
        put(10, 256, 84);  // Q2_K
        put(11, 256, 110); // Q3_K
        put(12, 256, 144); // Q4_K
        put(13, 256, 176); // Q5_K
        put(14, 256, 210); // Q6_K
        put(15, 256, 292); // Q8_K
        put(16, 256, 66);  // IQ2_XXS
        put(17, 256, 74);  // IQ2_XS
        put(18, 256, 98);  // IQ3_XXS
        put(19, 256, 50);  // IQ1_S
        put(20, 32, 18);   // IQ4_NL
        put(21, 256, 110); // IQ3_S
        put(22, 256, 82);  // IQ2_S
        put(23, 256, 136); // IQ4_XS
        put(24, 1, 1);     // I8
        put(25, 1, 2);     // I16
        put(26, 1, 4);     // I32
        put(27, 1, 8);     // I64
        put(28, 1, 8);     // F64
        put(29, 256, 56);  // IQ1_M
        put(30, 1, 2);     // BF16
    }

    private static void put(int type, int blockSize, int typeSize) {
        GGML_TYPE_SIZES.put(Long.valueOf(type), new long[]{blockSize, typeSize});
    }

    private static long alignUp(long value, long alignment) {
        if (alignment <= 0L) {
            return value;
        }
        long remainder = value % alignment;
        return remainder == 0L ? value : value + (alignment - remainder);
    }

    private static int readU8(CountingInputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private static long readLe(CountingInputStream in, int bytes) throws IOException {
        long value = 0L;
        for (int i = 0; i < bytes; i++) {
            value |= ((long) readU8(in)) << (8 * i);
        }
        return value;
    }

    private static long readU32(CountingInputStream in) throws IOException {
        return readLe(in, 4);
    }

    private static long readU64(CountingInputStream in) throws IOException {
        return readLe(in, 8);
    }

    private static String readString(CountingInputStream in) throws IOException {
        long length = readU64(in);
        if (length < 0L || length > MAX_COUNT) {
            throw new IOException("Corrupt GGUF header: implausible string length (" + length + ").");
        }
        byte[] bytes = new byte[(int) length];
        readFully(in, bytes);
        return new String(bytes, "UTF-8");
    }

    private static void readFully(CountingInputStream in, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = in.read(target, offset, target.length - offset);
            if (read < 0) {
                throw new EOFException();
            }
            offset += read;
        }
    }

    private static void skipFully(CountingInputStream in, long count) throws IOException {
        long remaining = count;
        byte[] scratch = new byte[8192];
        while (remaining > 0) {
            int chunk = (int) Math.min(scratch.length, remaining);
            int read = in.read(scratch, 0, chunk);
            if (read < 0) {
                throw new EOFException();
            }
            remaining -= read;
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Tracks how many bytes have been consumed, so the tensor-data offset (post-alignment) is known. */
    private static final class CountingInputStream extends FilterInputStream {

        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        long count() {
            return count;
        }

        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }
    }
}
