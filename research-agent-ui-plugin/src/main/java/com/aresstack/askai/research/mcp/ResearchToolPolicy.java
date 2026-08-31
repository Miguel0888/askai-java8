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
                tools.add(conceptAddTool(ctx));
                tools.add(conceptRemoveTool(ctx));
                // The ONE-command exclusion facade (live-gate 4): the model quotes the user's
                // term, the host owns id/facet/blacklist and the concept-conflict check.
                tools.add(excludeTopicTool(ctx));
                tools.add(resolveConflictTool(ctx));
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
    // The SMALL-MODEL facade (K2c, the MainframeMate lesson): tiny atomic operations addressed
    // by a human-readable name path — no handles, no revisions, no full-branch replacement in
    // the model contract. Examples live IN the tool description; required arguments are
    // validated server-side BEFORE dispatch; all transactional machinery (strict parse,
    // candidate validation, atomic commit) runs inside ConceptBranchService on every call.

    private static McpToolContribution conceptReadTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("concept_read",
                "Read the concept tree or one of its branches. "
                        + "Example: path=\"\" (whole concept). Example: path=\"FreeRTOS\". "
                        + "Example: path=\"FreeRTOS/Kommunikation\".",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        com.aresstack.askai.research.concept.ConceptBranchService.ReadResult result =
                                ctx.conceptBranchService()
                                        .readBranch(segmentsOf(call, "path"), 0);
                        if (!result.isOk()) {
                            return McpToolResult.error(result.getDiagnostic().describeForModel());
                        }
                        StringBuilder sb = new StringBuilder();
                        // Revision as DISPLAYED state (the CURRENT_CONCEPT block cites it) — it
                        // is never a token the model must echo back.
                        sb.append("revision=").append(result.getWorkingRevision()).append('\n');
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
                McpToolParameter.string("path_json", false,
                        "The segments as a JSON array of card names — the unambiguous form"));
    }

    private static McpToolContribution conceptAddTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("concept_add",
                "Add ONE new topic card to the concept. "
                        + "Example: parent_path=\"\", name=\"FreeRTOS\" (a top-level card). "
                        + "Example: parent_path=\"FreeRTOS/Kommunikation\", "
                        + "name=\"Task Notifications\". "
                        + "If unsure whether the parent exists, call concept_read first.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        String name = call.getString("name");
                        if (name == null || name.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: name — example: "
                                    + "parent_path=\"FreeRTOS\", name=\"Synchronisation\"");
                        }
                        com.aresstack.askai.research.concept.ConceptBranchService.EditResult result =
                                ctx.conceptBranchService().addNode(
                                        segmentsOf(call, "parent_path"), name.trim());
                        if (!result.isApplied()) {
                            return McpToolResult.error(result.getDiagnostic().describeForModel());
                        }
                        ctx.onConceptChanged(result.getNewRevision());
                        return McpToolResult.ok("added \"" + name.trim() + "\" revision="
                                + result.getNewRevision());
                    }
                },
                McpToolParameter.string("parent_path", false,
                        "The parent card's names from the concept root, separated by '/'. "
                                + "Empty = add a top-level card."),
                McpToolParameter.string("parent_path_json", false,
                        "The parent segments as a JSON array of card names — the unambiguous "
                                + "form ('/' in a name stays a character)"),
                McpToolParameter.string("name", true, "The new card's name (short noun phrase)"));
    }

    private static McpToolContribution conceptRemoveTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("concept_remove",
                "Remove ONE topic card (and everything under it) from the concept. Deliberately "
                        + "destructive. Example: path=\"FreeRTOS/Praxis/ESP-IDF\".",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        String path = call.getString("path");
                        String pathJson = call.getString("path_json");
                        if ((path == null || path.trim().isEmpty())
                                && (pathJson == null || pathJson.trim().isEmpty())) {
                            return McpToolResult.error("Missing argument: path — example: "
                                    + "path=\"FreeRTOS/Praxis/ESP-IDF\" or "
                                    + "path_json=[\"FreeRTOS\",\"Praxis\",\"ESP-IDF\"]");
                        }
                        com.aresstack.askai.research.concept.ConceptBranchService.EditResult result =
                                ctx.conceptBranchService().removeNodeAt(segmentsOf(call, "path"));
                        if (!result.isApplied()) {
                            return McpToolResult.error(result.getDiagnostic().describeForModel());
                        }
                        ctx.onConceptChanged(result.getNewRevision());
                        return McpToolResult.ok("removed \""
                                + (path == null || path.trim().isEmpty() ? pathJson.trim()
                                        : path.trim())
                                + "\" revision=" + result.getNewRevision());
                    }
                },
                McpToolParameter.string("path", false,
                        "The card's names from the concept root, separated by '/'"),
                McpToolParameter.string("path_json", false,
                        "The segments as a JSON array of card names — the unambiguous form"));
    }

    /**
     * The ONE-command exclusion facade: the model hands over the USER'S term verbatim; the host
     * derives the id, records the EXCLUDED facet (immediately effective), republishes the fence
     * and scans the concept for an EXACT name match. A conflict comes back as a structured
     * {@code conceptConflict} with an opaque conflictId + {@code INFORM_AND_ASK_REMOVE} — the
     * tool NEVER deletes anything itself.
     */
    private static McpToolContribution excludeTopicTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("exclude_topic",
                "Record a topic the user ruled out, in the USER'S words. The application derives "
                        + "the id, suppresses the topic for all research and checks the concept "
                        + "for a conflicting entry. Example: topic=\"ESP-IDF\".",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        String topic = call.getString("topic");
                        if (topic == null || topic.trim().isEmpty()) {
                            return McpToolResult.error(
                                    "Missing argument: topic — example: topic=\"ESP-IDF\"");
                        }
                        String reply = ctx.excludeTopic(topic.trim());
                        if (reply == null) {
                            return McpToolResult.error("This session has no scope system.");
                        }
                        // A rejected commit is the tool's REJECTION (ToolFailure on the runtime
                        // side, repair budget) — never a green result.
                        return reply.contains("\"result\":\"REJECTED\"")
                                ? McpToolResult.error(reply) : McpToolResult.ok(reply);
                    }
                },
                McpToolParameter.string("topic", true,
                        "The excluded topic in the user's own words, e.g. \"ESP-IDF\""));
    }

    /** Resolve a reported concept conflict — only ever AFTER the user answered the question. */
    private static McpToolContribution resolveConflictTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("resolve_concept_conflict",
                "Resolve a concept conflict exclude_topic reported, AFTER the user decided: "
                        + "decision=REMOVE deletes the concept entry, decision=KEEP_SUPPRESSED "
                        + "keeps it (research stays suppressed). Example: "
                        + "conflict_id=\"conflict-1\" decision=\"REMOVE\".",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        String conflictId = call.getString("conflict_id");
                        String decision = call.getString("decision");
                        if (conflictId == null || conflictId.trim().isEmpty()
                                || decision == null || decision.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: conflict_id and "
                                    + "decision are required — example: "
                                    + "conflict_id=\"conflict-1\" decision=\"REMOVE\"");
                        }
                        String reply = ctx.resolveConceptConflict(conflictId.trim(),
                                decision.trim());
                        return reply == null
                                ? McpToolResult.error("This session has no scope system.")
                                : (reply.startsWith("{") ? McpToolResult.ok(reply)
                                        : McpToolResult.error(reply));
                    }
                },
                McpToolParameter.string("conflict_id", true,
                        "The opaque id from exclude_topic's conceptConflict"),
                McpToolParameter.string("decision", true,
                        "REMOVE (delete the concept entry) or KEEP_SUPPRESSED (keep it)"));
    }

    /**
     * Segment resolution with the UNAMBIGUOUS form first: {@code <name>_json} carries a JSON
     * array of card names (the runtime's transport — '/' in a name stays a character, a missed
     * parent can never collapse into one literal root name); the plain {@code <name>} parameter
     * keeps the slash-joined convenience for external drivers and humans.
     */
    private static java.util.List<String> segmentsOf(McpToolCall call, String parameter) {
        String json = call.getString(parameter + "_json");
        if (json != null && !json.trim().isEmpty()) {
            java.util.List<String> segments = new ArrayList<String>();
            try {
                com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(json);
                if (parsed.isJsonArray()) {
                    for (com.google.gson.JsonElement element : parsed.getAsJsonArray()) {
                        String segment = element.isJsonPrimitive() ? element.getAsString() : "";
                        if (!segment.trim().isEmpty()) {
                            segments.add(segment.trim());
                        }
                    }
                    return segments;
                }
            } catch (RuntimeException notJson) {
                // fall through to the slash form — an unreadable array must not silently
                // reinterpret the call
            }
        }
        return splitPath(call.getString(parameter));
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
