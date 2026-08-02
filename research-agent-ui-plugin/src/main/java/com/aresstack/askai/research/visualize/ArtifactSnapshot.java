package com.aresstack.askai.research.visualize;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * An immutable snapshot of ONE artifact handed to the visualizer — the ONLY input it gets. It carries the
 * artifact id, its current content, a content hash (the stale-result marker) and the phase. No chat history
 * and no hidden agent knowledge: the visualizer may depict only what is in the artifact. Generic on purpose —
 * any artifact (not just the research brief) can be snapshotted for visualization.
 */
public final class ArtifactSnapshot {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String artifactId;
    private final String content;
    private final String contentHash;
    private final String phaseId;

    public ArtifactSnapshot(String artifactId, String content, String phaseId) {
        this.artifactId = artifactId == null ? "" : artifactId;
        this.content = content == null ? "" : content;
        this.phaseId = phaseId == null ? "" : phaseId;
        this.contentHash = sha256(this.content);
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getContent() {
        return content;
    }

    /** A stable hash of the content — the source-of-truth marker a stale visualization is checked against. */
    public String getContentHash() {
        return contentHash;
    }

    public String getPhaseId() {
        return phaseId;
    }

    private static String sha256(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(content.getBytes(UTF8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception hashingUnavailable) {
            return Integer.toHexString(content.hashCode());
        }
    }
}
