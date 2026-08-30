package com.aresstack.askai.research.jsontree;

/**
 * One returned branch edit: WHICH revision it was based on (stale detection), WHERE it belongs
 * (host-side path — never inside the model's JSON) and the model's branch JSON itself.
 */
public final class BranchEditRequest {

    private final long baseRevision;
    private final JsonBranchPath targetPath;
    private final String branchJson;

    public BranchEditRequest(long baseRevision, JsonBranchPath targetPath, String branchJson) {
        this.baseRevision = baseRevision;
        this.targetPath = targetPath;
        this.branchJson = branchJson;
    }

    public long getBaseRevision() {
        return baseRevision;
    }

    public JsonBranchPath getTargetPath() {
        return targetPath;
    }

    public String getBranchJson() {
        return branchJson;
    }
}
