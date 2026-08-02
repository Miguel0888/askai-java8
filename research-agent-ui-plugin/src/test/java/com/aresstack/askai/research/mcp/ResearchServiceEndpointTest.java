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
            agent.close();
        }
    }

    @Test
    public void theServiceEndpointOnlyOffersItsInternalTool() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        ResearchServiceEndpoint service = new ResearchServiceEndpoint(reg, "s1", 1L, new Ctx());
        service.open();
        List<String> names = reg.listToolNames(service.getEndpointId(), service.getHandle().getToken());
        assertEquals(Collections.singletonList("manual_source_accept"), names);
        service.close();
    }
}
