package com.aresstack.askai.java8.groupchat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Append-only file-backed {@link RoomHistoryLog}.
 *
 * <p>Each record is framed as a 4-byte big-endian length followed by
 * {@link GroupChatWire#encodeMessage} bytes. The framing allows a truncated or corrupt tail
 * (e.g. after a crash mid-write) to be detected and skipped: reading simply stops at the first
 * record that cannot be decoded. Appends are flushed immediately; duplicate message IDs are
 * ignored. All methods are thread-safe.</p>
 */
public final class FileRoomHistoryLog implements RoomHistoryLog {

    /** Sanity cap on record length so corrupt frames can't trigger huge allocations. */
    private static final int MAX_RECORD_BYTES = 32 * 1024 * 1024;

    private final File logFile;
    private final Set<String> messageIds = new LinkedHashSet<String>();
    private DataOutputStream out;
    private boolean closed;

    /**
     * Open (or create) the log for {@code roomId} inside {@code directory}.  The file name is the
     * sanitized room ID ({@code [^A-Za-z0-9._-]} replaced with {@code '_'}) plus {@code ".log"}.
     *
     * @throws UncheckedIOException when the directory cannot be created or the file cannot be opened
     */
    public FileRoomHistoryLog(File directory, String roomId) {
        if (roomId == null || roomId.trim().isEmpty()) {
            throw new IllegalArgumentException("roomId must not be blank");
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw new UncheckedIOException(new IOException("Cannot create directory: " + directory));
        }
        String fileName = roomId.replaceAll("[^A-Za-z0-9._-]", "_") + ".log";
        this.logFile = new File(directory, fileName);
        truncateCorruptTail();
        for (GroupChatMessage message : readRecords()) {
            messageIds.add(message.getMessageId());
        }
        try {
            this.out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(logFile, true)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open history log: " + logFile, e);
        }
    }

    @Override
    public synchronized void append(GroupChatMessage message) {
        if (message == null || closed || messageIds.contains(message.getMessageId())) {
            return;
        }
        byte[] record = GroupChatWire.encodeMessage(message);
        try {
            out.writeInt(record.length);
            out.write(record);
            out.flush();
            messageIds.add(message.getMessageId());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append to history log: " + logFile, e);
        }
    }

    @Override
    public synchronized List<GroupChatMessage> readAll() {
        return HistoryMerger.sort(readRecords());
    }

    @Override
    public synchronized List<GroupChatMessage> readSince(long sinceMillis) {
        List<GroupChatMessage> since = new ArrayList<GroupChatMessage>();
        for (GroupChatMessage message : readRecords()) {
            if (message.getCreatedAt() >= sinceMillis) {
                since.add(message);
            }
        }
        return HistoryMerger.sort(since);
    }

    @Override
    public synchronized boolean contains(String messageId) {
        return messageId != null && messageIds.contains(messageId);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.close();
        } catch (IOException e) {
            // Best effort: the log was flushed after every append.
        }
    }

    /**
     * Cut off a truncated or corrupt tail (e.g. after a crash mid-write) so subsequent appends
     * land on a valid record boundary and stay readable.
     */
    private void truncateCorruptTail() {
        if (!logFile.exists()) {
            return;
        }
        long validLength = 0;
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(logFile)));
            try {
                while (true) {
                    int length;
                    try {
                        length = in.readInt();
                    } catch (EOFException endOfLog) {
                        break;
                    }
                    if (length < 0 || length > MAX_RECORD_BYTES) {
                        break;
                    }
                    byte[] record = new byte[length];
                    try {
                        in.readFully(record);
                        GroupChatWire.decodeMessage(record);
                    } catch (IOException corruptOrTruncated) {
                        break;
                    }
                    validLength += 4L + length;
                }
            } finally {
                in.close();
            }
            if (validLength < logFile.length()) {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "rw");
                try {
                    raf.setLength(validLength);
                } finally {
                    raf.close();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot repair history log: " + logFile, e);
        }
    }

    /** Read all decodable records; stops gracefully at a truncated or corrupt tail. */
    private List<GroupChatMessage> readRecords() {
        List<GroupChatMessage> messages = new ArrayList<GroupChatMessage>();
        if (!logFile.exists()) {
            return messages;
        }
        Set<String> seen = new LinkedHashSet<String>();
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(logFile)));
            try {
                while (true) {
                    int length;
                    try {
                        length = in.readInt();
                    } catch (EOFException endOfLog) {
                        break;
                    }
                    if (length < 0 || length > MAX_RECORD_BYTES) {
                        break; // corrupt length prefix — ignore the tail
                    }
                    byte[] record = new byte[length];
                    try {
                        in.readFully(record);
                    } catch (EOFException truncatedTail) {
                        break; // truncated tail record — ignore
                    }
                    GroupChatMessage message;
                    try {
                        message = GroupChatWire.decodeMessage(record);
                    } catch (IOException corruptRecord) {
                        break; // corrupt tail record — ignore
                    }
                    if (seen.add(message.getMessageId())) {
                        messages.add(message);
                    }
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read history log: " + logFile, e);
        }
        return messages;
    }
}
