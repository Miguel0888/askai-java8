package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.ResearchProjectRepository;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The productive file adapter behind the domain {@link ResearchProjectRepository} port — the SINGLE persistence
 * contract stays whole-aggregate, but the on-disk form is per entity (no monolith file), so a growing corpus is
 * not fully rewritten on every capture. Layout under {@code <projectDir>/knowledge/}:
 * <pre>
 *   project.properties                          schema version + project id
 *   captures/&lt;captureId&gt;.properties            immutable SourceCapture (+ its structural blocks)
 *   sentences/&lt;captureId&gt;.properties           all sentences of that capture
 *   passages/&lt;captureId&gt;-&lt;fingerprint&gt;.properties  the capture's passages for one embedding fingerprint
 * </pre>
 * {@link #save} writes only the entity files whose serialized content actually changed (idempotent, thanks to
 * the deterministic ids), each via a temp file + atomic move, so a partial write never leaves a half-valid
 * project. {@link #load} reconstructs the aggregate by replaying the existing domain record-operations from the
 * persisted entities (captures → sentences → passages); Lucene/vector indexes stay rebuildable projections and
 * are NOT stored here. This adapter currently persists the KNOWLEDGE slice (captures/sentences/passages) of the
 * aggregate — the methodology-workflow parts are added in their own later slice.
 */
public final class FileResearchProjectRepository implements ResearchProjectRepository {

    /** Bumped only on an incompatible on-disk format change; an unknown version is rejected, never guessed. */
    static final int SCHEMA_VERSION = 1;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File root;

    /** @param projectDirectory the project's directory; knowledge is stored under {@code knowledge/}. */
    public FileResearchProjectRepository(File projectDirectory) {
        this.root = new File(projectDirectory, "knowledge");
    }

    @Override
    public ResearchProject load(String projectId) {
        ResearchProject project = new ResearchProject(projectId, IdSequence.counting());
        File meta = new File(root, "project.properties");
        if (meta.isFile()) {
            Properties p = read(meta);
            int version = parseInt(p.getProperty("schemaVersion"), -1);
            if (version != SCHEMA_VERSION) {
                throw new IllegalStateException("incompatible research-knowledge schema version " + version
                        + " (expected " + SCHEMA_VERSION + ") at " + meta.getAbsolutePath());
            }
        }
        // Captures first (sentences/passages require a known capture), then sentences, then passages.
        for (File f : sortedPropertyFiles(new File(root, "captures"))) {
            project.recordSourceCapture(readCapture(read(f)));
        }
        for (File f : sortedPropertyFiles(new File(root, "sentences"))) {
            project.recordSentences(readSentences(read(f)));
        }
        for (File f : sortedPropertyFiles(new File(root, "passages"))) {
            project.recordPassages(readPassages(read(f)));
        }
        return project;
    }

    @Override
    public void save(ResearchProject project) {
        writeIfChanged(new File(root, "project.properties"),
                "schemaVersion=" + SCHEMA_VERSION + "\nprojectId=" + escape(project.getProjectId()) + "\n");

        for (SourceCapture capture : project.captures().values()) {
            writeIfChanged(new File(new File(root, "captures"), safe(capture.getCaptureId()) + ".properties"),
                    serializeCapture(capture));
        }
        for (Map.Entry<String, List<Sentence>> e : groupSentencesByCapture(project).entrySet()) {
            writeIfChanged(new File(new File(root, "sentences"), safe(e.getKey()) + ".properties"),
                    serializeSentences(e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, List<Passage>> e : groupPassagesByCaptureAndFingerprint(project).entrySet()) {
            writeIfChanged(new File(new File(root, "passages"), safe(e.getKey()) + ".properties"),
                    serializePassages(e.getValue()));
        }
    }

    // ------------------------------------------------------------------ serialization (write)

    private static String serializeCapture(SourceCapture c) {
        StringBuilder sb = new StringBuilder();
        line(sb, "captureId", c.getCaptureId());
        line(sb, "sourceId", c.getSourceId());
        line(sb, "canonicalUrl", c.getCanonicalUrl());
        line(sb, "capturedAtMillis", Long.toString(c.getCapturedAtMillis()));
        line(sb, "checksum", c.getChecksum());
        line(sb, "title", c.getTitle());
        line(sb, "author", c.getAuthor());
        line(sb, "blockCount", Integer.toString(c.getBlocks().size()));
        for (int i = 0; i < c.getBlocks().size(); i++) {
            SourceCapture.StructuralBlock b = c.getBlocks().get(i);
            line(sb, "block." + i + ".blockId", b.getBlockId());
            line(sb, "block." + i + ".kind", b.getKind().name());
            line(sb, "block." + i + ".headingPath", b.getHeadingPath());
            line(sb, "block." + i + ".text", b.getText());
        }
        return sb.toString();
    }

    private static String serializeSentences(String captureId, List<Sentence> sentences) {
        StringBuilder sb = new StringBuilder();
        line(sb, "captureId", captureId);
        line(sb, "sentenceCount", Integer.toString(sentences.size()));
        for (int i = 0; i < sentences.size(); i++) {
            Sentence s = sentences.get(i);
            line(sb, "s." + i + ".id", s.getSentenceId());
            line(sb, "s." + i + ".blockId", s.getBlockId());
            line(sb, "s." + i + ".ordinal", Integer.toString(s.getOrdinal()));
            line(sb, "s." + i + ".text", s.getText());
        }
        return sb.toString();
    }

    private static String serializePassages(List<Passage> passages) {
        StringBuilder sb = new StringBuilder();
        line(sb, "passageCount", Integer.toString(passages.size()));
        for (int i = 0; i < passages.size(); i++) {
            Passage p = passages.get(i);
            line(sb, "p." + i + ".id", p.getPassageId());
            line(sb, "p." + i + ".captureId", p.getCaptureId());
            line(sb, "p." + i + ".headingPath", p.getHeadingPath());
            line(sb, "p." + i + ".embeddingFingerprint", p.getEmbeddingFingerprint());
            line(sb, "p." + i + ".sentenceIds", join(p.getSentenceIds()));
            line(sb, "p." + i + ".text", p.getText());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ deserialization (read)

    private static SourceCapture readCapture(Properties p) {
        int blockCount = parseInt(p.getProperty("blockCount"), 0);
        List<SourceCapture.StructuralBlock> blocks = new ArrayList<SourceCapture.StructuralBlock>();
        for (int i = 0; i < blockCount; i++) {
            blocks.add(new SourceCapture.StructuralBlock(
                    p.getProperty("block." + i + ".blockId", ""),
                    parseKind(p.getProperty("block." + i + ".kind")),
                    p.getProperty("block." + i + ".headingPath", ""),
                    p.getProperty("block." + i + ".text", "")));
        }
        return new SourceCapture(p.getProperty("captureId", ""), p.getProperty("sourceId", ""),
                p.getProperty("canonicalUrl", ""), parseLong(p.getProperty("capturedAtMillis")),
                p.getProperty("checksum", ""), p.getProperty("title", ""), p.getProperty("author", ""),
                blocks);
    }

    private static List<Sentence> readSentences(Properties p) {
        String captureId = p.getProperty("captureId", "");
        int count = parseInt(p.getProperty("sentenceCount"), 0);
        List<Sentence> sentences = new ArrayList<Sentence>();
        for (int i = 0; i < count; i++) {
            sentences.add(new Sentence(p.getProperty("s." + i + ".id", ""), captureId,
                    p.getProperty("s." + i + ".blockId", ""),
                    parseInt(p.getProperty("s." + i + ".ordinal"), 0), p.getProperty("s." + i + ".text", "")));
        }
        return sentences;
    }

    private static List<Passage> readPassages(Properties p) {
        int count = parseInt(p.getProperty("passageCount"), 0);
        List<Passage> passages = new ArrayList<Passage>();
        for (int i = 0; i < count; i++) {
            passages.add(new Passage(p.getProperty("p." + i + ".id", ""),
                    p.getProperty("p." + i + ".captureId", ""), split(p.getProperty("p." + i + ".sentenceIds")),
                    p.getProperty("p." + i + ".headingPath", ""), p.getProperty("p." + i + ".text", ""),
                    p.getProperty("p." + i + ".embeddingFingerprint", "")));
        }
        return passages;
    }

    // ------------------------------------------------------------------ grouping

    private static Map<String, List<Sentence>> groupSentencesByCapture(ResearchProject project) {
        Map<String, List<Sentence>> byCapture = new TreeMap<String, List<Sentence>>();
        for (Sentence s : project.sentences().values()) {
            List<Sentence> list = byCapture.get(s.getCaptureId());
            if (list == null) {
                list = new ArrayList<Sentence>();
                byCapture.put(s.getCaptureId(), list);
            }
            list.add(s);
        }
        return byCapture;
    }

    /** key = {@code <captureId>-<embeddingFingerprint>} → the passages file name stem. */
    private static Map<String, List<Passage>> groupPassagesByCaptureAndFingerprint(ResearchProject project) {
        Map<String, List<Passage>> byKey = new TreeMap<String, List<Passage>>();
        for (Passage p : project.passages().values()) {
            String key = p.getCaptureId() + "-" + p.getEmbeddingFingerprint();
            List<Passage> list = byKey.get(key);
            if (list == null) {
                list = new ArrayList<Passage>();
                byKey.put(key, list);
            }
            list.add(p);
        }
        return byKey;
    }

    // ------------------------------------------------------------------ IO helpers

    /** Write only when the serialized content actually changed (avoids rewriting the whole corpus each save). */
    private static void writeIfChanged(File target, String content) {
        try {
            if (target.isFile() && content.equals(new String(Files.readAllBytes(target.toPath()), UTF8))) {
                return;
            }
        } catch (IOException ignored) {
            // fall through to a fresh write
        }
        atomicWrite(target, content);
    }

    private static void atomicWrite(File target, String content) {
        try {
            File parent = target.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            File tmp = new File(parent, target.getName() + ".tmp");
            Files.write(tmp.toPath(), content.getBytes(UTF8));
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot persist " + target.getName(), ex);
        }
    }

    private static Properties read(File f) {
        Properties p = new Properties();
        try {
            InputStream in = new FileInputStream(f);
            try {
                p.load(in);
            } finally {
                in.close();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + f.getAbsolutePath(), ex);
        }
        return p;
    }

    private static File[] sortedPropertyFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return new File[0];
        }
        List<File> kept = new ArrayList<File>();
        for (File f : files) {
            if (f.getName().endsWith(".properties")) {
                kept.add(f);
            }
        }
        File[] out = kept.toArray(new File[0]);
        Arrays.sort(out);
        return out;
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(escape(value)).append('\n');
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        for (String part : csv.split(",")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String safe(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static SourceCapture.BlockKind parseKind(String v) {
        try {
            return v == null ? SourceCapture.BlockKind.PARAGRAPH : SourceCapture.BlockKind.valueOf(v.trim());
        } catch (IllegalArgumentException ex) {
            return SourceCapture.BlockKind.PARAGRAPH;
        }
    }

    private static int parseInt(String v, int fallback) {
        try {
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0L : Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
