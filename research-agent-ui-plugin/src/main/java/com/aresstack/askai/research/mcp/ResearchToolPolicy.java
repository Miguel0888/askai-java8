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
    private static final int REVIEW_CONTEXT_MAXIMUM_SOURCES = 12;
    private static final int REVIEW_CONTEXT_MAXIMUM_CHARACTERS_PER_SOURCE = 1_200;

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
                        List<ResearchSourceRecord> reviewable = new ArrayList<ResearchSourceRecord>();
                        for (ResearchSourceRecord record : ctx.sourceRepository().find(SourceQuery.all())) {
                            if (isReviewable(record)
                                    && (capturedThrough <= 0L
                                            || record.getCapturedAt() <= capturedThrough)) {
                                reviewable.add(record);
                            }
                        }
                        java.util.Collections.sort(reviewable,
                                new java.util.Comparator<ResearchSourceRecord>() {
                                    public int compare(ResearchSourceRecord a, ResearchSourceRecord b) {
                                        return Long.compare(b.getCapturedAt(), a.getCapturedAt());
                                    }
                                });
                        StringBuilder sb = new StringBuilder();
                        int rendered = 0;
                        for (ResearchSourceRecord record : reviewable) {
                            if (rendered >= REVIEW_CONTEXT_MAXIMUM_SOURCES) {
                                sb.append("(").append(reviewable.size() - rendered)
                                  .append(" further sources not shown in this review context)\n");
                                break;
                            }
                            appendSource(sb, record);
                            rendered++;
                        }
                        return McpToolResult.ok(sb.length() == 0
                                ? "No sources to review." : sb.toString());
                    }
                },
                McpToolParameter.string("captured_through", false,
                        "Only sources captured at or before this epoch-millis timestamp (0 = all)"));
    }

    private static void appendSource(StringBuilder sb, ResearchSourceRecord record) {
        sb.append("--- ").append(record.getSourceId()).append('\n');
        appendIfPresent(sb, "title: ", record.getTitle());
        appendIfPresent(sb, "url: ", record.getUrl());
        appendIfPresent(sb, "found via: ", record.getSearchQuery());
        String text = record.getFullText() == null || record.getFullText().trim().isEmpty()
                ? record.getExcerpt() : record.getFullText();
        if (text != null && !text.trim().isEmpty()) {
            String trimmed = text.trim();
            boolean cut = trimmed.length() > REVIEW_CONTEXT_MAXIMUM_CHARACTERS_PER_SOURCE;
            sb.append("text: ")
              .append(cut ? trimmed.substring(0, REVIEW_CONTEXT_MAXIMUM_CHARACTERS_PER_SOURCE) : trimmed)
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
