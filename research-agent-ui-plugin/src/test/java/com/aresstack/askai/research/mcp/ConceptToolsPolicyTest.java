package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.research.concept.ConceptBranchService;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.store.FileConceptStore;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Konzeptpapier MCP tools: reading everywhere, bite-wise WRITING only in SCOPING/running —
 * with the server-side re-check at execution time (visibility ≠ authorization), the structured
 * diagnostics as the error payload (the model's repair input), and the change notification on
 * every commit. All change semantics live in ConceptBranchService; the tools only translate.
 */
public class ConceptToolsPolicyTest {

    private ConceptBranchService service;
    private String phaseId = ResearchStateIds.SCOPING;
    private String stateId = ResearchStateIds.RUNNING;
    private int changeNotifications;

    private final ResearchControlContext ctx = new ResearchControlContext() {
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
            return null;
        }

        public ResearchSourceRepository sourceRepository() {
            return InMemoryResearchSourceRepository.empty();
        }

        public String acceptCapture(String captureId) {
            return null;
        }

        @Override
        public ConceptBranchService conceptBranchService() {
            return service;
        }

        @Override
        public void onConceptChanged(long newWorkingRevision) {
            changeNotifications++;
        }
    };

    @Before
    public void freshService() throws Exception {
        File dir = Files.createTempDirectory("askai-concept-tools").toFile();
        service = new ConceptBranchService(new FileConceptStore(new File(dir, "concept")));
        changeNotifications = 0;
    }

    private McpToolContribution tool(String name) {
        for (McpToolContribution tool : ResearchToolPolicy.toolsFor(phaseId, stateId, ctx)) {
            if (name.equals(tool.getName())) {
                return tool;
            }
        }
        return null;
    }

    private static McpToolResult invoke(McpToolContribution tool, String... keyValues) {
        Map<String, Object> args = new HashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            args.put(keyValues[i], keyValues[i + 1]);
        }
        return tool.getHandler().invoke(new McpToolCall(tool.getName(), args));
    }

    // ------------------------------------------------------------------ visibility

    @Test
    public void writingIsOfferedOnlyInScopingRunningButReadingEverywhere() {
        assertTrue(tool("concept_read") != null);
        assertTrue(tool("concept_update") != null);
        assertTrue(tool("concept_remove") != null);
        phaseId = ResearchStateIds.RESEARCH; // later phase: the concept is frozen but readable
        assertTrue(tool("concept_read") != null);
        assertNull(tool("concept_update"));
        assertNull(tool("concept_remove"));
    }

    @Test
    public void withoutAServiceNoConceptToolExistsAtAll() {
        service = null;
        assertNull(tool("concept_read"));
        assertNull(tool("concept_update"));
        assertNull(tool("concept_remove"));
    }

    // ------------------------------------------------------------------ the bite-wise flow

    @Test
    public void theFullReadEditCommitFlowWorksThroughTheTools() {
        McpToolResult read = invoke(tool("concept_read"));
        assertFalse(read.isError());
        assertTrue(read.getText().startsWith("handle=b-"));
        assertTrue(read.getText().contains("editable=true"));
        assertTrue(read.getText().contains("{\"concept\":[]}"));
        String handle = read.getText().substring("handle=".length(),
                read.getText().indexOf(' '));

        McpToolResult update = invoke(tool("concept_update"),
                "handle", handle,
                "branch_json", "{\"concept\":[{\"Tasks\":[],\"Queues\":[]}]}");
        assertFalse(update.isError());
        assertEquals("applied revision=1", update.getText());
        assertEquals("a committed edit notifies the host", 1, changeNotifications);

        McpToolResult branch = invoke(tool("concept_read"), "path", "Tasks");
        assertFalse(branch.isError());
        assertTrue("orientation travels with the branch",
                branch.getText().contains("siblings=Queues"));
        assertTrue(branch.getText().contains("{\"Tasks\":[]}"));
    }

    @Test
    public void aBrokenBranchComesBackAsTheStructuredDiagnosticNotAGsonError() {
        McpToolResult read = invoke(tool("concept_read"));
        String handle = read.getText().substring("handle=".length(), read.getText().indexOf(' '));
        McpToolResult update = invoke(tool("concept_update"),
                "handle", handle,
                "branch_json", "{\"concept\":[{\"Tasks\":[] \"Queues\":[]}]}");
        assertTrue(update.isError());
        assertTrue("the model receives the feedback block: " + update.getText(),
                update.getText().startsWith("JSON_SYNTAX_ERROR"));
        assertFalse("no raw Gson advice leaks", update.getText().contains("setLenient"));
        assertEquals("a rejected edit never notifies", 0, changeNotifications);
    }

    @Test
    public void silentNodeLossIsRejectedThroughTheToolToo() {
        McpToolResult seedRead = invoke(tool("concept_read"));
        String seedHandle = seedRead.getText().substring("handle=".length(),
                seedRead.getText().indexOf(' '));
        invoke(tool("concept_update"), "handle", seedHandle,
                "branch_json", "{\"concept\":[{\"Tasks\":[],\"Queues\":[]}]}");
        McpToolResult read = invoke(tool("concept_read"));
        String handle = read.getText().substring("handle=".length(), read.getText().indexOf(' '));
        McpToolResult update = invoke(tool("concept_update"), "handle", handle,
                "branch_json", "{\"concept\":[{\"Tasks\":[]}]}");
        assertTrue(update.isError());
        assertTrue(update.getText().startsWith("STRUCTURE_LOSS_DETECTED"));
        assertTrue("the lost node is named", update.getText().contains("Queues"));
    }

    // ------------------------------------------------------------------ authorization re-check

    @Test
    public void aPhaseTransitionBetweenListAndCallIsCaughtAtExecutionTime() {
        McpToolContribution update = tool("concept_update"); // offered while SCOPING/running…
        McpToolResult read = invoke(tool("concept_read"));
        String handle = read.getText().substring("handle=".length(), read.getText().indexOf(' '));
        phaseId = ResearchStateIds.RESEARCH; // …but the phase moved on before the call arrived
        McpToolResult result = invoke(update, "handle", handle,
                "branch_json", "{\"concept\":[{\"Tasks\":[]}]}");
        assertTrue(result.isError());
        assertTrue(result.getText().contains("Not allowed in the current state"));
        assertEquals(0, changeNotifications);
    }

    @Test
    public void removeIsExplicitAndNotifiesOnCommit() {
        McpToolResult seedRead = invoke(tool("concept_read"));
        String seedHandle = seedRead.getText().substring("handle=".length(),
                seedRead.getText().indexOf(' '));
        invoke(tool("concept_update"), "handle", seedHandle,
                "branch_json", "{\"concept\":[{\"Tasks\":[],\"Queues\":[]}]}");
        McpToolResult read = invoke(tool("concept_read"), "path", "Queues");
        String handle = read.getText().substring("handle=".length(), read.getText().indexOf(' '));
        McpToolResult removed = invoke(tool("concept_remove"), "handle", handle);
        assertFalse(removed.isError());
        assertEquals("removed revision=2", removed.getText());
        assertEquals(2, changeNotifications);
        McpToolResult after = invoke(tool("concept_read"));
        assertFalse(after.getText().contains("Queues"));
    }

    @Test
    public void aDepthLimitedReadSaysItIsNotEditable() {
        McpToolResult seedRead = invoke(tool("concept_read"));
        String seedHandle = seedRead.getText().substring("handle=".length(),
                seedRead.getText().indexOf(' '));
        invoke(tool("concept_update"), "handle", seedHandle,
                "branch_json", "{\"concept\":[{\"A\":[{\"B\":[{\"C\":[]}]}]}]}");
        McpToolResult shallow = invoke(tool("concept_read"), "path", "A", "depth", "1");
        assertTrue(shallow.getText().contains("editable=false"));
        assertTrue("grandchildren pruned", shallow.getText().contains("{\"A\":[{\"B\":[]}]}"));
    }
}
