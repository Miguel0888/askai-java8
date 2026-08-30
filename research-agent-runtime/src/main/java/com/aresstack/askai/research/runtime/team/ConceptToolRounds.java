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

    private ConceptToolRounds() {
    }

    /**
     * Drive the loop starting from the turn's FIRST result. Returns the FINAL result — the only
     * one whose visible message reaches the user; every intermediate answer is a working step.
     */
    public static TeamAgentResult run(TeamAgentResult initial, FollowUpTurn turn,
                                      ConceptTool tool, int maxToolRounds,
                                      int maxRepairAttempts, Trace trace) {
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
                feedback = TeamAgentPlaybook.conceptToolRejected(actionError);
            } else {
                trace.line("round " + rounds + ": " + action.describe());
                try {
                    String text = tool.call(action);
                    if (action.getType() == ConceptAction.Type.READ) {
                        trace.line("round " + rounds + " -> RESULT");
                        feedback = TeamAgentPlaybook.conceptToolResult(text);
                    } else {
                        conceptRevision = revisionIn(text, conceptRevision);
                        applied.add(action.describe() + " (revision " + conceptRevision + ")");
                        refetchConcept = true;
                        trace.line("round " + rounds + " -> APPLIED revision=" + conceptRevision);
                        feedback = TeamAgentPlaybook.conceptToolApplied(text);
                    }
                } catch (ToolInvoker.ToolFailure toolRejected) {
                    repairs++;
                    String reason = firstLine(toolRejected.getMessage());
                    rejected.add(action.describe() + " — " + reason);
                    refetchConcept = true; // prove to the model that NOTHING changed
                    trace.line("round " + rounds + " -> REJECTED " + reason);
                    feedback = TeamAgentPlaybook.conceptToolRejected(toolRejected.getMessage());
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
            budgetExhausted = rounds >= maxToolRounds || repairs > maxRepairAttempts;
            if (budgetExhausted) {
                trace.line("budget reached (rounds=" + rounds + "/" + maxToolRounds
                        + " repairs=" + repairs + "/" + maxRepairAttempts + ") — wrap-up turn");
                feedback = feedback + "\n\n" + TeamAgentPlaybook.conceptToolBudgetExhausted();
            }
            result = turn.run(TeamAgentPlaybook.conceptReceipts(conceptRevision, applied,
                    rejected, currentConcept) + feedback);
        }
        return result;
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
