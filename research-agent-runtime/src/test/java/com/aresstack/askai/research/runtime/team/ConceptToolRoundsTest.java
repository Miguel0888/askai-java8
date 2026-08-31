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
 * The tool-round loop with change RECEIPTS: every feedback lists APPLIED_ACTIONS and
 * REJECTED_ACTIONS (a lone boolean once let one applied add be claimed as four) and grounds
 * them in the persisted CURRENT_CONCEPT after any mutation attempt. A read is a working step,
 * only rejections count against the separate repair budget, outcomes land in the trace
 * (APPLIED revision / REJECTED code), budgets end in one wrap-up, a dead endpoint keeps the
 * last good answer.
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

        ScriptedTool() {
            // The loop grounds receipts by re-reading the whole concept after mutations.
            byDescription.put("read path=[]", "{\"concept\":[]}");
        }

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
    public void readThenAddThenFinishCarriesReceiptsAndGrounding() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path=[\"X\"]", "{\"X\":[]}");
        tool.byDescription.put("add parent=[] name=\"FreeRTOS\"",
                "added \"FreeRTOS\" revision=1");
        tool.byDescription.put("read path=[]",
                "{\"concept\":[{\"FreeRTOS\":[]}]}");
        turns.script.add(turn("lege an",
                "{\"type\":\"add\",\"parent\":[],\"name\":\"FreeRTOS\"}"));
        turns.script.add(turn("Angelegt.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("ich sehe nach", "{\"type\":\"read\",\"path\":[\"X\"]}"),
                turns, tool, 4, 2, false, null, traceSink);

        assertEquals("Angelegt.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        String readFeedback = turns.feedbackSeen.get(0);
        assertTrue(readFeedback.startsWith("ARTIFACT_STATE"));
        assertTrue("nothing applied yet", readFeedback.contains("APPLIED_ACTIONS\n- (none)"));
        String addFeedback = turns.feedbackSeen.get(1);
        assertTrue("the receipt names the ONE applied action",
                addFeedback.contains("APPLIED_ACTIONS\n- add parent=[] name=\"FreeRTOS\" "
                        + "(revision 1)"));
        assertTrue(addFeedback.contains("REJECTED_ACTIONS\n- (none)"));
        assertTrue("the receipts are grounded in the persisted concept",
                addFeedback.contains("CURRENT_CONCEPT\n{\"concept\":[{\"FreeRTOS\":[]}]}"));
        assertTrue(addFeedback.contains("Only claim changes listed under APPLIED_ACTIONS"));
        assertTrue("the trace shows the OUTCOME, not just the attempt",
                trace.contains("round 2 -> APPLIED revision=1"));
    }

    /** The live exclusion bug: intermediate rounds must hand their scopePatch to the sink. */
    @Test
    public void anIntermediateRoundsScopePatchReachesTheSinkInsteadOfVanishing() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add parent=[] name=\"Arduino\"", "added \"Arduino\" revision=1");
        turns.script.add(turn("fertig", null));
        final List<ScopingAssistantOutput> intermediates = new ArrayList<ScopingAssistantOutput>();

        // ONE answer, BOTH channels: conceptAction (add Arduino) AND scopePatch (exclude ESP-IDF).
        TeamAgentResult initial = turn("nur Arduino",
                "{\"type\":\"add\",\"parent\":[],\"name\":\"Arduino\"}");
        ScopingAssistantOutputParser.Result withPatch = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"nur Arduino\","
                        + "\"scopePatch\":{\"operations\":[{\"kind\":\"addExclusion\","
                        + "\"value\":\"ESP-IDF\"}]},"
                        + "\"conceptAction\":{\"type\":\"add\",\"parent\":[],"
                        + "\"name\":\"Arduino\"}}");
        assertTrue(withPatch.isOk());
        initial = TeamAgentResult.ok(withPatch.getOutput(), null);

        ConceptToolRounds.run(initial, turns, tool, 4, 2, false,
                new ConceptToolRounds.IntermediateSink() {
                    public void intermediate(ScopingAssistantOutput output) {
                        intermediates.add(output);
                    }
                }, traceSink);

        assertEquals("the consumed round reached the sink", 1, intermediates.size());
        assertTrue("its scope update survives for emission",
                intermediates.get(0).getScopeUpdate() != null
                        && intermediates.get(0).getScopeUpdate().isValid());
    }

    @Test
    public void aRejectedAddIsAReceiptARepairAndATraceOutcome() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add parent=[\"FreeRTOS\",\"ESP32\"] name=\"Grundlagen\"",
                new ToolInvoker.ToolFailure(
                        "TARGET_NODE_NOT_FOUND\nConcept node \"ESP32\" does not exist."));
        turns.script.add(turn("verstanden", null));

        ConceptToolRounds.run(
                turn("try", "{\"type\":\"add\",\"parent\":[\"FreeRTOS\",\"ESP32\"],"
                        + "\"name\":\"Grundlagen\"}"),
                turns, tool, 4, 2, false, null, traceSink);

        String feedback = turns.feedbackSeen.get(0);
        assertTrue(feedback.contains("REJECTED_ACTIONS\n- add parent=[\"FreeRTOS\",\"ESP32\"] "
                + "name=\"Grundlagen\" — TARGET_NODE_NOT_FOUND"));
        assertTrue(feedback.contains("APPLIED_ACTIONS\n- (none)"));
        assertTrue("even a rejection grounds the receipts in the persisted (unchanged) state",
                feedback.contains("CURRENT_CONCEPT"));
        assertTrue(trace.contains("round 1 -> REJECTED TARGET_NODE_NOT_FOUND"));
    }

    @Test
    public void rejectionsExhaustTheRepairBudgetSeparately() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add parent=[] name=\"X\"",
                new ToolInvoker.ToolFailure("BRANCH_GRAFT_FAILED\nboom"));
        turns.script.add(turn("retry", "{\"type\":\"add\",\"parent\":[],\"name\":\"X\"}"));
        turns.script.add(turn("aufgeben", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("try", "{\"type\":\"add\",\"parent\":[],\"name\":\"X\"}"),
                turns, tool, 10, 1, false, null, traceSink);

        assertEquals("aufgeben",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue("the second rejection exhausts the repair budget (work budget untouched)",
                turns.feedbackSeen.get(1).contains("TOOL BUDGET EXHAUSTED"));
        assertTrue(turns.feedbackSeen.get(1).contains(
                "REJECTED_ACTIONS\n- add parent=[] name=\"X\" — BRANCH_GRAFT_FAILED\n"
                        + "- add parent=[] name=\"X\" — BRANCH_GRAFT_FAILED"));
    }

    @Test
    public void anInvalidActionNeverReachesTheToolButBecomesAReceipt() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        turns.script.add(turn("ok", null));
        ConceptToolRounds.run(
                turn("try", "{\"type\":\"add\",\"parent\":[\"X\"]}"), // name missing
                turns, tool, 4, 2, false, null, traceSink);
        assertTrue(tool.calls.isEmpty());
        assertTrue(turns.feedbackSeen.get(0).contains("REJECTED_ACTIONS\n- (invalid)"));
    }

    @Test
    public void theWorkBudgetEndsWithOneWrapUpTurnAndDropsFurtherActions() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path=[\"A\"]", "branch A");
        tool.byDescription.put("read path=[\"B\"]", "branch B");
        turns.script.add(turn("next", "{\"type\":\"read\",\"path\":[\"B\"]}"));
        turns.script.add(turn("wrap-up trotz Verbot", "{\"type\":\"read\",\"path\":[\"C\"]}"));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("start", "{\"type\":\"read\",\"path\":[\"A\"]}"),
                turns, tool, 2, 2, false, null, traceSink);

        assertEquals(2, tool.calls.size()); // reads only — no mutation, no grounding re-read
        assertTrue(turns.feedbackSeen.get(1).contains("TOOL BUDGET EXHAUSTED"));
        assertEquals("wrap-up trotz Verbot",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue(trace.get(trace.size() - 1).contains("dropping"));
    }

    @Test
    public void aDeadEndpointKeepsTheLastGoodAnswer() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path=[\"A\"]",
                new ToolInvoker.EndpointUnavailable("Connection refused"));
        TeamAgentResult initial = turn("ich schaue nach", "{\"type\":\"read\",\"path\":[\"A\"]}");

        TeamAgentResult result = ConceptToolRounds.run(initial, turns, tool, 4, 2, false, null, traceSink);

        assertEquals(initial, result);
        assertTrue(turns.feedbackSeen.isEmpty());
        assertTrue(trace.get(trace.size() - 1).contains("unavailable"));
    }

    /**
     * GROUNDING (live-gate 2): a finished turn whose scopePatch failed validation said "Das ist
     * notiert" while the commit was rejected. The broken patch now costs one repair inference —
     * the model sees the violations, the sink still receives the invalid attempt (host-side
     * REJECTED observability), and the corrected answer replaces the false claim.
     */
    @Test
    public void aFinishedTurnWithABrokenScopePatchGetsOneScopeRepairTurn() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        final List<ScopingAssistantOutput> intermediates = new ArrayList<ScopingAssistantOutput>();
        ScopingAssistantOutputParser.Result broken = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Das ist notiert.\","
                        + "\"scopePatch\":{\"operations\":[{\"kind\":\"excludeFacet\"}]},"
                        + "\"conceptAction\":{\"type\":\"none\"}}");
        assertTrue(broken.isOk());
        ScopingAssistantOutputParser.Result corrected = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"ESP-IDF ist ausgeschlossen.\","
                        + "\"scopePatch\":{\"operations\":[{\"kind\":\"excludeFacet\","
                        + "\"facetId\":\"esp-idf\"}]},"
                        + "\"conceptAction\":{\"type\":\"none\"}}");
        assertTrue(corrected.isOk());
        turns.script.add(TeamAgentResult.ok(corrected.getOutput(), null));

        TeamAgentResult result = ConceptToolRounds.run(
                TeamAgentResult.ok(broken.getOutput(), null), turns, new ScriptedTool(),
                4, 2, false,
                new ConceptToolRounds.IntermediateSink() {
                    public void intermediate(ScopingAssistantOutput output) {
                        intermediates.add(output);
                    }
                }, traceSink);

        assertEquals("the corrected answer replaces the false claim", "ESP-IDF ist ausgeschlossen.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        // The REPLACEMENT patch stays authoritative: the repaired output IS the returned final
        // result, and the caller (ResearchAgentMain.emitTeamAgentResult -> emitScopeUpdate)
        // emits exactly the final result's scope update to the host — repair fixes the COMMIT,
        // not just the wording.
        ScopeUpdateDocument replacement =
                ((ScopingAssistantOutput) result.getOutput()).getScopeUpdate();
        assertTrue("the corrected patch rides the final result into the authoritative emission",
                replacement != null && replacement.isValid());
        assertTrue(replacement.toJson(), replacement.toJson().contains("\"facetId\":\"esp-idf\""));
        String feedback = turns.feedbackSeen.get(0);
        assertTrue(feedback.startsWith("SCOPE PATCH REJECTED"));
        assertTrue("the violations travel verbatim", feedback.contains("excludeFacet without 'facetId'"));
        assertTrue("the false claim is forbidden explicitly", feedback.contains("NEVER claim"));
        assertEquals("the invalid attempt still reaches the sink (host logs the rejection)",
                1, intermediates.size());
        assertTrue(!intermediates.get(0).getScopeUpdate().isValid());
        assertTrue(trace.toString(), trace.toString().contains("scope patch REJECTED"));
    }

    /** A zero repair budget never loops: the broken-patch turn passes through unrepaired. */
    @Test
    public void aBrokenScopePatchWithoutRepairBudgetPassesThroughUntouched() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScopingAssistantOutputParser.Result broken = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"notiert\","
                        + "\"scopePatch\":{\"operations\":[{\"kind\":\"excludeFacet\"}]},"
                        + "\"conceptAction\":{\"type\":\"none\"}}");
        assertTrue(broken.isOk());
        TeamAgentResult initial = TeamAgentResult.ok(broken.getOutput(), null);
        assertEquals(initial, ConceptToolRounds.run(initial, turns, new ScriptedTool(),
                4, 0, false, null, traceSink));
        assertTrue(turns.feedbackSeen.isEmpty());
    }

    @Test
    public void aTurnWithoutAnActionPassesThroughUntouched() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        TeamAgentResult initial = turn("nur eine Antwort", null);
        assertEquals(initial, ConceptToolRounds.run(initial, turns, new ScriptedTool(),
                4, 2, false, null, traceSink));
        assertTrue(turns.feedbackSeen.isEmpty());
        assertTrue("an ABSENT field leaves no NONE trace", trace.isEmpty());
    }

    @Test
    public void anExplicitNoneIsObservableInTheTrace() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        TeamAgentResult initial = turn("nichts zu tun", "{\"type\":\"none\"}");
        assertEquals(initial, ConceptToolRounds.run(initial, turns, new ScriptedTool(),
                4, 2, false, null, traceSink));
        assertTrue(turns.feedbackSeen.isEmpty());
        assertTrue("the model CHOSE none — distinguishable from an absent field",
                trace.contains("concept action: NONE"));
    }
}
