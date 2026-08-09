package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.research.agent.ResearchArtifactStore;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The internal service endpoint hosts {@code manual_source_accept}, delegating to the same host-side acceptance
 * service the agent path uses, but on its OWN namespace: it is phase-independent AND structurally absent from
 * the agent-facing control endpoint's tool catalog.
 */
public class ResearchServiceEndpointTest {

    private static final class Ctx implements ResearchControlContext {
        String phaseId = ResearchStateIds.SCOPING;
        String stateId = ResearchStateIds.RUNNING;

        public String currentPhaseId() {
            return phaseId;
        }

        public String currentStateId() {
            return stateId;
        }

        public String statusLine() {
            return phaseId + "/" + stateId;
        }

        public AgentArtifactStore artifactStore() {
            return new ResearchArtifactStore();
        }

        public ResearchSourceRepository sourceRepository() {
            return new InMemoryResearchSourceRepository();
        }

        public String acceptCapture(String captureId) {
            return "cap1".equals(captureId)
                    ? "status=ACCEPTED source_id=source-1 title=\"t\" passage_count=1 duplicate=false" : null;
        }
    }

    private static McpToolResult call(InProcessMcpServerRegistry reg, String endpointId, String token,
                                      String tool, String... kv) {
        Map<String, Object> args = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            args.put(kv[i], kv[i + 1]);
        }
        return reg.invoke(endpointId, token, new McpToolCall(tool, args));
    }

    @Test
    public void manualSourceAcceptIsCallableAndPhaseIndependent() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        Ctx ctx = new Ctx();
        ctx.phaseId = ResearchStateIds.SCOPING; // the phase the agent tool would be UNavailable in
        ResearchServiceEndpoint service = new ResearchServiceEndpoint(reg, "s1", 1L, ctx);
        service.open();

        McpToolResult unknown = call(reg, service.getEndpointId(), service.getHandle().getToken(),
                "manual_source_accept", "capture_id", "nope");
        assertTrue("an unknown capture is rejected", unknown.isError());

        McpToolResult accepted = call(reg, service.getEndpointId(), service.getHandle().getToken(),
                "manual_source_accept", "capture_id", "cap1");
        assertFalse("a known capture is accepted even in SCOPING", accepted.isError());
        assertTrue(accepted.getText().contains("source-1"));
        service.close();
    }

    @Test
    public void manualSourceAcceptIsNeverInTheAgentToolCatalog() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        Ctx ctx = new Ctx();
        for (String phase : new String[]{ResearchStateIds.SCOPING, ResearchStateIds.RESEARCH}) {
            ctx.phaseId = phase;
            ctx.stateId = ResearchStateIds.RUNNING;
            ResearchControlEndpoint agent = new ResearchControlEndpoint(reg, "s1", 1L, ctx);
            agent.open();
            List<String> agentTools = reg.listToolNames(agent.getEndpointId(), agent.getHandle().getToken());
            assertFalse("manual_source_accept must never be an agent tool in " + phase,
                    agentTools.contains("manual_source_accept"));
            assertFalse("manual_source_park must never be an agent tool in " + phase,
                    agentTools.contains("manual_source_park"));
            agent.close();
        }
    }

    @Test
    public void theServiceEndpointOnlyOffersItsInternalTools() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        ResearchServiceEndpoint service = new ResearchServiceEndpoint(reg, "s1", 1L, new Ctx());
        service.open();
        List<String> names = reg.listToolNames(service.getEndpointId(), service.getHandle().getToken());
        assertEquals("runtime plumbing ONLY — the bot tools live on their own endpoint",
                java.util.Arrays.asList("manual_source_accept", "manual_source_park"), names);
        service.close();
    }

    /** Records gateway calls; configurable result. */
    private static final class RecordingGateway implements ResearchBotControlEndpoint.SessionGateway {
        final List<String> invoked = new java.util.ArrayList<String>();
        String executeResult = "handled: ok";

        public String execute(String command, String arguments) {
            invoked.add("execute(" + command + "|" + arguments + ")");
            return executeResult;
        }

        public String describeState() {
            invoked.add("describeState");
            return "phase=scoping state=running revision=1 pendingApproval=- busy=false";
        }

        public String describeHistory(boolean raw) {
            invoked.add("describeHistory(" + raw + ")");
            return raw ? "raw history" : "summarized history";
        }
    }

    @Test
    public void theBotControlEndpointOffersExactlyTheThreeDrivingTools() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        ResearchBotControlEndpoint bot = new ResearchBotControlEndpoint(reg, "s1", 1L,
                new RecordingGateway());
        bot.open();
        assertEquals(java.util.Arrays.asList("run_command", "session_state", "chat_history"),
                reg.listToolNames(bot.getEndpointId(), bot.getHandle().getToken()));
        bot.close();
    }

    @Test
    public void theBotToolsDriveTheOneSessionGateway() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        RecordingGateway gateway = new RecordingGateway();
        ResearchBotControlEndpoint bot = new ResearchBotControlEndpoint(reg, "s1", 1L, gateway);
        bot.open();
        String id = bot.getEndpointId();
        String token = bot.getHandle().getToken();

        assertFalse(call(reg, id, token, "run_command", "command", "search",
                "arguments", "wearables").isError());
        assertFalse(call(reg, id, token, "run_command", "arguments", "just a chat message").isError());
        McpToolResult state = call(reg, id, token, "session_state");
        assertFalse(state.isError());
        assertTrue(state.getText().contains("phase=scoping state=running"));
        McpToolResult summary = call(reg, id, token, "chat_history");
        assertFalse(summary.isError());
        assertEquals("summarized history", summary.getText());
        McpToolResult raw = call(reg, id, token, "chat_history", "raw", "true");
        assertEquals("raw history", raw.getText());
        assertEquals(java.util.Arrays.asList("execute(search|wearables)",
                "execute(null|just a chat message)", "describeState",
                "describeHistory(false)", "describeHistory(true)"), gateway.invoked);

        // A rejected command surfaces as a typed MCP error with the reason.
        gateway.executeResult = "rejected: unknown command 'nope'. Valid now: search <query>";
        McpToolResult rejected = call(reg, id, token, "run_command", "command", "nope");
        assertTrue(rejected.isError());
        assertTrue(rejected.getText().contains("unknown command"));
        bot.close();

        // Without a gateway result (no session attached): an honest error, never a silent no-op.
        ResearchBotControlEndpoint bare = new ResearchBotControlEndpoint(reg, "s2", 1L,
                new ResearchBotControlEndpoint.SessionGateway() {
                    public String execute(String command, String arguments) {
                        return null;
                    }

                    public String describeState() {
                        return null;
                    }

                    public String describeHistory(boolean raw) {
                        return null;
                    }
                });
        bare.open();
        assertTrue(call(reg, bare.getEndpointId(), bare.getHandle().getToken(),
                "run_command", "command", "search").isError());
        assertTrue(call(reg, bare.getEndpointId(), bare.getHandle().getToken(),
                "session_state").isError());
        assertTrue(call(reg, bare.getEndpointId(), bare.getHandle().getToken(),
                "chat_history").isError());
        bare.close();
    }

    @Test
    public void theDrivingToolsAreNeverInTheAgentToolCatalog() {
        // Authority boundary: the TeamAgent gets NEITHER the driving tools NOR the state/history tools —
        // in no phase/state does the control endpoint offer them.
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        Ctx ctx = new Ctx();
        for (String phase : new String[]{ResearchStateIds.SCOPING, ResearchStateIds.OUTLINE,
                ResearchStateIds.RESEARCH, ResearchStateIds.EVIDENCE, ResearchStateIds.DRAFT,
                ResearchStateIds.FINALIZATION}) {
            ctx.phaseId = phase;
            ctx.stateId = ResearchStateIds.RUNNING;
            ResearchControlEndpoint agent = new ResearchControlEndpoint(reg, "s1", 1L, ctx);
            agent.open();
            List<String> agentTools = reg.listToolNames(agent.getEndpointId(),
                    agent.getHandle().getToken());
            for (String tool : new String[]{"run_command", "session_state", "chat_history"}) {
                assertFalse(tool + " must never be an agent tool in " + phase,
                        agentTools.contains(tool));
            }
            agent.close();
        }
    }

    @Test
    public void theDescriptorFileCarriesTheHeadlessConnectionData() {
        String json = ServiceEndpointDescriptorFile.toJson(
                "research-service.s1.g1", "http://127.0.0.1:4242/mcp", "streamable", "tok\"en\\x");
        assertTrue(json.contains("\"endpointId\": \"research-service.s1.g1\""));
        assertTrue(json.contains("\"url\": \"http://127.0.0.1:4242/mcp\""));
        assertTrue(json.contains("\"transport\": \"streamable\""));
        assertTrue("quotes/backslashes are escaped", json.contains("\"token\": \"tok\\\"en\\\\x\""));
    }
}
