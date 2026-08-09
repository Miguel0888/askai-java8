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
        assertEquals(java.util.Arrays.asList("manual_source_accept", "manual_source_park",
                "review_sources", "visualization_generate", "outline_generate"), names);
        service.close();
    }

    /** Records which derived-action command was invoked; configurable outcome. */
    private static final class RecordingActions
            implements com.aresstack.askai.research.agent.ResearchDerivedActions {
        final List<String> invoked = new java.util.ArrayList<String>();
        boolean accept = true;

        public ActionOutcome reviewSources() {
            invoked.add("reviewSources");
            return outcome();
        }

        public ActionOutcome generateVisualization() {
            invoked.add("generateVisualization");
            return outcome();
        }

        public ActionOutcome generateOutline() {
            invoked.add("generateOutline");
            return outcome();
        }

        private ActionOutcome outcome() {
            return accept ? ActionOutcome.accepted("started") : ActionOutcome.rejected("unavailable");
        }
    }

    @Test
    public void theDerivedActionToolsInvokeTheOneSessionCommandImplementation() {
        // Issue #33: the MCP tools and the UI buttons are two adapters over the SAME commands — the
        // endpoint resolves the session's implementation at call time and reports its typed outcome.
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        final RecordingActions actions = new RecordingActions();
        final com.aresstack.askai.research.agent.ResearchDerivedActions[] attached = {null};
        ResearchServiceEndpoint service = new ResearchServiceEndpoint(reg, "s1", 1L, new Ctx(),
                new ResearchServiceEndpoint.DerivedActionsSource() {
                    public com.aresstack.askai.research.agent.ResearchDerivedActions derivedActions() {
                        return attached[0];
                    }
                });
        service.open();
        String id = service.getEndpointId();
        String token = service.getHandle().getToken();

        // Before the session attached: an honest error, never a silent no-op.
        McpToolResult early = call(reg, id, token, "outline_generate");
        assertTrue(early.isError());
        assertTrue(early.getText().contains("No research session"));

        attached[0] = actions;
        assertFalse(call(reg, id, token, "review_sources").isError());
        assertFalse(call(reg, id, token, "visualization_generate").isError());
        assertFalse(call(reg, id, token, "outline_generate").isError());
        assertEquals(java.util.Arrays.asList("reviewSources", "generateVisualization", "generateOutline"),
                actions.invoked);

        // A rejected command surfaces as a typed MCP error with the reason.
        actions.accept = false;
        McpToolResult rejected = call(reg, id, token, "outline_generate");
        assertTrue(rejected.isError());
        assertTrue(rejected.getText().contains("unavailable"));
        service.close();
    }

    @Test
    public void theDerivedActionToolsAreNeverInTheAgentToolCatalog() {
        // Issue #33 authority boundary: the TeamAgent must not re-acquire the implicit orchestration that
        // #29 removed — in NO phase/state does the control endpoint offer the derived-action tools.
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
            for (String tool : new String[]{"review_sources", "visualization_generate",
                    "outline_generate"}) {
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
