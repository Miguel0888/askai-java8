package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.jsontree.JsonTreeNode;
import com.aresstack.askai.research.jsontree.JsonTreeParseResult;
import com.aresstack.askai.research.jsontree.JsonTreeParser;
import com.aresstack.askai.research.jsontree.MermaidMindmapRenderer;
import com.aresstack.askai.research.jsontree.StructuralForest;
import com.aresstack.askai.research.jsontree.StructuralTreeExtractor;
import com.aresstack.askai.research.jsontree.ValueLeaf;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

/**
 * The render-neutral projection of ONE concept snapshot for the UI: the SAME pipeline the agent
 * tools stand on (strict parse → typed tree → structural forest → Mermaid), computed once from
 * one atomic {@link ConceptBranchService.DocumentSnapshot} so mindmap, JSON view and revision
 * label can never show different states. No Swing in here. An unreadable document (disk/IO
 * damage — K1/K2 never commit one) yields the DIAGNOSTIC, never a creatively repaired view.
 */
public final class ConceptProjection {

    private final long workingRevision;
    private final String title;
    private final String mermaid;
    private final String prettyJson;
    private final boolean emptyConcept;
    private final String diagnosticText;

    private ConceptProjection(long workingRevision, String title, String mermaid,
                              String prettyJson, boolean emptyConcept, String diagnosticText) {
        this.workingRevision = workingRevision;
        this.title = title;
        this.mermaid = mermaid;
        this.prettyJson = prettyJson;
        this.emptyConcept = emptyConcept;
        this.diagnosticText = diagnosticText;
    }

    public static ConceptProjection of(ConceptBranchService.DocumentSnapshot snapshot) {
        String document = snapshot.getDocumentJson();
        long revision = snapshot.getWorkingRevision();
        JsonTreeParseResult parsed = JsonTreeParser.parse(document);
        if (!parsed.isOk()) {
            // Never a broken mindmap: the raw text stays visible in the JSON view, the mindmap
            // side shows the honest diagnosis.
            return new ConceptProjection(revision, "", null, document, false,
                    parsed.getDiagnostic().describeForModel());
        }
        String title = titleOf(parsed);
        // Unwrap the working surface: the mindmap shows the concept's CARDS, never a "concept"
        // wrapper node (the envelope's other sections are leaves and drop out on their own).
        StructuralForest forest = StructuralTreeExtractor.extract(parsed.getTree());
        for (com.aresstack.askai.research.jsontree.StructuralNode root : forest.getRoots()) {
            if (ConceptBranchService.CONCEPT_PROPERTY.equals(root.getName())) {
                forest = StructuralForest.of(root.getChildren());
                break;
            }
        }
        String mermaid = forest.isEmpty() ? null
                : MermaidMindmapRenderer.render(forest,
                        title.isEmpty() ? "Konzept" : title);
        String pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(JsonParser.parseString(document));
        return new ConceptProjection(revision, title, mermaid, pretty, forest.isEmpty(), null);
    }

    /** The envelope's {@code title} ValueLeaf, or empty. */
    private static String titleOf(JsonTreeParseResult parsed) {
        for (JsonTreeNode node : parsed.getTree().getRoots()) {
            if (node instanceof ValueLeaf && "title".equals(node.getName())) {
                String value = ((ValueLeaf) node).asText().trim();
                return "null".equals(value) ? "" : value;
            }
        }
        return "";
    }

    public boolean isReadable() {
        return diagnosticText == null;
    }

    /** The diagnosis of an unreadable document (never shown as a diagram), or {@code null}. */
    public String getDiagnosticText() {
        return diagnosticText;
    }

    public long getWorkingRevision() {
        return workingRevision;
    }

    public String getTitle() {
        return title;
    }

    /** The Mermaid mindmap source, or {@code null} while the concept has no structure yet. */
    public String getMermaid() {
        return mermaid;
    }

    /** Whether the working surface holds no structural node yet (placeholder instead of a map). */
    public boolean isEmptyConcept() {
        return emptyConcept;
    }

    /** The document pretty-printed for the read-only JSON view (raw text when unreadable). */
    public String getPrettyJson() {
        return prettyJson;
    }
}
