package com.aresstack.askai.research.jsontree;

/**
 * Machine-readable codes for every failure this layer can report. They are part of the contract
 * with the (later) model-facing tools: a rejected proposal is echoed back to the model with the
 * code, the position and a repair hint, so the MODEL repairs — this layer never auto-repairs.
 */
public enum JsonTreeErrorCode {

    /** The text is not strict RFC-8259 JSON (missing comma/bracket, unquoted names, garbage, …). */
    JSON_SYNTAX_ERROR,

    /** A branch must be ONE object with EXACTLY ONE array-valued property (one root ArrayNode). */
    INVALID_BRANCH_ROOT,

    /** The host-side target path does not resolve to an array-valued property in the document. */
    TARGET_NODE_NOT_FOUND,

    /** The document changed between branch export and branch return; the edit was not applied. */
    STALE_DOCUMENT_REVISION,

    /** The graft step itself failed (e.g. a renamed branch root collides with a sibling). */
    BRANCH_GRAFT_FAILED,

    /** The fully grafted candidate document failed re-validation; the candidate was discarded. */
    CANDIDATE_DOCUMENT_INVALID,

    /**
     * Raised by POLICY layers on top of the neutral replacer (not by the replacer itself): a
     * syntactically perfect refinement silently dropped existing structural nodes. Removal must
     * be an explicit operation, never a side effect of a refinement.
     */
    STRUCTURE_LOSS_DETECTED
}
