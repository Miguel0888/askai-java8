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
 * The tool-round loop is NOT just a repair loop: a read is a regular working step (work budget),
 * only a REJECTED mutation counts against the separate repair budget. When either budget runs out
 * the model gets ONE wrap-up inference and any further action is dropped; a dead endpoint keeps
 * the last good answer. The loop itself is deterministic — all semantics live in the host.
 */
public class ConceptToolRoundsTest {

    /** A scripted follow-up turn: pops the next scripted output, records the feedback it saw. */
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

    /** A scripted tool: maps action descriptions to results/failures, records the calls. */
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
    public void readThenUpdateThenFinishIsTheHappyPath() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path='Synchronisation'",
                "handle=b-1 revision=3 editable=true\n{\"Synchronisation\":[]}");
        tool.byDescription.put("update handle=b-1", "applied revision=4");
        turns.script.add(turn("working",
                "{\"type\":\"update\",\"handle\":\"b-1\",\"branchJson\":"
                        + "\"{\\\"Synchronisation\\\":[{\\\"Mutex\\\":[]}]}\"}"));
        turns.script.add(turn("Fertig umgruppiert.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("ich sehe nach", "{\"type\":\"read\",\"path\":\"Synchronisation\"}"),
                turns, tool, 4, 2, traceSink);

        assertEquals("Fertig umgruppiert.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertEquals(2, tool.calls.size());
        assertTrue("the read feedback is a RESULT, not a repair",
                turns.feedbackSeen.get(0).startsWith("CONCEPT TOOL RESULT"));
        assertTrue("the commit feedback says APPLIED",
                turns.feedbackSeen.get(1).startsWith("CONCEPT TOOL APPLIED"));
    }

    @Test
    public void aRejectedUpdateFeedsTheDiagnosticBackAndCountsAsRepair() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("update handle=b-1", new ToolInvoker.ToolFailure(
                "STRUCTURE_LOSS_DETECTED\nPath: $.concept.Sync\nThe proposed refinement silently "
                        + "removes existing concept nodes: \"Queues\"."));
        tool.byDescription.put("update handle=b-2", "applied revision=4");
        turns.script.add(turn("second try",
                "{\"type\":\"update\",\"handle\":\"b-2\",\"branchJson\":\"{\\\"S\\\":[]}\"}"));
        turns.script.add(turn("Erledigt.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("try", "{\"type\":\"update\",\"handle\":\"b-1\",\"branchJson\":\"{\\\"S\\\":[]}\"}"),
                turns, tool, 4, 2, traceSink);

        assertEquals("Erledigt.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue(turns.feedbackSeen.get(0).startsWith("CONCEPT TOOL REJECTED"));
        assertTrue("the diagnostic travels verbatim",
                turns.feedbackSeen.get(0).contains("STRUCTURE_LOSS_DETECTED"));
    }

    @Test
    public void anInvalidActionNeverReachesTheToolButComesBackAsRejection() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        turns.script.add(turn("ok", null));
        // type update without handle → parser carries the error instead of the action
        TeamAgentResult result = ConceptToolRounds.run(
                turn("try", "{\"type\":\"update\",\"branchJson\":\"{\\\"S\\\":[]}\"}"),
                turns, tool, 4, 2, traceSink);
        assertTrue(tool.calls.isEmpty());
        assertTrue(turns.feedbackSeen.get(0).startsWith("CONCEPT TOOL REJECTED"));
        assertTrue(turns.feedbackSeen.get(0).contains("requires \"handle\""));
        assertEquals("ok", ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
    }

    @Test
    public void theWorkBudgetEndsWithOneWrapUpTurnAndDropsFurtherActions() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path='A'", "branch A");
        tool.byDescription.put("read path='B'", "branch B");
        // maxToolRounds=2: round 1 read A, round 2 read B → budget note; the wrap-up STILL tries
        // an action, which must be dropped, keeping the wrap-up answer.
        turns.script.add(turn("next", "{\"type\":\"read\",\"path\":\"B\"}"));
        turns.script.add(turn("wrap-up trotz Verbot", "{\"type\":\"read\",\"path\":\"C\"}"));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("start", "{\"type\":\"read\",\"path\":\"A\"}"),
                turns, tool, 2, 2, traceSink);

        assertEquals(2, tool.calls.size());
        assertTrue("the last feedback carries the budget note",
                turns.feedbackSeen.get(1).contains("TOOL BUDGET EXHAUSTED"));
        assertEquals("wrap-up trotz Verbot",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue("the dropped action leaves a trace",
                trace.get(trace.size() - 1).contains("dropping"));
    }

    @Test
    public void repairBudgetIsSeparateFromTheWorkBudget() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("update handle=b-1",
                new ToolInvoker.ToolFailure("JSON_SYNTAX_ERROR\nLine 1"));
        // maxRepairAttempts=1: the FIRST rejection is within budget, the SECOND exhausts it.
        turns.script.add(turn("retry",
                "{\"type\":\"update\",\"handle\":\"b-1\",\"branchJson\":\"{\\\"S\\\":[]}\"}"));
        turns.script.add(turn("aufgeben", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("try", "{\"type\":\"update\",\"handle\":\"b-1\",\"branchJson\":\"{\\\"S\\\":[]}\"}"),
                turns, tool, 10, 1, traceSink);

        assertEquals("aufgeben",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue("the second rejection carries the budget note",
                turns.feedbackSeen.get(1).contains("TOOL BUDGET EXHAUSTED"));
    }

    @Test
    public void aDeadEndpointKeepsTheLastGoodAnswer() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path='A'",
                new ToolInvoker.EndpointUnavailable("Connection refused"));
        TeamAgentResult initial = turn("ich schaue nach", "{\"type\":\"read\",\"path\":\"A\"}");

        TeamAgentResult result = ConceptToolRounds.run(initial, turns, tool, 4, 2, traceSink);

        assertEquals("the last good result survives, no follow-up turn", initial, result);
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
