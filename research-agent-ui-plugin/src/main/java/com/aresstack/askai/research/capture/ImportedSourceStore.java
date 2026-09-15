package com.aresstack.askai.research.capture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * #39 correction — the DURABLE truth for user imports: the RAW snapshot exactly as the user
 * delivered it, plus the structured extraction record. Both persist BEFORE the acceptance
 * commit; the extracted text in the source record is DERIVED and the source's comment is at
 * most a display convenience — provenance never depends on it. Losing this store's write is
 * an import FAILURE, never a silent success (provenance is part of the evidence chain).
 *
 * <p>Layout under the store directory, keyed by a deterministic content id
 * ({@code imp-<rawSha256 prefix>}): {@code <id>.raw} is the untouched raw content,
 * {@code <id>.properties} the metadata (kind, hashes, origin, extractor, warnings, and the
 * source id once the acceptance bound one). Re-importing identical raw content rewrites the
 * same snapshot idempotently.</p>
 */
public final class ImportedSourceStore {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** The raw user delivery: content untouched, hashed, stamped. */
    public static final class ImportedSourceSnapshot {
        public final String snapshotId;
        public final String kind;
        public final String rawContent;
        public final String rawSha256;
        public final String originUri;
        public final long importedAtMillis;

        public ImportedSourceSnapshot(String snapshotId, String kind, String rawContent,
                                      String rawSha256, String originUri, long importedAtMillis) {
            this.snapshotId = snapshotId;
            this.kind = kind;
            this.rawContent = rawContent;
            this.rawSha256 = rawSha256;
            this.originUri = originUri == null ? "" : originUri;
            this.importedAtMillis = importedAtMillis;
        }
    }

    /** What the mechanical extractor derived from the snapshot (text lives in the record). */
    public static final class ExtractionRecord {
        public final String snapshotId;
        public final String extractorId;
        public final String normalizedSha256;
        public final List<String> warnings;

        public ExtractionRecord(String snapshotId, String extractorId, String normalizedSha256,
                                List<String> warnings) {
            this.snapshotId = snapshotId;
            this.extractorId = extractorId;
            this.normalizedSha256 = normalizedSha256;
            this.warnings = Collections.unmodifiableList(
                    new ArrayList<String>(warnings == null
                            ? Collections.<String>emptyList() : warnings));
        }
    }

    /** One loaded snapshot + extraction + the bound source id (empty until bound). */
    public static final class Loaded {
        public final ImportedSourceSnapshot snapshot;
        public final ExtractionRecord extraction;
        public final String sourceId;

        Loaded(ImportedSourceSnapshot snapshot, ExtractionRecord extraction, String sourceId) {
            this.snapshot = snapshot;
            this.extraction = extraction;
            this.sourceId = sourceId;
        }
    }

    private final File dir;

    public ImportedSourceStore(File dir) {
        this.dir = dir;
    }

    /** Deterministic content id — identical raw content maps to the same snapshot. */
    public static String snapshotIdFor(String rawSha256) {
        return "imp-" + rawSha256.substring(0, Math.min(16, rawSha256.length()));
    }

    /**
     * Persist raw snapshot + extraction record together, atomically per file. This runs
     * BEFORE the acceptance commit; an {@link IOException} here must fail the import.
     */
    public void save(ImportedSourceSnapshot snapshot, ExtractionRecord extraction)
            throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create import store directory " + dir);
        }
        atomicWrite(rawFile(snapshot.snapshotId), snapshot.rawContent);
        // Idempotent rewrite of the same content id must never wipe an existing binding.
        Properties existing = readProps(snapshot.snapshotId);
        String boundSourceId = existing == null ? null : existing.getProperty("sourceId");
        Properties props = new Properties();
        if (boundSourceId != null && !boundSourceId.isEmpty()) {
            props.setProperty("sourceId", boundSourceId);
        }
        props.setProperty("snapshotId", snapshot.snapshotId);
        props.setProperty("kind", snapshot.kind);
        props.setProperty("rawSha256", snapshot.rawSha256);
        props.setProperty("originUri", snapshot.originUri);
        props.setProperty("importedAtMillis", Long.toString(snapshot.importedAtMillis));
        props.setProperty("extractorId", extraction.extractorId);
        props.setProperty("normalizedSha256", extraction.normalizedSha256);
        props.setProperty("warningCount", Integer.toString(extraction.warnings.size()));
        for (int index = 0; index < extraction.warnings.size(); index++) {
            props.setProperty("warning." + index, extraction.warnings.get(index));
        }
        writeProps(snapshot.snapshotId, props);
    }

    /** Bind the accepted source id to its snapshot (after the acceptance commit). */
    public void bindSource(String snapshotId, String sourceId) throws IOException {
        Properties props = readProps(snapshotId);
        if (props == null) {
            throw new IOException("no snapshot " + snapshotId + " to bind source " + sourceId);
        }
        props.setProperty("sourceId", sourceId);
        writeProps(snapshotId, props);
    }

    /** @return the full provenance for a snapshot id, or {@code null} when unknown. */
    public Loaded load(String snapshotId) throws IOException {
        Properties props = readProps(snapshotId);
        File raw = rawFile(snapshotId);
        if (props == null || !raw.isFile()) {
            return null;
        }
        byte[] bytes = java.nio.file.Files.readAllBytes(raw.toPath());
        List<String> warnings = new ArrayList<String>();
        int count = Integer.parseInt(props.getProperty("warningCount", "0"));
        for (int index = 0; index < count; index++) {
            String warning = props.getProperty("warning." + index);
            if (warning != null) {
                warnings.add(warning);
            }
        }
        return new Loaded(
                new ImportedSourceSnapshot(snapshotId,
                        props.getProperty("kind", ""),
                        new String(bytes, UTF8),
                        props.getProperty("rawSha256", ""),
                        props.getProperty("originUri", ""),
                        Long.parseLong(props.getProperty("importedAtMillis", "0"))),
                new ExtractionRecord(snapshotId,
                        props.getProperty("extractorId", ""),
                        props.getProperty("normalizedSha256", ""),
                        warnings),
                props.getProperty("sourceId", ""));
    }

    /** @return the snapshot id bound to this source, or {@code null} when none is. */
    public String snapshotIdForSource(String sourceId) {
        File[] files = dir.listFiles();
        if (files == null || sourceId == null || sourceId.isEmpty()) {
            return null;
        }
        for (File file : files) {
            if (!file.getName().endsWith(".properties")) {
                continue;
            }
            try {
                Properties props = readPropsFile(file);
                if (sourceId.equals(props.getProperty("sourceId"))) {
                    return props.getProperty("snapshotId");
                }
            } catch (IOException unreadable) {
                // an unreadable sibling never hides the others
            }
        }
        return null;
    }

    // ---- files ----

    private File rawFile(String snapshotId) {
        return new File(dir, snapshotId + ".raw");
    }

    private File propsFile(String snapshotId) {
        return new File(dir, snapshotId + ".properties");
    }

    private Properties readProps(String snapshotId) throws IOException {
        File file = propsFile(snapshotId);
        return file.isFile() ? readPropsFile(file) : null;
    }

    private static Properties readPropsFile(File file) throws IOException {
        Properties props = new Properties();
        java.io.InputStream in = new java.io.FileInputStream(file);
        try {
            props.load(in);
        } finally {
            in.close();
        }
        return props;
    }

    private void writeProps(String snapshotId, Properties props) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        props.store(buffer, "askai imported source snapshot");
        atomicWrite(propsFile(snapshotId), new String(buffer.toByteArray(), "ISO-8859-1"));
    }

    private static void atomicWrite(File target, String content) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
        if (target.isFile() && !target.delete()) {
            throw new IOException("cannot replace " + target);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("cannot move " + tmp + " to " + target);
        }
    }
}
