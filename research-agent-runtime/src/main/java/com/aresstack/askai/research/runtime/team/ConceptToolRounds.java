package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

/**
 * The TOOL-ROUND loop of a conceptualization turn — deliberately NOT just a repair loop. With the
 * one-JSON-per-inference contract the model cannot read a branch and edit it within the SAME
 * inference, so each tool step is its own round: inference → conceptAction → host tool →
 * feedback → next inference. Three feedback kinds are kept explicit:
 * <ul>
 *   <li>{@code TOOL_RESULT} — a regular read result (a working step, budget: tool rounds);</li>
 *   <li>{@code TOOL_APPLIED} — a mutation committed (working step as well);</li>
 *   <li>{@code TOOL_REJECTED} — a mutation refused with a diagnostic (counts against the
 *       SEPARATE repair budget, so error tolerance never eats the work budget or vice versa).</li>
 * </ul>
 * Both budgets are the user's settings (env hand-off), never hidden constants. When either budget
 * is exhausted the model gets ONE final wrap-up inference that explicitly forbids further actions;
 * an action it emits anyway is dropped with a trace line. An unreachable endpoint aborts the loop
 * and keeps the last good result — never a fabricated turn. The change semantics stay entirely in
 * the host's ConceptBranchService; this class only orchestrates.
 */
public final class ConceptToolRounds {

    /** Documented defaults; the EFFECTIVE values are the user's settings, handed to the process. */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 4;
    public static final int DEFAULT_MAX_REPAIR_ATTEMPTS = 2;

    /** One follow-up inference within the same user turn (machinery instruction, not user text). */
    public interface FollowUpTurn {
        TeamAgentResult run(String feedbackInstruction);
    }

    /** The synchronous host tool call (MCP lane). Returns the tool's text; failures throw. */
    public interface ConceptTool {
        String call(ConceptAction action)
                throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;
    }

    /** Technical trace lines (collapsible diagnostics on the host, never a chat bubble). */
    public interface Trace {
        void line(String message);
    }

    /**
     * Receives every INTERMEDIATE output the loop replaces with a follow-up inference. The live
     * exclusion bug: a turn answering "kein ESP-IDF, nur Arduino" carried conceptAction AND
     * scopePatch — the loop kept only the final (usually patch-free) result, so the exclusion
     * silently vanished. The caller emits each consumed round's scope update through this sink.
     */
    public interface IntermediateSink {
        void intermediate(ScopingAssistantOutput output);
    }

    private ConceptToolRounds() {
    }

    /**
     * Drive the loop starting from the turn's FIRST result. Returns the FINAL result — the only
     * one whose visible message reaches the user; every intermediate answer is a working step.
     */
    /** @param germanFeedback the session's language selector: feedback sentences follow it so
     *  the model is never pushed into switching its reply language. */
    public static TeamAgentResult run(TeamAgentResult initial, FollowUpTurn turn,
                                      ConceptTool tool, int maxToolRounds,
                                      int maxRepairAttempts, boolean germanFeedback,
                                      IntermediateSink intermediateSink, Trace trace) {
        TeamAgentResult result = initial;
        int rounds = 0;
        int repairs = 0;
        boolean budgetExhausted = false;
        // The AUTHORITATIVE change receipts, carried across the rounds: every feedback lists
        // WHICH actions were applied and WHICH were rejected (and why), plus the CURRENT
        // persisted concept after any mutation attempt — a lone boolean once let one applied
        // add be claimed as four.
        long conceptRevision = -1L;
        java.util.List<String> applied = new java.util.ArrayList<String>();
        java.util.List<String> rejected = new java.util.ArrayList<String>();
        String currentConcept = null;
        boolean refetchConcept = false;
        while (result != null && result.isOk()
                && result.getOutput() instanceof ScopingAssistantOutput) {
            ScopingAssistantOutput output = (ScopingAssistantOutput) result.getOutput();
            ConceptAction action = output.getConceptAction();
            String actionError = output.getConceptActionError();
            if (action == null && actionError == null) {
                if (output.isConceptActionExplicitNone()) {
                    // Observable: the model CHOSE none — distinguishable from an absent field.
                    trace.line("concept action: NONE");
                }
                // GROUNDING (live-gate 2): a finished turn whose scopePatch failed validation
                // would leave the visible answer claiming a change the application refused
                // ("Das ist notiert" over a rejected excludeFacet). A broken patch is repaired
                // like a rejected tool call — same error-tolerance budget, one honest retry.
                ScopeUpdateDocument scopeUpdate = output.getScopeUpdate();
                if (scopeUpdate != null && !scopeUpdate.isValid() && !budgetExhausted
                        && repairs < maxRepairAttempts) {
                    repairs++;
                    trace.line("scope patch REJECTED (" + scopeUpdate.describeViolations()
                            + ") — repair turn (repairs=" + repairs + "/" + maxRepairAttempts + ")");
                    if (intermediateSink != null) {
                        // The invalid attempt still travels to the host — its rejection line is
                        // exactly the observability the technical_log gate asked for.
                        intermediateSink.intermediate(output);
                    }
                    result = turn.run(TeamAgentPlaybook.scopePatchRejected(
                            scopeUpdate.describeViolations(), germanFeedback));
                    continue;
                }
                // Mission bookkeeping is the HOST'S job (live-gate 4 decision): it records the
                // user's first message as the mission mechanically — no repair loop begs the
                // model for setMission anymore.
                return result; // the model finished without a further action — the normal end
            }
            if (budgetExhausted) {
                trace.line("tool budget exhausted — dropping the further conceptAction");
                return result;
            }
            rounds++;
            String feedback;
            if (actionError != null) {
                // A malformed action never reaches the host; the reason goes straight back.
                repairs++;
                rejected.add("(invalid) " + firstLine(actionError));
                trace.line("round " + rounds + ": invalid conceptAction (" + actionError + ")");
                feedback = TeamAgentPlaybook.conceptToolRejected(actionError, germanFeedback);
            } else {
                trace.line("round " + rounds + ": " + action.describe());
                try {
                    String text = tool.call(action);
                    if (action.getType() == ConceptAction.Type.EXCLUDE
                            || action.getType() == ConceptAction.Type.RESOLVE) {
                        // TERMINAL (gate 5): one command, one effect, TURN OVER. The visible
                        // answer is the platform's deterministic receipt sentence — a further
                        // inference once contradicted the committed blacklist entry ("keine
                        // dauerhaften Änderungen" right after EXCLUDED was persisted).
                        trace.line("round " + rounds + " -> "
                                + (action.getType() == ConceptAction.Type.EXCLUDE
                                        ? "EXCLUDED" : "RESOLVED") + " (terminal)");
                        if (intermediateSink != null) {
                            // Whatever else the turn proposed (scopePatch, suggestions) is
                            // emitted before its output is replaced by the receipt answer.
                            intermediateSink.intermediate(output);
                        }
                        return receiptResult(text, result);
                    }
                    if (action.getType() == ConceptAction.Type.READ) {
                        trace.line("round " + rounds + " -> RESULT");
                        feedback = TeamAgentPlaybook.conceptToolResult(text, germanFeedback);
                    } else {
                        conceptRevision = revisionIn(text, conceptRevision);
                        applied.add(action.describe() + " (revision " + conceptRevision + ")");
                        refetchConcept = true;
                        trace.line("round " + rounds + " -> APPLIED revision=" + conceptRevision);
                        feedback = TeamAgentPlaybook.conceptToolApplied(text, germanFeedback);
                    }
                } catch (ToolInvoker.ToolFailure toolRejected) {
                    repairs++;
                    String reason = firstLine(toolRejected.getMessage());
                    rejected.add(action.describe() + " — " + reason);
                    refetchConcept = true; // prove to the model that NOTHING changed
                    trace.line("round " + rounds + " -> REJECTED " + reason);
                    feedback = TeamAgentPlaybook.conceptToolRejected(toolRejected.getMessage(), germanFeedback);
                } catch (ToolInvoker.EndpointUnavailable dead) {
                    // Infrastructure, not the model's fault: keep the last good result, no retry.
                    trace.line("concept endpoint unavailable — keeping the last answer ("
                            + firstLine(dead.getMessage()) + ")");
                    return result;
                }
            }
            if (refetchConcept) {
                // Ground the receipts in the PERSISTED state (best effort — a failed fetch
                // simply omits the block, it never breaks the loop).
                try {
                    currentConcept = tool.call(readAll());
                    refetchConcept = false;
                } catch (ToolInvoker.ToolFailure unavailable) {
                    currentConcept = null;
                } catch (ToolInvoker.EndpointUnavailable unavailable) {
                    currentConcept = null;
                }
            }
            ScopeUpdateDocument roundScope = output.getScopeUpdate();
            if (roundScope != null && !roundScope.isValid()) {
                // The round continues anyway (concept feedback) — the scope rejection rides the
                // SAME feedback for free instead of costing an extra repair inference.
                trace.line("scope patch REJECTED (" + roundScope.describeViolations()
                        + ") — noted in round feedback");
                feedback = feedback + "\n\n" + TeamAgentPlaybook.scopePatchRejected(
                        roundScope.describeViolations(), germanFeedback);
            }
            budgetExhausted = rounds >= maxToolRounds || repairs > maxRepairAttempts;
            if (budgetExhausted) {
                trace.line("budget reached (rounds=" + rounds + "/" + maxToolRounds
                        + " repairs=" + repairs + "/" + maxRepairAttempts + ") — wrap-up turn");
                feedback = feedback + "\n\n" + TeamAgentPlaybook.conceptToolBudgetExhausted(germanFeedback);
            }
            if (intermediateSink != null) {
                // This output is about to be REPLACED by the follow-up inference — whatever it
                // proposed beyond the concept action (scopePatch!) must not vanish with it.
                intermediateSink.intermediate(output);
            }
            result = turn.run(TeamAgentPlaybook.conceptReceipts(conceptRevision, applied,
                    rejected, currentConcept, germanFeedback) + feedback);
        }
        return result;
    }

    /**
     * The final result of a terminal EXCLUDE/RESOLVE: a synthetic output whose visible message
     * is the receipt's {@code userMessage} (host-authored, session language) — never model
     * prose. When the receipt carries no such field (older host), the previous result stands
     * unchanged rather than showing raw JSON to the user.
     */
    private static TeamAgentResult receiptResult(String receiptJson, TeamAgentResult fallback) {
        String userMessage = null;
        try {
            Object parsed = com.aresstack.askai.agent.model.reranker.MiniJson.parse(receiptJson);
            if (parsed instanceof java.util.Map) {
                Object message = ((java.util.Map<?, ?>) parsed).get("userMessage");
                if (message instanceof String && !((String) message).trim().isEmpty()) {
                    userMessage = ((String) message).trim();
                }
            }
        } catch (RuntimeException notJson) {
            userMessage = null;
        }
        if (userMessage == null) {
            return fallback;
        }
        StringBuilder json = new StringBuilder("{\"assistantMessage\":\"");
        for (int index = 0; index < userMessage.length(); index++) {
            char character = userMessage.charAt(index);
            if (character == '"' || character == '\\') {
                json.append('\\').append(character);
            } else if (character == '\n') {
                json.append("\\n");
            } else {
                json.append(character);
            }
        }
        json.append("\"}");
        ScopingAssistantOutputParser.Result synthetic =
                ScopingAssistantOutputParser.parse(json.toString());
        return synthetic.isOk() ? TeamAgentResult.ok(synthetic.getOutput(), null) : fallback;
    }

    /** The whole-concept read used to ground the receipts in the persisted state. */
    private static ConceptAction readAll() {
        ConceptAction.Parsed parsed = ConceptAction.parse(
                java.util.Collections.singletonMap("type", (Object) "read"));
        return parsed.getAction();
    }

    /** The {@code revision=N} the host reports on an applied call, or the previous value. */
    private static long revisionIn(String toolText, long fallback) {
        if (toolText != null) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("revision=(\\d+)").matcher(toolText);
            if (matcher.find()) {
                try {
                    return Long.parseLong(matcher.group(1));
                } catch (NumberFormatException overflow) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }
}
