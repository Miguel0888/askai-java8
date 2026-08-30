package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import java.util.ArrayList;
import java.util.List;

/**
 * THE central mapping from the hierarchical research state to the offered MCP tools — there is no second
 * phase matrix anywhere else. Visibility comes from (phaseId, stateId); every write handler re-checks the SAME
 * rule against the live {@link ResearchControlContext} state at execution time (visibility ≠ authorization).
 *
 * <p>Always readable: {@code research_status}, {@code artifact_read(name)}, {@code source_list}. Write tools
 * are bound to phase AND run-state (never in waiting_approval/paused/blocked/failed/terminal):
 * RESEARCH/running → {@code source_accept}, {@code source_park}; DRAFT/running and FINALIZATION/running →
 * {@code document_read}, {@code document_save} — BOTH phases work on the ONE canonical document (issue #32).
 * The legacy per-stage tools ({@code concept_save}, {@code finding_add}, {@code notes_append},
 * {@code draft_*}, {@code final_*}) are gone: the ResearchBrief is the scoping truth and the removed legacy
 * artifacts must no longer be written by the active workflow. {@code outline_save} stays ONLY for persisted
 * old sessions still sitting in the legacy OUTLINE phase. There is deliberately NO phase-transition tool
 * (no advance_phase/approve_phase/set_state).</p>
 */
public final class ResearchToolPolicy {

    private ResearchToolPolicy() {
    }

    /** @return whether write tools of {@code phaseId} are usable in {@code stateId} (running only). */
    static boolean writable(String phaseId, String stateId, String requiredPhaseId) {
        return requiredPhaseId.equals(phaseId) && ResearchStateIds.RUNNING.equals(stateId);
    }

    /** Compute the offered tool set for the current state. */
    public static List<McpToolContribution> toolsFor(String phaseId, String stateId,
                                                     ResearchControlContext ctx) {
        List<McpToolContribution> tools = new ArrayList<McpToolContribution>();
        // Always readable.
        tools.add(statusTool(ctx));
        tools.add(artifactReadTool(ctx));
        tools.add(sourceListTool(ctx));
        tools.add(sourceReviewContextTool(ctx));
        // The Konzeptpapier tools (Fragestellung→Konzept rework). READING the concept is allowed in
        // every phase (frozen artifacts stay readable); WRITING is bite-wise branch editing and is
        // bound to SCOPING/running. Absent service (clickdummy/old fakes) → no concept tools at all.
        if (ctx.conceptBranchService() != null) {
            tools.add(conceptReadTool(ctx));
            if (writable(phaseId, stateId, ResearchStateIds.SCOPING)) {
                tools.add(conceptUpdateTool(ctx));
                tools.add(conceptRemoveTool(ctx));
            }
        }
        // Phase + run-state gated writes. SCOPING has NO document tool anymore: the ResearchBrief is the
        // canonical scoping artifact (issue #32) — no second concept document beside it.
        if (writable(phaseId, stateId, ResearchStateIds.OUTLINE)) {
            // Legacy operability only: persisted old sessions still sitting in the OUTLINE phase.
            tools.add(saveTool(ctx, "outline_save", "outline", ResearchStateIds.OUTLINE));
        }
        if (writable(phaseId, stateId, ResearchStateIds.RESEARCH)) {
            tools.add(sourceAcceptTool(ctx));
            tools.add(sourceParkTool(ctx));
        }
        // DRAFT and FINALIZATION both work on the ONE canonical document; whether FINALIZATION survives as
        // its own outer phase is #30's decision — this policy does not pre-empt it.
        if (writable(phaseId, stateId, ResearchStateIds.DRAFT)) {
            tools.add(readTool(ctx, "document_read", "document"));
            tools.add(saveTool(ctx, "document_save", "document", ResearchStateIds.DRAFT));
        }
        if (writable(phaseId, stateId, ResearchStateIds.FINALIZATION)) {
            tools.add(readTool(ctx, "document_read", "document"));
            tools.add(saveTool(ctx, "document_save", "document", ResearchStateIds.FINALIZATION));
        }
        return tools;
    }

    // ------------------------------------------------------------------ handlers (server-side re-check)

    private static McpToolResult requireWritable(ResearchControlContext ctx, String requiredPhaseId) {
        if (!writable(ctx.currentPhaseId(), ctx.currentStateId(), requiredPhaseId)) {
            return McpToolResult.error("Not allowed in the current state ("
                    + ctx.currentPhaseId() + "/" + ctx.currentStateId() + ").");
        }
        return null;
    }

    private static McpToolContribution statusTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("research_status", "Current research phase, run state and revision.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        return McpToolResult.ok(ctx.statusLine());
                    }
                });
    }

    private static McpToolContribution artifactReadTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("artifact_read", "Read a markdown artifact by name.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String name = call.getString("name");
                        if (name == null || name.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: name");
                        }
                        ArtifactContent content = ctx.artifactStore().read(name.trim());
                        return McpToolResult.ok("revision=" + content.getRevision()
                                + "\n" + content.getMarkdown());
                    }
                },
                McpToolParameter.string("name", true, "Artifact id: outline, document"));
    }

    private static McpToolContribution sourceListTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("source_list", "List the research sources.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        StringBuilder sb = new StringBuilder();
                        for (ResearchSourceRecord r : ctx.sourceRepository().find(SourceQuery.all())) {
                            sb.append(r.getSourceId()).append(": ").append(r.getTitle())
                              .append(" [").append(r.getStatus()).append("] rev=")
                              .append(r.getRevision()).append('\n');
                        }
                        return McpToolResult.ok(sb.length() == 0 ? "No sources." : sb.toString());
                    }
                });
    }

    /**
     * How many sources one review turn may see, and how much of each. A review is a reading task with a
     * finite context: handing over the whole corpus would push the material that matters out of the
     * window, and handing over nothing is what made the agent summarise titles.
     */
    // The bounds themselves are the user's settings, served by the context (never local constants):
    // ResearchControlContext.reviewContextMaximumSources() / reviewContextMaximumCharactersPerSource().

    /** Statuses that carry real material; PARKED candidates have no text yet, excluded ones are out. */
    private static boolean isReviewable(ResearchSourceRecord record) {
        return record.getStatus() == com.aresstack.askai.research.sources.SourceStatus.NEW
                || record.getStatus() == com.aresstack.askai.research.sources.SourceStatus.REVIEWED
                || record.getStatus() == com.aresstack.askai.research.sources.SourceStatus.ACCEPTED;
    }

    /**
     * The READ-ONLY content the post-search review actually works on.
     * <p>
     * {@code source_list} answers "which sources exist" with ids, titles and statuses — the right answer to
     * a different question. A review asked to report what WE LEARNED had nothing but those titles to go on,
     * so it could only invent. This tool hands over the material itself: what was searched for, what the
     * page promised, and a bounded piece of what it actually said.
     * <p>
     * {@code captured_through} pins the review to the same corpus the host recorded as its target, so a
     * source that arrives while the review runs is not silently counted as reviewed.
     */
    private static McpToolContribution sourceReviewContextTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("source_review_context",
                "Read the content of the research sources to review (bounded, newest first).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        long capturedThrough = parseLong(call.getString("captured_through"));
                        // The WINDOW decides what "the new sources" means: without a lower bound every
                        // review re-read the whole cumulative corpus and produced the same summary and the
                        // same clusters, search after search.
                        long capturedSince = parseLong(call.getString("captured_since"));
                        // Request-id scoping beats the time window: sources tagged with a DIFFERENT
                        // search are OUT even inside the window. Untagged (legacy/agent) records keep
                        // falling back to the window alone.
                        String requestId = call.getString("search_request_id");
                        String wantedRequestId = requestId == null ? "" : requestId.trim();
                        List<ResearchSourceRecord> reviewable = new ArrayList<ResearchSourceRecord>();
                        for (ResearchSourceRecord record : ctx.sourceRepository().find(SourceQuery.all())) {
                            boolean requestMatches = wantedRequestId.isEmpty()
                                    || record.getSearchRequestId().isEmpty()
                                    || wantedRequestId.equals(record.getSearchRequestId());
                            if (isReviewable(record) && requestMatches
                                    && (capturedThrough <= 0L
                                            || record.getCapturedAt() <= capturedThrough)
                                    && (capturedSince <= 0L
                                            || record.getCapturedAt() >= capturedSince)) {
                                reviewable.add(record);
                            }
                        }
                        // RELEVANCE order, not arrival order: the review's bounded context takes the
                        // BEST-ranked material first, so a poorly-ranked source can never crowd out a
                        // good one — treating them all alike made junk exactly as loud as the hits.
                        java.util.Collections.sort(reviewable,
                                new java.util.Comparator<ResearchSourceRecord>() {
                                    public int compare(ResearchSourceRecord a, ResearchSourceRecord b) {
                                        boolean aScored = !Double.isNaN(a.getRerankScore());
                                        boolean bScored = !Double.isNaN(b.getRerankScore());
                                        if (aScored && bScored
                                                && a.getRerankScore() != b.getRerankScore()) {
                                            return Double.compare(b.getRerankScore(),
                                                    a.getRerankScore());
                                        }
                                        if (aScored != bScored) {
                                            return aScored ? -1 : 1; // scored material first
                                        }
                                        return Long.compare(b.getCapturedAt(), a.getCapturedAt());
                                    }
                                });
                        StringBuilder sb = new StringBuilder();
                        int rendered = 0;
                        for (ResearchSourceRecord record : reviewable) {
                            if (rendered >= ctx.reviewContextMaximumSources()) {
                                sb.append("(").append(reviewable.size() - rendered)
                                  .append(" further sources not shown in this review context)\n");
                                break;
                            }
                            appendSource(sb, record, ctx.reviewContextMaximumCharactersPerSource());
                            rendered++;
                        }
                        return McpToolResult.ok(sb.length() == 0
                                ? "No sources to review." : sb.toString());
                    }
                },
                McpToolParameter.string("captured_through", false,
                        "Only sources captured at or before this epoch-millis timestamp (0 = all)"),
                McpToolParameter.string("captured_since", false,
                        "Only sources captured at or after this epoch-millis timestamp (0 = all) — "
                                + "the lower edge of \"the new sources\" of one search"),
                McpToolParameter.string("search_request_id", false,
                        "Only sources found by THIS manual-search request (empty = no id scoping; "
                                + "untagged legacy sources fall back to the time window)"));
    }

    private static void appendSource(StringBuilder sb, ResearchSourceRecord record,
                                     int maximumCharacters) {
        sb.append("--- ").append(record.getSourceId()).append('\n');
        appendIfPresent(sb, "title: ", record.getTitle());
        appendIfPresent(sb, "url: ", record.getUrl());
        appendIfPresent(sb, "found via: ", record.getSearchQuery());
        if (!Double.isNaN(record.getRerankScore())) {
            // The ranking travels WITH the material: the reviewer weighs a weak hit as a weak hit.
            appendIfPresent(sb, "relevance score (higher = more relevant to the query): ",
                    String.valueOf(record.getRerankScore()));
        }
        String text = record.getFullText() == null || record.getFullText().trim().isEmpty()
                ? record.getExcerpt() : record.getFullText();
        if (text != null && !text.trim().isEmpty()) {
            String trimmed = text.trim();
            boolean cut = trimmed.length() > maximumCharacters;
            sb.append("text: ")
              .append(cut ? trimmed.substring(0, maximumCharacters) : trimmed)
              .append(cut ? " […]" : "").append('\n');
        } else {
            // Say it rather than let the agent assume the page said nothing worth reporting.
            sb.append("text: (no readable text was captured for this source)\n");
        }
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            sb.append(label).append(value.trim()).append('\n');
        }
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.trim().isEmpty() ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    private static McpToolContribution readTool(final ResearchControlContext ctx,
                                                String toolName, final String artifactId) {
        return McpToolContribution.of(toolName, "Read the " + artifactId + " document.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        ArtifactContent content = ctx.artifactStore().read(artifactId);
                        return McpToolResult.ok("revision=" + content.getRevision()
                                + "\n" + content.getMarkdown());
                    }
                });
    }

    private static McpToolContribution saveTool(final ResearchControlContext ctx, String toolName,
                                                final String artifactId, final String requiredPhaseId) {
        return McpToolContribution.of(toolName,
                "Replace the " + artifactId + " document (optimistic locking via expected_revision).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, requiredPhaseId);
                        if (denied != null) {
                            return denied;
                        }
                        String markdown = call.getString("markdown");
                        long expected = call.getInteger("expected_revision", -1L);
                        if (markdown == null || expected < 0) {
                            return McpToolResult.error("Required: markdown, expected_revision");
                        }
                        ArtifactWriteResult result =
                                ctx.artifactStore().replace(artifactId, expected, markdown);
                        return result.isSuccess()
                                ? McpToolResult.ok("saved revision=" + result.getRevision())
                                : McpToolResult.error("Conflict: " + result.getReason()
                                        + " current revision=" + result.getRevision());
                    }
                },
                McpToolParameter.string("markdown", true, "The full new markdown content"),
                McpToolParameter.integer("expected_revision", true, "The revision this edit is based on"));
    }

    private static McpToolContribution sourceAcceptTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("source_accept",
                "Accept a visited capture as a persistent research source.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.RESEARCH);
                        if (denied != null) {
                            return denied;
                        }
                        String captureId = call.getString("capture_id");
                        if (captureId == null || captureId.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: capture_id");
                        }
                        // The context returns the compact Commit-37 acceptance line verbatim
                        // (status/source_id/title/passage_count/duplicate[/index=STALE]).
                        String result = ctx.acceptCapture(captureId.trim());
                        return result == null
                                ? McpToolResult.error("Unknown capture: " + captureId)
                                : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("capture_id", true, "The capture id from a visited page"));
    }

    private static McpToolContribution sourceParkTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("source_park",
                "Park a reranked search candidate as a scored source before it is visited (empty full text).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.RESEARCH);
                        if (denied != null) {
                            return denied;
                        }
                        String url = call.getString("url");
                        if (url == null || url.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: url");
                        }
                        String result = ctx.parkCandidate(url.trim(),
                                emptyIfNull(call.getString("title")),
                                emptyIfNull(call.getString("excerpt")),
                                parseScore(call.getString("score")), "");
                        return result == null ? McpToolResult.error("Could not park: " + url)
                                : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("url", true, "The candidate's resolved target URL"),
                McpToolParameter.string("title", false, "The candidate title"),
                McpToolParameter.string("excerpt", false, "The search-result snippet/excerpt"),
                McpToolParameter.string("score", false, "The reranker relevance score (a double)"));
    }

    // ------------------------------------------------------------------ Konzeptpapier tools
    //
    // The model edits the concept in BITES, never as a whole document: read a branch (getting an
    // opaque handle), send back a refined branch, get either "applied revision=N" or a structured
    // diagnostic it can act on. All semantics live in ConceptBranchService (deterministic, one
    // attempt, no retries) — the repair LOOP is the caller's business, per the agreed layering.

    private static McpToolContribution conceptReadTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("concept_read",
                "Read the concept tree or one branch of it. Returns a branch handle for editing.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        java.util.List<String> names = splitPath(call.getString("path"));
                        int depth = (int) call.getInteger("depth", 0);
                        com.aresstack.askai.research.concept.ConceptBranchService.ReadResult result =
                                ctx.conceptBranchService().readBranch(names, depth);
                        if (!result.isOk()) {
                            return McpToolResult.error(result.getDiagnostic().describeForModel());
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("handle=").append(result.getHandleId())
                          .append(" revision=").append(result.getWorkingRevision())
                          .append(" editable=").append(result.isEditable()).append('\n');
                        if (result.getParentName() != null) {
                            sb.append("parent=").append(result.getParentName()).append('\n');
                        }
                        if (!result.getSiblingNames().isEmpty()) {
                            sb.append("siblings=");
                            for (int i = 0; i < result.getSiblingNames().size(); i++) {
                                sb.append(i > 0 ? ", " : "").append(result.getSiblingNames().get(i));
                            }
                            sb.append('\n');
                        }
                        sb.append(result.getBranchJson());
                        return McpToolResult.ok(sb.toString());
                    }
                },
                McpToolParameter.string("path", false,
                        "Node names from the concept root, separated by '/'. Empty = whole concept."),
                McpToolParameter.integer("depth", false,
                        "Limit the subtree depth (orientation only — a depth-limited handle cannot "
                                + "be used for editing). 0 = full depth, editable."));
    }

    private static McpToolContribution conceptUpdateTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("concept_update",
                "Refine ONE concept branch (non-destructive: existing nodes must survive; moving "
                        + "them is fine). Send the branch as {\"Name\": [ ... ]}.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        String handle = call.getString("handle");
                        String branch = call.getString("branch_json");
                        if (handle == null || handle.trim().isEmpty()
                                || branch == null || branch.trim().isEmpty()) {
                            return McpToolResult.error("Required: handle, branch_json");
                        }
                        boolean allowRemovals =
                                "true".equalsIgnoreCase(call.getString("allow_removals"));
                        com.aresstack.askai.research.concept.ConceptBranchService.EditResult result =
                                ctx.conceptBranchService()
                                        .updateBranch(handle.trim(), branch, allowRemovals);
                        if (!result.isApplied()) {
                            return McpToolResult.error(result.getDiagnostic().describeForModel());
                        }
                        ctx.onConceptChanged(result.getNewRevision());
                        return McpToolResult.ok("applied revision=" + result.getNewRevision());
                    }
                },
                McpToolParameter.string("handle", true, "The branch handle from concept_read"),
                McpToolParameter.string("branch_json", true,
                        "The complete refined branch: one object with exactly one array property"),
                McpToolParameter.string("allow_removals", false,
                        "\"true\" to permit dropping existing nodes (default: refused)"));
    }

    private static McpToolContribution conceptRemoveTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("concept_remove",
                "Remove ONE concept node with its whole subtree. Deliberately destructive.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        String handle = call.getString("handle");
                        if (handle == null || handle.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: handle");
                        }
                        com.aresstack.askai.research.concept.ConceptBranchService.EditResult result =
                                ctx.conceptBranchService().removeBranch(handle.trim());
                        if (!result.isApplied()) {
                            return McpToolResult.error(result.getDiagnostic().describeForModel());
                        }
                        ctx.onConceptChanged(result.getNewRevision());
                        return McpToolResult.ok("removed revision=" + result.getNewRevision());
                    }
                },
                McpToolParameter.string("handle", true, "The branch handle from concept_read"));
    }

    /** "A/B/C" → [A, B, C]; empty/null → the concept root. */
    private static java.util.List<String> splitPath(String path) {
        java.util.List<String> names = new ArrayList<String>();
        if (path != null) {
            for (String part : path.split("/")) {
                if (!part.trim().isEmpty()) {
                    names.add(part.trim());
                }
            }
        }
        return names;
    }

    private static String emptyIfNull(String v) {
        return v == null ? "" : v;
    }

    private static double parseScore(String v) {
        if (v == null || v.trim().isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

}
