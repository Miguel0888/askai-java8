package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-session model-backed research conversation engine. It owns ONE session's conversation state — the
 * system prompt, the running user/assistant history and the scope so far — and turns each user message into a
 * validated {@link TeamAgentTurn} by calling the configured main model ({@link MainModelChat}).
 *
 * <p>It is deliberately transport-agnostic and Solon/ACP-free so the whole conversation can be driven in a unit
 * test with a scripted fake model. The host's two nested state patterns stay the authority in three ways:</p>
 * <ul>
 *   <li><b>Commands.</b> The engine only PROPOSES a command and pre-validates that proposal against the live
 *       allowed set ({@link TeamAgentStateView}). An out-of-set command triggers ONE bounded repair; if the
 *       model insists, the turn is a {@link TeamAgentResult.Status#COMMAND_REJECTED} whose misleading message
 *       is withheld — never run, never invented, never falsely claimed.</li>
 *   <li><b>Scope.</b> The model may only PROPOSE a scope ({@link #getProposedQuestion()} /
 *       {@link #getProposedAspects()}). Only the host promotes a proposal to confirmed via
 *       {@link #applyConfirmedScope(String, List)}; the model is never allowed to mark its own scope as
 *       confirmed.</li>
 *   <li><b>Retries.</b> A user message is held as a single pending turn and only committed to history once the
 *       model answers usably, so {@link #retryPendingTurn(TeamAgentStateView)} after a failure never
 *       duplicates the user's message.</li>
 * </ul>
 *
 * <p>Model or transport failures never fabricate a turn; they surface as
 * {@link TeamAgentResult.Status#MODEL_UNAVAILABLE} or {@link TeamAgentResult.Status#UNUSABLE_ANSWER}.</p>
 *
 * <p>Not thread-safe: one instance per research session, driven from that session's single turn at a time.</p>
 */
public final class ResearchTeamAgent {

    /** A calm, deterministic temperature for a consultative planner (varied queries come from the prompt). */
    private static final double TEMPERATURE = 0.4;
    private static final int MAX_OUTPUT_TOKENS = 1024;

    private final MainModelChat model;
    private final List<ChatMessage> history = new ArrayList<ChatMessage>();

    /** Scope the HOST has confirmed (never set by the model). */
    private String confirmedQuestion = "";
    private final List<String> confirmedAspects = new ArrayList<String>();

    /** Scope the MODEL last proposed but that nobody has confirmed yet. */
    private String proposedQuestion = "";
    private final List<String> proposedAspects = new ArrayList<String>();

    private boolean greeted;
    private TeamAgentResult greetingResult;

    /** The user's message for the current turn, held until the model answers usably (retry-safe). */
    private ChatMessage pendingUserTurn;

    public ResearchTeamAgent(MainModelChat model) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        this.model = model;
    }

    public String modelName() {
        return model.modelName();
    }

    public String getProposedQuestion() {
        return proposedQuestion;
    }

    public List<String> getProposedAspects() {
        return new ArrayList<String>(proposedAspects);
    }

    public String getConfirmedQuestion() {
        return confirmedQuestion;
    }

    public List<String> getConfirmedAspects() {
        return new ArrayList<String>(confirmedAspects);
    }

    /**
     * Promote a scope to confirmed. Only the HOST calls this — after the user (via the host state pattern) has
     * approved it. The model is then told this scope is settled, but it can never set it itself.
     */
    public void applyConfirmedScope(String question, List<String> aspects) {
        confirmedQuestion = question == null ? "" : question.trim();
        confirmedAspects.clear();
        if (aspects != null) {
            for (String aspect : aspects) {
                if (aspect != null && !aspect.trim().isEmpty()) {
                    confirmedAspects.add(aspect.trim());
                }
            }
        }
    }

    public boolean hasGreeted() {
        return greeted;
    }

    public boolean hasPendingTurn() {
        return pendingUserTurn != null;
    }

    /**
     * Produce the opening greeting + first scoping question from the model. Truly idempotent within a session:
     * once a greeting has succeeded it is cached and returned as-is, so a second call neither re-asks the model
     * nor writes a second assistant turn. A greeting that FAILED (MODEL_UNAVAILABLE / UNUSABLE_ANSWER) leaves
     * {@link #hasGreeted()} false, so it can be retried.
     */
    public TeamAgentResult greet(TeamAgentStateView state) {
        if (greeted) {
            return greetingResult;
        }
        List<ChatMessage> messages = baseMessages(state);
        messages.add(ChatMessage.user(TeamAgentPlaybook.greetingInstruction()));
        TeamAgentResult result = runTurn(messages, state);
        if (result.isOk()) {
            greeted = true;
            greetingResult = result;
            recordAssistant(result.getTurn());
            foldProposal(result.getTurn());
        }
        return result;
    }

    /**
     * Advance the conversation with the user's message. The message becomes the single pending turn and is only
     * committed to history once the model answers usably; a successful answer also folds any scope update into
     * the PROPOSED scope (never the confirmed one).
     */
    public TeamAgentResult respond(String userText, TeamAgentStateView state) {
        pendingUserTurn = ChatMessage.user(userText == null ? "" : userText.trim());
        return runUserTurn(state);
    }

    /**
     * Re-run the pending user turn after a failure (MODEL_UNAVAILABLE / UNUSABLE_ANSWER / COMMAND_REJECTED)
     * without re-appending the user's message. Requires a pending turn — an OK turn clears it.
     */
    public TeamAgentResult retryPendingTurn(TeamAgentStateView state) {
        if (pendingUserTurn == null) {
            throw new IllegalStateException("no pending user turn to retry");
        }
        return runUserTurn(state);
    }

    // ------------------------------------------------------------------ internals

    private TeamAgentResult runUserTurn(TeamAgentStateView state) {
        List<ChatMessage> messages = baseMessages(state);
        messages.add(pendingUserTurn);
        TeamAgentResult result = runTurn(messages, state);
        if (result.isOk()) {
            history.add(pendingUserTurn);
            pendingUserTurn = null;
            recordAssistant(result.getTurn());
            foldProposal(result.getTurn());
        }
        return result;
    }

    /** system(playbook) + system(live state + confirmed/proposed scope) + the running user/assistant history. */
    private List<ChatMessage> baseMessages(TeamAgentStateView state) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(TeamAgentPlaybook.systemPrompt()));
        messages.add(ChatMessage.system(TeamAgentPlaybook.stateContext(
                state, confirmedQuestion, confirmedAspects, proposedQuestion, proposedAspects)));
        messages.addAll(history);
        return messages;
    }

    /**
     * The full turn pipeline: get a parseable turn (with one bounded parse repair), then enforce command
     * legality (with one bounded command repair). A non-OK model call is MODEL_UNAVAILABLE; an unparseable
     * answer after repair is UNUSABLE_ANSWER; an illegal command the model keeps proposing is COMMAND_REJECTED.
     * None of these fabricate or surface a misleading turn.
     */
    private TeamAgentResult runTurn(List<ChatMessage> messages, TeamAgentStateView state) {
        Parsed parsed = callParseWithRepair(messages);
        if (parsed.failure != null) {
            return parsed.failure;
        }
        // The model is an ASSISTANT, not a process controller: it proposes no commands and is never
        // policed against an allowed set. Any legacy proposedCommand it still emits is only honored when
        // the host happens to allow it — otherwise it is silently ignored (never a COMMAND_REJECTED, never
        // a nagging repair). Scope readiness is decided from the structured turn, not from a command.
        TeamAgentTurn turn = parsed.turn;
        String validatedCommand = turn.hasProposedCommand() && state.allows(turn.getProposedCommand())
                ? turn.getProposedCommand() : null;
        return TeamAgentResult.ok(turn, validatedCommand);
    }

    /** One model call + parse, with EXACTLY ONE bounded parse-repair on failure. */
    private Parsed callParseWithRepair(List<ChatMessage> messages) {
        MainModelChatResult call = model.complete(messages, TEMPERATURE, MAX_OUTPUT_TOKENS);
        if (!call.isOk()) {
            return Parsed.fail(TeamAgentResult.modelUnavailable(call.getDetail()));
        }
        TeamAgentTurnParser.Result parsed = TeamAgentTurnParser.parse(call.getText());
        if (parsed.isOk()) {
            return Parsed.ok(parsed.getTurn(), call.getText());
        }
        List<ChatMessage> repair = new ArrayList<ChatMessage>(messages);
        repair.add(ChatMessage.assistant(call.getText()));
        repair.add(ChatMessage.user(TeamAgentPlaybook.repairNudge()));
        return callParseOnce(repair);
    }

    /** One model call + parse, with NO further repair (used for the second, bounded repair attempts). */
    private Parsed callParseOnce(List<ChatMessage> messages) {
        MainModelChatResult call = model.complete(messages, TEMPERATURE, MAX_OUTPUT_TOKENS);
        if (!call.isOk()) {
            return Parsed.fail(TeamAgentResult.modelUnavailable(call.getDetail()));
        }
        TeamAgentTurnParser.Result parsed = TeamAgentTurnParser.parse(call.getText());
        if (!parsed.isOk()) {
            return Parsed.fail(TeamAgentResult.unusableAnswer(parsed.getError()));
        }
        return Parsed.ok(parsed.getTurn(), call.getText());
    }

    /**
     * Record the assistant turn so CONTEXT ACCUMULATES across short replies: the model's own visible
     * message PLUS a compact note of what it took as understood/open. The user only ever sees
     * assistantMessage (that is what the wire carries); the model, however, needs its own structured
     * understanding back in history — otherwise a following one-word reply has nothing to build on.
     */
    private void recordAssistant(TeamAgentTurn turn) {
        StringBuilder recorded = new StringBuilder(turn.getAssistantMessage());
        if (!turn.getUnderstoodFacts().isEmpty()) {
            recorded.append("\n[understood: ").append(join(turn.getUnderstoodFacts())).append(']');
        }
        if (!turn.getOpenQuestions().isEmpty()) {
            recorded.append("\n[still open: ").append(join(turn.getOpenQuestions())).append(']');
        }
        history.add(ChatMessage.assistant(recorded.toString()));
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    /** Fold a scope update from the model into the PROPOSED scope (last statement wins for the question). */
    private void foldProposal(TeamAgentTurn turn) {
        if (turn.getQuestion() != null) {
            proposedQuestion = turn.getQuestion();
        }
        if (!turn.getAspects().isEmpty()) {
            proposedAspects.clear();
            proposedAspects.addAll(turn.getAspects());
        }
    }

    /** A parsed turn (with its raw text, for repair context) OR a typed failure result — never both. */
    private static final class Parsed {
        final TeamAgentResult failure;
        final TeamAgentTurn turn;
        final String raw;

        private Parsed(TeamAgentResult failure, TeamAgentTurn turn, String raw) {
            this.failure = failure;
            this.turn = turn;
            this.raw = raw;
        }

        static Parsed fail(TeamAgentResult failure) {
            return new Parsed(failure, null, null);
        }

        static Parsed ok(TeamAgentTurn turn, String raw) {
            return new Parsed(null, turn, raw);
        }
    }
}
