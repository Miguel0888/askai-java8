package com.aresstack.askai.research.runtime;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The external Solon research agent process. It mirrors host state and NEVER owns a research state machine —
 * the plugin/host is the only transition authority; this process only calls the MCP tools it is offered.
 *
 * <p>Readiness is REAL, not configured: {@code session/new} connects the research-control MCP endpoint, runs
 * {@code tools/list} and calls {@code research_status()}; only if that round-trip succeeds does session
 * creation succeed (otherwise the host start fails atomically). The first prompt turn then reports
 * {@code RESEARCH_MCP_READY} (and {@code BROWSER_NOT_AVAILABLE} when no browser endpoint exists — visible,
 * never fatal, never a silent fallback). ACP carries prompt/streaming/status/errors; MCP carries the research
 * tools — the same operations are never doubled as custom ACP requests. Logs go to STDERR only.</p>
 */
@AcpAgent(name = "askai-research-agent", version = "0.1")
public final class ResearchAgentMain {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ResearchAgentEnvironment environment;
    private McpClientProvider researchMcp;
    private volatile boolean readinessAnnounced;

    public static void main(String[] args) {
        System.err.println("[research-agent] starting");
        AcpAgentSupport.create(new ResearchAgentMain())
                .transport(new StdioAcpAgentTransport())
                .build().run();
        System.err.println("[research-agent] terminated");
    }

    @Initialize
    public AcpSchema.InitializeResponse initialize() {
        System.err.println("[research-agent] initialize");
        environment = ResearchAgentEnvironment.from(System.getenv());
        System.err.println("[research-agent] " + environment); // toString never contains tokens
        return AcpSchema.InitializeResponse.ok();
    }

    @NewSession
    public AcpSchema.NewSessionResponse newSession() {
        // Real readiness check: connect research MCP, list tools, call research_status. A failure here fails
        // session/new, so the host start is atomic (no half-started session).
        researchMcp = McpClientProvider.builder()
                .apiUrl(environment.researchUrl)
                .channel(environment.researchTransport)
                .cacheSeconds(0)
                .initializationTimeout(Duration.ofSeconds(15))
                .requestTimeout(Duration.ofSeconds(15))
                .build();
        boolean hasStatus = false;
        for (FunctionTool tool : researchMcp.getTools()) {
            if ("research_status".equals(tool.name())) {
                hasStatus = true;
            }
        }
        if (!hasStatus) {
            throw new IllegalStateException("research_status is not offered by the research MCP endpoint");
        }
        ToolResult status = researchMcp.callTool("research_status",
                Collections.<String, Object>emptyMap());
        System.err.println("[research-agent] research_status ok: " + status);
        return new AcpSchema.NewSessionResponse("research-acp-" + environment.sessionId, null, null);
    }

    @Cancel
    public void cancel() {
        System.err.println("[research-agent] cancel");
        cancelled.set(true);
    }

    @Prompt
    public AcpSchema.PromptResponse prompt(SyncPromptContext ctx, AcpSchema.PromptRequest request) {
        System.err.println("[research-agent] prompt turn started");
        cancelled.set(false);
        if (!readinessAnnounced) {
            readinessAnnounced = true;
            ctx.sendMessage("RESEARCH_MCP_READY");
            if (!environment.hasBrowser()) {
                ctx.sendMessage("BROWSER_NOT_AVAILABLE");
            }
        }
        String text = request.text() == null ? "" : request.text();
        ctx.sendThought("planning: " + text);
        if (cancelled.get()) {
            return new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED);
        }
        // Autonomous web research turn: a NORMAL user question starts the loop whenever the HOST state
        // machine (mirrored via research_status — this process owns no state) is in research/running.
        // The legacy explicit "research:" prefix keeps working. Without a browser endpoint this is a
        // visible, honest refusal — never a fallback.
        boolean explicitResearch = text.startsWith("research:");
        String task = explicitResearch ? text.substring("research:".length()).trim() : text.trim();
        if ((explicitResearch || hostIsInResearchRunning()) && !task.isEmpty()) {
            if (!environment.hasBrowser()) {
                ctx.sendMessage("BROWSER_NOT_AVAILABLE: cannot run autonomous web research this turn.");
                return AcpSchema.PromptResponse.endTurn();
            }
            runResearchLoop(ctx, task);
            return cancelled.get()
                    ? new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED)
                    : AcpSchema.PromptResponse.endTurn();
        }
        // Mirror the host state via MCP (no own state machine): report the live research status.
        try {
            ToolResult status = researchMcp.callTool("research_status",
                    Collections.<String, Object>emptyMap());
            ctx.sendMessage("status: " + status);
        } catch (RuntimeException ex) {
            ctx.sendMessage("research MCP unavailable: " + ex.getMessage());
        }
        if (text.contains("slow")) {
            for (int i = 0; i < 1_000_000 && !cancelled.get(); i++) {
                ctx.sendMessage("working " + i);
            }
            if (cancelled.get()) {
                return new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED);
            }
        }
        ctx.sendMessage("turn done for: " + text);
        return AcpSchema.PromptResponse.endTurn();
    }

    /** Mirror the host state: research phase in run state — the only condition for an autonomous turn. */
    private boolean hostIsInResearchRunning() {
        try {
            String status = String.valueOf(researchMcp.callTool("research_status",
                    Collections.<String, Object>emptyMap()));
            return status.contains("research/running");
        } catch (RuntimeException ex) {
            return false; // unreachable status → answer as a plain turn, never start a blind run
        }
    }

    /**
     * The 36A loop, verbatim: content-driven, centrally budgeted, PHASE_READY as an EVENT line (the host
     * remains the only state authority — this process never switches phases). Stop reason and progress are
     * reported explicitly over ACP, never only to logs.
     */
    private void runResearchLoop(final SyncPromptContext ctx, String task) {
        com.aresstack.askai.research.runtime.loop.SolonToolInvoker browser =
                new com.aresstack.askai.research.runtime.loop.SolonToolInvoker(
                        environment.browserUrl, environment.browserTransport);
        com.aresstack.askai.research.runtime.loop.SolonToolInvoker research =
                new com.aresstack.askai.research.runtime.loop.SolonToolInvoker(
                        environment.researchUrl, environment.researchTransport);
        try {
            com.aresstack.askai.research.runtime.loop.ResearchLoop loop =
                    new com.aresstack.askai.research.runtime.loop.ResearchLoop(browser, research,
                            com.aresstack.askai.research.runtime.loop.ResearchRunBudget.defaults(),
                            new com.aresstack.askai.research.runtime.loop.ResearchLoopClock() {
                                public long currentTimeMillis() {
                                    return System.currentTimeMillis();
                                }
                            },
                            new com.aresstack.askai.research.runtime.loop.ResearchLoopListener() {
                                public void status(String message) {
                                    ctx.sendMessage("loop: " + message);
                                }

                                public void phaseReady(
                                        com.aresstack.askai.research.runtime.loop.ResearchStopReason reason) {
                                    ctx.sendMessage("PHASE_READY: " + reason); // event only — host decides
                                }
                            }, cancelled);
            com.aresstack.askai.research.runtime.loop.ResearchStopReason reason = loop.run(task);
            ctx.sendMessage("RESEARCH_RUN_STOPPED: " + reason
                    + " pages=" + loop.getProgress().getPagesVisited()
                    + " sources=" + loop.getProgress().getAcceptedSources()
                    + " hosts=" + loop.getProgress().getDistinctHosts().size());
        } finally {
            browser.close();
            research.close();
        }
    }
}
