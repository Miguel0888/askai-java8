package com.aresstack.askai.research.jsontree;

/**
 * Outcome of a branch edit. On success the COMMITTED candidate document and its new revision are
 * returned; on any failure only the diagnostic — the previously valid document is guaranteed
 * untouched (the replacer works exclusively on copies until the final commit).
 */
public final class BranchEditResult {

    private final String documentJson;
    private final long newRevision;
    private final JsonTreeDiagnostic diagnostic;

    private BranchEditResult(String documentJson, long newRevision,
            JsonTreeDiagnostic diagnostic) {
        this.documentJson = documentJson;
        this.newRevision = newRevision;
        this.diagnostic = diagnostic;
    }

    static BranchEditResult committed(String documentJson, long newRevision) {
        return new BranchEditResult(documentJson, newRevision, null);
    }

    static BranchEditResult rejected(JsonTreeDiagnostic diagnostic) {
        return new BranchEditResult(null, -1L, diagnostic);
    }

    public boolean isCommitted() {
        return diagnostic == null;
    }

    /** The full candidate document that became the new valid state ({@code null} if rejected). */
    public String getDocumentJson() {
        return documentJson;
    }

    /** The new revision number ({@code -1} if rejected). */
    public long getNewRevision() {
        return newRevision;
    }

    public JsonTreeDiagnostic getDiagnostic() {
        return diagnostic;
    }
}
