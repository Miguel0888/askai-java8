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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Session-scoped research-control endpoint: state-gated visibility, live re-check, tokens, generations. */
public class ResearchControlEndpointTest {

    /** Mutable fake context: tests flip phase/state to simulate transitions between list and call. */
    private static final class Ctx implements ResearchControlContext {
        String phaseId = ResearchStateIds.SCOPING;
        String stateId = ResearchStateIds.RUNNING;
        final AgentArtifactStore store = new ResearchArtifactStore();
        final InMemoryResearchSourceRepository sources = new InMemoryResearchSourceRepository();

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
            return store;
        }

        public ResearchSourceRepository sourceRepository() {
            return sources;
        }

        public String acceptCapture(String captureId) {
            if (!"cap1".equals(captureId)) {
                return null;
            }
            try {
                // Simulate promotion: a new source record appears in the shared repository.
                return "src-accepted";
            } finally {
                // (InMemory repo has fixed seeds; the id return is what the tool reports.)
            }
        }
    }

    private static McpToolResult call(InProcessMcpServerRegistry reg, ResearchControlEndpoint ep,
                                      String tool, String... kv) {
        Map<String, Object> args = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            args.put(kv[i], kv[i + 1]);
        }
        return reg.invoke(ep.getEndpointId(), ep.getHandle().getToken(), new McpToolCall(tool, args));
    }

    private static List<String> tools(InProcessMcpServerRegistry reg, ResearchControlEndpoint ep) {
        return reg.listToolNames(ep.getEndpointId(), ep.getHandle().getToken());
    }

    @Test
    public void visibilityFollowsPhaseAndRunState() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        Ctx ctx = new Ctx();
        ResearchControlEndpoint ep = new ResearchControlEndpoint(reg, "s1", 1L, ctx);
        ep.open();

        // SCOPING/running: always-tools + concept_save, no outline/draft tools.
        List<String> t = tools(reg, ep);
        assertTrue(t.containsAll(java.util.Arrays.asList("research_status", "artifact_read", "source_list")));
        assertTrue(t.contains("concept_save"));
        assertFalse(t.contains("outline_save"));
        assertFalse(t.contains("draft_save"));

        // OUTLINE/running → outline_save only.
        ctx.phaseId = ResearchStateIds.OUTLINE;
        ep.refreshTools();
        t = tools(reg, ep);
        assertTrue(t.contains("outline_save"));
        assertFalse(t.contains("concept_save"));

        // RESEARCH/running → research write tools.
        ctx.phaseId = ResearchStateIds.RESEARCH;
        ep.refreshTools();
        t = tools(reg, ep);
        assertTrue(t.containsAll(java.util.Arrays.asList("source_accept", "finding_add", "notes_append")));

        // Any non-running run state removes ALL write tools (approval gate, paused, blocked, failed, terminal).
        for (String s : new String[]{ResearchStateIds.WAITING_APPROVAL, ResearchStateIds.PAUSED,
                ResearchStateIds.BLOCKED, ResearchStateIds.FAILED, ResearchStateIds.CANCELLED}) {
            ctx.stateId = s;
            ep.refreshTools();
            t = tools(reg, ep);
            assertEquals("only the 3 read tools in " + s, 3, t.size());
        }
        ep.close();
    }

    @Test
    public void executionRechecksLiveStateEvenWhenToolWasListed() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        Ctx ctx = new Ctx();
        ctx.phaseId = ResearchStateIds.RESEARCH;
        ResearchControlEndpoint ep = new ResearchControlEndpoint(reg, "s1", 1L, ctx);
        ep.open();
        assertTrue(tools(reg, ep).contains("notes_append"));

        // Transition happens AFTER tools/list but BEFORE tools/call: the handler must reject server-side.
        ctx.stateId = ResearchStateIds.WAITING_APPROVAL;
        McpToolResult denied = call(reg, ep, "notes_append", "markdown", "late write");
        assertTrue(denied.isError());
        assertTrue(denied.getText().contains("Not allowed in the current state"));
        ep.close();
    }

    @Test
    public void writesWorkWithOptimisticLockingAndAppends() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        Ctx ctx = new Ctx();
        ctx.phaseId = ResearchStateIds.OUTLINE;
        ResearchControlEndpoint ep = new ResearchControlEndpoint(reg, "s1", 1L, ctx);
        ep.open();

        // Save with the correct revision succeeds; a stale revision is a conflict, not an overwrite.
        long rev = ctx.store.read("outline").getRevision();
        assertFalse(call(reg, ep, "outline_save", "markdown", "# New", "expected_revision",
                String.valueOf(rev)).isError());
        McpToolResult stale = call(reg, ep, "outline_save", "markdown", "# Old copy",
                "expected_revision", String.valueOf(rev));
        assertTrue(stale.isError());
        assertEquals("# New", ctx.store.read("outline").getMarkdown());

        // RESEARCH/running: finding_add validates the source id; notes_append appends.
        ctx.phaseId = ResearchStateIds.RESEARCH;
        ep.refreshTools();
        assertTrue(call(reg, ep, "finding_add", "source_id", "nope", "text", "x").isError());
        assertFalse(call(reg, ep, "finding_add", "source_id", "src1", "text", "PF4J is Java 8").isError());
        assertTrue(ctx.store.read("findings").getMarkdown().contains("[src1] PF4J is Java 8"));
        assertFalse(call(reg, ep, "notes_append", "markdown", "- note").isError());
        assertTrue(ctx.store.read("research-notes").getMarkdown().endsWith("- note"));

        // source_accept: unknown capture rejected; known capture reports the new source id.
        assertTrue(call(reg, ep, "source_accept", "capture_id", "nope").isError());
        McpToolResult accepted = call(reg, ep, "source_accept", "capture_id", "cap1");
        assertFalse(accepted.isError());
        assertTrue(accepted.getText().contains("src-accepted"));
        ep.close();
    }

    @Test
    public void endpointIdentityBindsSessionAndGenerationAndTokens() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        Ctx ctx = new Ctx();
        ResearchControlEndpoint g1 = new ResearchControlEndpoint(reg, "s1", 1L, ctx);
        ResearchControlEndpoint g2 = new ResearchControlEndpoint(reg, "s1", 2L, ctx);
        ResearchControlEndpoint other = new ResearchControlEndpoint(reg, "s2", 1L, ctx);
        g1.open();
        g2.open();
        other.open();

        assertNotEquals(g1.getEndpointId(), g2.getEndpointId()); // generation in the identity
        assertNotEquals(g1.getEndpointId(), other.getEndpointId()); // session in the identity
        assertNotEquals(g1.getHandle().getToken(), g2.getHandle().getToken());

        // Wrong token → rejected; g1's token cannot call g2's endpoint.
        assertTrue(reg.invoke(g2.getEndpointId(), g1.getHandle().getToken(),
                new McpToolCall("research_status", null)).isError());

        // Retiring the old generation invalidates exactly it; the new one keeps working.
        String g1Id = g1.getEndpointId();
        String g1Token = g1.getHandle().getToken();
        g1.close();
        g1.close(); // idempotent
        assertTrue(reg.invoke(g1Id, g1Token, new McpToolCall("research_status", null)).isError());
        assertFalse(call(reg, g2, "research_status").isError());
        g2.close();
        other.close();
    }
}
