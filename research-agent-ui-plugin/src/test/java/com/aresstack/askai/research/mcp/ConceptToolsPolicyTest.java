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
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The SMALL-MODEL facade over the concept (K2c): three tiny tools addressed by name paths —
 * no handles, no revisions to echo, examples in the descriptions, required arguments validated
 * before dispatch, and the same server-side phase re-check as every write tool. All change
 * semantics stay in ConceptBranchService.
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

    // ------------------------------------------------------------------ visibility & contract

    @Test
    public void writingIsOfferedOnlyInScopingRunningButReadingEverywhere() {
        assertTrue(tool("concept_read") != null);
        assertTrue(tool("concept_add") != null);
        assertTrue(tool("concept_remove") != null);
        phaseId = ResearchStateIds.RESEARCH; // later phase: the concept is frozen but readable
        assertTrue(tool("concept_read") != null);
        assertNull(tool("concept_add"));
        assertNull(tool("concept_remove"));
    }

    @Test
    public void withoutAServiceNoConceptToolExistsAtAll() {
        service = null;
        assertNull(tool("concept_read"));
        assertNull(tool("concept_add"));
        assertNull(tool("concept_remove"));
    }

    @Test
    public void theDescriptionsCarryConcreteExamplesTheMainframeMateWay() {
        assertTrue(tool("concept_add").getDescription().contains("Task Notifications"));
        assertTrue(tool("concept_remove").getDescription().contains("ESP-IDF"));
        assertTrue(tool("concept_read").getDescription().contains("FreeRTOS"));
    }

    // ------------------------------------------------------------------ the atomic flow

    @Test
    public void addReadRemoveWorkByNamePathsWithoutAnyCeremony() {
        McpToolResult first = invoke(tool("concept_add"), "name", "FreeRTOS");
        assertFalse(first.isError());
        assertEquals("added \"FreeRTOS\" revision=1", first.getText());
        assertEquals(1, changeNotifications);

        McpToolResult sub = invoke(tool("concept_add"),
                "parent_path", "FreeRTOS", "name", "Kommunikation");
        assertFalse(sub.isError());
        McpToolResult subsub = invoke(tool("concept_add"),
                "parent_path", "FreeRTOS/Kommunikation", "name", "Task Notifications");
        assertFalse(subsub.isError());
        assertEquals("added \"Task Notifications\" revision=3", subsub.getText());

        McpToolResult read = invoke(tool("concept_read"), "path", "FreeRTOS/Kommunikation");
        assertFalse(read.isError());
        assertTrue(read.getText().contains("{\"Kommunikation\":[{\"Task Notifications\":[]}]}"));
        assertTrue("no handle line anywhere", !read.getText().contains("handle"));

        McpToolResult removed = invoke(tool("concept_remove"),
                "path", "FreeRTOS/Kommunikation/Task Notifications");
        assertFalse(removed.isError());
        assertEquals("removed \"FreeRTOS/Kommunikation/Task Notifications\" revision=4",
                removed.getText());
        assertEquals(4, changeNotifications);
    }

    @Test
    public void requiredArgumentsAreValidatedBeforeDispatchWithAnExample() {
        McpToolResult noName = invoke(tool("concept_add"), "parent_path", "FreeRTOS");
        assertTrue(noName.isError());
        assertTrue(noName.getText().contains("Missing argument: name"));
        assertTrue("the error teaches by example", noName.getText().contains("Synchronisation"));
        McpToolResult noPath = invoke(tool("concept_remove"));
        assertTrue(noPath.isError());
        assertTrue(noPath.getText().contains("Missing argument: path"));
        assertEquals(0, changeNotifications);
    }

    @Test
    public void duplicatesAndUnknownParentsComeBackAsTeachingDiagnostics() {
        invoke(tool("concept_add"), "name", "FreeRTOS");
        McpToolResult duplicate = invoke(tool("concept_add"), "name", "FreeRTOS");
        assertTrue(duplicate.isError());
        assertTrue(duplicate.getText().contains("already exists"));

        McpToolResult orphan = invoke(tool("concept_add"),
                "parent_path", "Gibtsnicht", "name", "X");
        assertTrue(orphan.isError());
        assertTrue(orphan.getText().startsWith("TARGET_NODE_NOT_FOUND"));
        assertEquals("failures never notify", 1, changeNotifications);
    }

    // ------------------------------------------------------------------ authorization re-check

    @Test
    public void aPhaseTransitionBetweenListAndCallIsCaughtAtExecutionTime() {
        McpToolContribution add = tool("concept_add"); // offered while SCOPING/running…
        phaseId = ResearchStateIds.RESEARCH; // …but the phase moved on before the call arrived
        McpToolResult result = invoke(add, "name", "FreeRTOS");
        assertTrue(result.isError());
        assertTrue(result.getText().contains("Not allowed in the current state"));
        assertEquals(0, changeNotifications);
    }
}
