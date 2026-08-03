package com.aresstack.askai.research.knowledge.lucene;

import com.aresstack.askai.research.knowledge.processing.index.PassageIndexDocument;
import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Lucene text/metadata half of the semantic index — one Lucene index per semantic namespace
 * ({@code embeddingFingerprint}) under {@code <project>/indexes/knowledge/text/}, kept strictly separate from the
 * canonical {@code knowledge/} store. It is a fully REBUILDABLE projection; Lucene types never leave this module
 * (only neutral {@link PassageSearchHit}/{@link PassageIndexDocument} cross the boundary). No Lucene KNN/vector
 * search and no heavy analyzer chain — plain {@link StandardAnalyzer} keyword matching (vector search is the
 * separate brute-force cosine index).
 */
public final class LuceneTextPassageIndex {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String F_PASSAGE_ID = "passageId";
    private static final String F_CAPTURE_ID = "captureId";
    private static final String F_SOURCE_ID = "sourceId";
    private static final String F_HEADING = "headingPath";
    private static final String F_TEXT = "text";

    private final File textRoot;

    public LuceneTextPassageIndex(File projectDirectory) {
        this.textRoot = new File(new File(new File(projectDirectory, "indexes"), "knowledge"), "text");
    }

    /** Upsert documents into their own namespaces (idempotent per passage id). */
    public void upsert(Collection<PassageIndexDocument> documents) {
        for (Map.Entry<String, List<PassageIndexDocument>> ns : byNamespace(documents).entrySet()) {
            writeNamespace(ns.getKey(), ns.getValue(), null);
        }
    }

    /** Replace ALL of a capture's passages in one namespace with {@code documents} (supersession). */
    public void replaceForCapture(String embeddingFingerprint, String captureId,
                                  Collection<PassageIndexDocument> documents) {
        List<PassageIndexDocument> docs = new ArrayList<PassageIndexDocument>();
        if (documents != null) {
            docs.addAll(documents);
        }
        writeNamespace(embeddingFingerprint, docs, captureId);
    }

    /** Keyword search within one namespace; empty when the namespace has no index yet. */
    public List<PassageSearchHit> search(String embeddingFingerprint, String queryText, int limit) {
        File dir = namespaceDir(embeddingFingerprint);
        List<PassageSearchHit> hits = new ArrayList<PassageSearchHit>();
        Analyzer analyzer = new StandardAnalyzer();
        try {
            Directory directory = FSDirectory.open(dir.toPath());
            try {
                if (!DirectoryReader.indexExists(directory)) {
                    return hits;
                }
                Query query = toQuery(analyzer, queryText);
                if (query == null) {
                    return hits; // no searchable terms
                }
                DirectoryReader reader = DirectoryReader.open(directory);
                try {
                    IndexSearcher searcher = new IndexSearcher(reader);
                    TopDocs top = searcher.search(query, Math.max(1, limit));
                    for (ScoreDoc sd : top.scoreDocs) {
                        Document d = searcher.doc(sd.doc);
                        hits.add(new PassageSearchHit(d.get(F_PASSAGE_ID), d.get(F_CAPTURE_ID),
                                d.get(F_SOURCE_ID), d.get(F_TEXT), d.get(F_HEADING), sd.score));
                    }
                } finally {
                    reader.close();
                }
            } finally {
                directory.close();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("keyword search failed in " + dir, ex);
        } finally {
            analyzer.close();
        }
        return hits;
    }

    /** Rebuild the whole text index from scratch for the given documents. */
    public void rebuild(Collection<PassageIndexDocument> documents) {
        removeAll();
        upsert(documents);
    }

    /** Remove the entire text index of this project (all namespaces). */
    public void removeAll() {
        deleteRecursively(textRoot);
    }

    // ------------------------------------------------------------------ write

    private void writeNamespace(String fingerprint, List<PassageIndexDocument> documents,
                                String deleteCaptureIdFirst) {
        Analyzer analyzer = new StandardAnalyzer();
        try {
            File dir = namespaceDir(fingerprint);
            Files.createDirectories(dir.toPath());
            Directory directory = FSDirectory.open(dir.toPath());
            try {
                IndexWriterConfig config = new IndexWriterConfig(analyzer);
                config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
                IndexWriter writer = new IndexWriter(directory, config);
                try {
                    if (deleteCaptureIdFirst != null) {
                        writer.deleteDocuments(new Term(F_CAPTURE_ID, deleteCaptureIdFirst));
                    }
                    for (PassageIndexDocument d : documents) {
                        if (!fingerprint.equals(d.getEmbeddingFingerprint())) {
                            throw new IllegalArgumentException("document fingerprint "
                                    + d.getEmbeddingFingerprint() + " does not match namespace " + fingerprint);
                        }
                        writer.updateDocument(new Term(F_PASSAGE_ID, d.getPassageId()), toDocument(d));
                    }
                    writer.commit();
                } finally {
                    writer.close();
                }
            } finally {
                directory.close();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("cannot write text index for namespace " + fingerprint, ex);
        } finally {
            analyzer.close();
        }
    }

    private static Document toDocument(PassageIndexDocument d) {
        Document doc = new Document();
        doc.add(new StringField(F_PASSAGE_ID, d.getPassageId(), Field.Store.YES));
        doc.add(new StringField(F_CAPTURE_ID, d.getCaptureId(), Field.Store.YES));
        doc.add(new StoredField(F_SOURCE_ID, d.getSourceId()));
        doc.add(new StoredField(F_HEADING, d.getHeadingPath()));
        // Analyzed + stored: searchable text AND returned verbatim in a hit.
        doc.add(new TextField(F_TEXT, d.getText(), Field.Store.YES));
        return doc;
    }

    private static Query toQuery(Analyzer analyzer, String queryText) {
        List<String> terms = new ArrayList<String>();
        try {
            TokenStream ts = analyzer.tokenStream(F_TEXT, queryText == null ? "" : queryText);
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                terms.add(attr.toString());
            }
            ts.end();
            ts.close();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot analyze query text", ex);
        }
        if (terms.isEmpty()) {
            return null;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String term : terms) {
            builder.add(new TermQuery(new Term(F_TEXT, term)), BooleanClause.Occur.SHOULD);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ paths / util

    private File namespaceDir(String fingerprint) {
        return new File(textRoot, sha256Hex(fingerprint));
    }

    private static Map<String, List<PassageIndexDocument>> byNamespace(
            Collection<PassageIndexDocument> documents) {
        Map<String, List<PassageIndexDocument>> byFp = new LinkedHashMap<String, List<PassageIndexDocument>>();
        if (documents != null) {
            for (PassageIndexDocument d : documents) {
                List<PassageIndexDocument> list = byFp.get(d.getEmbeddingFingerprint());
                if (list == null) {
                    list = new ArrayList<PassageIndexDocument>();
                    byFp.put(d.getEmbeddingFingerprint(), list);
                }
                list.add(d);
            }
        }
        return byFp;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        file.delete();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value)
                    .getBytes(UTF8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** For diagnostics/tests: a stable comparator on hit score desc then passage id. */
    static Comparator<PassageSearchHit> byScoreDesc() {
        return new Comparator<PassageSearchHit>() {
            public int compare(PassageSearchHit a, PassageSearchHit b) {
                int byScore = Double.compare(b.getScore(), a.getScore());
                return byScore != 0 ? byScore : a.getPassageId().compareTo(b.getPassageId());
            }
        };
    }
}
