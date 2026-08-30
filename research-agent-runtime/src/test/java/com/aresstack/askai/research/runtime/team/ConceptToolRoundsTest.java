package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The tool-round loop with the small-model contract: a read is a regular working step (work
 * budget), only a REJECTED mutation counts against the separate repair budget, and EVERY
 * feedback carries the authoritative ARTIFACT_STATE block — only an APPLIED call may claim a
 * change. Budgets exhausted → one wrap-up inference, further actions dropped; a dead endpoint
 * keeps the last good answer.
 */
public class ConceptToolRoundsTest {

    private static final class ScriptedTurns implements ConceptToolRounds.FollowUpTurn {
        final List<TeamAgentResult> script = new ArrayList<TeamAgentResult>();
        final List<String> feedbackSeen = new ArrayList<String>();

        public TeamAgentResult run(String feedbackInstruction) {
            feedbackSeen.add(feedbackInstruction);
            if (script.isEmpty()) {
                throw new AssertionError("more follow-up turns requested than scripted");
            }
            return script.remove(0);
        }
    }

    private static final class ScriptedTool implements ConceptToolRounds.ConceptTool {
        final Map<String, Object> byDescription = new HashMap<String, Object>();
        final List<String> calls = new ArrayList<String>();

        public String call(ConceptAction action)
                throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
            calls.add(action.describe());
            Object scripted = byDescription.get(action.describe());
            if (scripted instanceof ToolInvoker.ToolFailure) {
                throw (ToolInvoker.ToolFailure) scripted;
            }
            if (scripted instanceof ToolInvoker.EndpointUnavailable) {
                throw (ToolInvoker.EndpointUnavailable) scripted;
            }
            return String.valueOf(scripted);
        }
    }

    private final List<String> trace = new ArrayList<String>();
    private final ConceptToolRounds.Trace traceSink = new ConceptToolRounds.Trace() {
        public void line(String message) {
            trace.add(message);
        }
    };

    private static TeamAgentResult turn(String message, String conceptActionJson) {
        String json = "{\"assistantMessage\":\"" + message + "\""
                + (conceptActionJson == null ? "" : ",\"conceptAction\":" + conceptActionJson)
                + "}";
        ScopingAssistantOutputParser.Result parsed = ScopingAssistantOutputParser.parse(json);
        assertTrue("fixture must parse: " + parsed.getError(), parsed.isOk());
        return TeamAgentResult.ok(parsed.getOutput(), null);
    }

    @Test
    public void readThenAddThenFinishIsTheHappyPath() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path=''", "{\"concept\":[]}");
        tool.byDescription.put("add parent='' name='FreeRTOS'", "added \"FreeRTOS\" revision=1");
        turns.script.add(turn("lege an",
                "{\"type\":\"add\",\"parent_path\":\"\",\"name\":\"FreeRTOS\"}"));
        turns.script.add(turn("Angelegt.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("ich sehe nach", "{\"type\":\"read\",\"path\":\"\"}"),
                turns, tool, 4, 2, traceSink);

        assertEquals("Angelegt.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertEquals(2, tool.calls.size());
        String readFeedback = turns.feedbackSeen.get(0);
        assertTrue("EVERY feedback leads with the authoritative state block",
                readFeedback.startsWith("ARTIFACT_STATE"));
        assertTrue(readFeedback.contains("updateAppliedThisTurn: false"));
        assertTrue(readFeedback.contains("CONCEPT TOOL RESULT"));
        String addFeedback = turns.feedbackSeen.get(1);
        assertTrue(addFeedback.contains("updateAppliedThisTurn: true"));
        assertTrue(addFeedback.contains("conceptRevision: 1"));
        assertTrue(addFeedback.contains("CONCEPT TOOL APPLIED"));
    }

    @Test
    public void aRejectedAddFeedsTheDiagnosticBackAndCountsAsRepair() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add parent='' name='Tasks'", new ToolInvoker.ToolFailure(
                "BRANCH_GRAFT_FAILED\nA card named \"Tasks\" already exists here."));
        tool.byDescription.put("add parent='Tasks' name='Scheduling'",
                "added \"Scheduling\" revision=2");
        turns.script.add(turn("anders",
                "{\"type\":\"add\",\"parent_path\":\"Tasks\",\"name\":\"Scheduling\"}"));
        turns.script.add(turn("Erledigt.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("try", "{\"type\":\"add\",\"parent_path\":\"\",\"name\":\"Tasks\"}"),
                turns, tool, 4, 2, traceSink);

        assertEquals("Erledigt.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        String rejection = turns.feedbackSeen.get(0);
        assertTrue(rejection.contains("CONCEPT TOOL REJECTED"));
        assertTrue("the diagnostic travels verbatim", rejection.contains("already exists"));
        assertTrue("the state block names the error",
                rejection.contains("lastConceptError: BRANCH_GRAFT_FAILED"));
        assertTrue(rejection.contains("updateAppliedThisTurn: false"));
    }

    @Test
    public void anInvalidActionNeverReachesTheToolButComesBackAsRejection() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        turns.script.add(turn("ok", null));
        TeamAgentResult result = ConceptToolRounds.run(
                turn("try", "{\"type\":\"add\",\"parent_path\":\"X\"}"), // name missing
                turns, tool, 4, 2, traceSink);
        assertTrue(tool.calls.isEmpty());
        assertTrue(turns.feedbackSeen.get(0).contains("CONCEPT TOOL REJECTED"));
        assertTrue(turns.feedbackSeen.get(0).contains("requires \"name\""));
        assertEquals("ok", ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
    }

    @Test
    public void theWorkBudgetEndsWithOneWrapUpTurnAndDropsFurtherActions() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path='A'", "branch A");
        tool.byDescription.put("read path='B'", "branch B");
        turns.script.add(turn("next", "{\"type\":\"read\",\"path\":\"B\"}"));
        turns.script.add(turn("wrap-up trotz Verbot", "{\"type\":\"read\",\"path\":\"C\"}"));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("start", "{\"type\":\"read\",\"path\":\"A\"}"),
                turns, tool, 2, 2, traceSink);

        assertEquals(2, tool.calls.size());
        assertTrue(turns.feedbackSeen.get(1).contains("TOOL BUDGET EXHAUSTED"));
        assertEquals("wrap-up trotz Verbot",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue(trace.get(trace.size() - 1).contains("dropping"));
    }

    @Test
    public void repairBudgetIsSeparateFromTheWorkBudget() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add parent='' name='X'",
                new ToolInvoker.ToolFailure("BRANCH_GRAFT_FAILED\nboom"));
        turns.script.add(turn("retry", "{\"type\":\"add\",\"parent_path\":\"\",\"name\":\"X\"}"));
        turns.script.add(turn("aufgeben", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("try", "{\"type\":\"add\",\"parent_path\":\"\",\"name\":\"X\"}"),
                turns, tool, 10, 1, traceSink);

        assertEquals("aufgeben",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue(turns.feedbackSeen.get(1).contains("TOOL BUDGET EXHAUSTED"));
    }

    @Test
    public void aDeadEndpointKeepsTheLastGoodAnswer() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path='A'",
                new ToolInvoker.EndpointUnavailable("Connection refused"));
        TeamAgentResult initial = turn("ich schaue nach", "{\"type\":\"read\",\"path\":\"A\"}");

        TeamAgentResult result = ConceptToolRounds.run(initial, turns, tool, 4, 2, traceSink);

        assertEquals(initial, result);
        assertTrue(turns.feedbackSeen.isEmpty());
        assertTrue(trace.get(trace.size() - 1).contains("unavailable"));
    }

    @Test
    public void aTurnWithoutAnActionPassesThroughUntouched() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        TeamAgentResult initial = turn("nur eine Antwort", null);
        assertEquals(initial, ConceptToolRounds.run(initial, turns, new ScriptedTool(),
                4, 2, traceSink));
        assertTrue(turns.feedbackSeen.isEmpty());
    }
}
