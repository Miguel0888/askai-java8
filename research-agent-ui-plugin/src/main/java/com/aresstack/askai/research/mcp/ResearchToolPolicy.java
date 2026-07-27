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
 * SCOPING/running → {@code concept_save}; OUTLINE/running → {@code outline_save}; RESEARCH/running →
 * {@code source_accept}, {@code finding_add}, {@code notes_append}; DRAFT/running → {@code draft_read},
 * {@code draft_save}; FINALIZATION/running → {@code final_read}, {@code final_save}. There is deliberately NO
 * phase-transition tool (no advance_phase/approve_phase/set_state).</p>
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
        // Phase + run-state gated writes.
        if (writable(phaseId, stateId, ResearchStateIds.SCOPING)) {
            tools.add(saveTool(ctx, "concept_save", "concept", ResearchStateIds.SCOPING));
        }
        if (writable(phaseId, stateId, ResearchStateIds.OUTLINE)) {
            tools.add(saveTool(ctx, "outline_save", "outline", ResearchStateIds.OUTLINE));
        }
        if (writable(phaseId, stateId, ResearchStateIds.RESEARCH)) {
            tools.add(sourceAcceptTool(ctx));
            tools.add(findingAddTool(ctx));
            tools.add(notesAppendTool(ctx));
        }
        if (writable(phaseId, stateId, ResearchStateIds.DRAFT)) {
            tools.add(readTool(ctx, "draft_read", "draft"));
            tools.add(saveTool(ctx, "draft_save", "draft", ResearchStateIds.DRAFT));
        }
        if (writable(phaseId, stateId, ResearchStateIds.FINALIZATION)) {
            tools.add(readTool(ctx, "final_read", "final"));
            tools.add(saveTool(ctx, "final_save", "final", ResearchStateIds.FINALIZATION));
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
                McpToolParameter.string("name", true,
                        "Artifact id: outline, concept, research-notes, findings, draft, final"));
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
                        String sourceId = ctx.acceptCapture(captureId.trim());
                        return sourceId == null
                                ? McpToolResult.error("Unknown capture: " + captureId)
                                : McpToolResult.ok("accepted sourceId=" + sourceId);
                    }
                },
                McpToolParameter.string("capture_id", true, "The capture id from a visited page"));
    }

    private static McpToolContribution findingAddTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("finding_add", "Record a finding referencing an accepted source.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.RESEARCH);
                        if (denied != null) {
                            return denied;
                        }
                        String sourceId = call.getString("source_id");
                        String text = call.getString("text");
                        if (sourceId == null || text == null || text.trim().isEmpty()) {
                            return McpToolResult.error("Required: source_id, text");
                        }
                        if (ctx.sourceRepository().get(sourceId) == null) {
                            return McpToolResult.error("Unknown source: " + sourceId);
                        }
                        return appendTo(ctx, "findings", "- [" + sourceId + "] " + text.trim());
                    }
                },
                McpToolParameter.string("source_id", true, "An accepted source id"),
                McpToolParameter.string("text", true, "The finding text"));
    }

    private static McpToolContribution notesAppendTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("notes_append", "Append markdown to the research notes.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.RESEARCH);
                        if (denied != null) {
                            return denied;
                        }
                        String markdown = call.getString("markdown");
                        if (markdown == null || markdown.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: markdown");
                        }
                        return appendTo(ctx, "research-notes", markdown.trim());
                    }
                },
                McpToolParameter.string("markdown", true, "The markdown to append"));
    }

    /** Read-modify-write append with the store's optimistic lock (single retry is unnecessary: same thread). */
    private static McpToolResult appendTo(ResearchControlContext ctx, String artifactId, String block) {
        ArtifactContent current = ctx.artifactStore().read(artifactId);
        String next = current.getMarkdown().isEmpty() ? block : current.getMarkdown() + "\n" + block;
        ArtifactWriteResult result = ctx.artifactStore().replace(artifactId, current.getRevision(), next);
        return result.isSuccess()
                ? McpToolResult.ok("appended revision=" + result.getRevision())
                : McpToolResult.error("Conflict while appending: " + result.getReason());
    }
}
