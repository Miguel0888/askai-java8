package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-session model-backed research conversation engine. It owns ONE session's conversation state — the
 * system prompt, the running user/assistant history and the confirmed scope so far — and turns each user
 * message into a validated {@link TeamAgentTurn} by calling the configured main model ({@link MainModelChat}).
 *
 * <p>It is deliberately transport-agnostic and Solon/ACP-free so the whole conversation can be driven in a unit
 * test with a scripted fake model. The host's two nested state patterns stay the authority: this engine only
 * PROPOSES a command, and it pre-validates that proposal against the live allowed set ({@link TeamAgentStateView})
 * — an out-of-set command is dropped, never run and never invented. Model or transport failures never fabricate
 * a turn; they surface as {@link TeamAgentResult.Status#MODEL_UNAVAILABLE} or
 * {@link TeamAgentResult.Status#UNUSABLE_ANSWER}.</p>
 *
 * <p>Not thread-safe: one instance per research session, driven from that session's single turn at a time.</p>
 */
public final class ResearchTeamAgent {

    /** A calm, deterministic temperature for a consultative planner (varied queries come from the prompt). */
    private static final double TEMPERATURE = 0.4;
    private static final int MAX_OUTPUT_TOKENS = 1024;

    private final MainModelChat model;
    private final List<ChatMessage> history = new ArrayList<ChatMessage>();

    private String question = "";
    private final List<String> aspects = new ArrayList<String>();
    private boolean greeted;

    public ResearchTeamAgent(MainModelChat model) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        this.model = model;
    }

    public String modelName() {
        return model.modelName();
    }

    public String getQuestion() {
        return question;
    }

    public List<String> getAspects() {
        return new ArrayList<String>(aspects);
    }

    /**
     * Produce the opening greeting + first scoping question from the model. Idempotent within a session: a
     * second call re-greets only if the first never succeeded (so a MODEL_UNAVAILABLE greeting can be retried).
     */
    public TeamAgentResult greet(TeamAgentStateView state) {
        List<ChatMessage> messages = baseMessages(state);
        messages.add(ChatMessage.user(TeamAgentPlaybook.greetingInstruction()));
        TeamAgentResult result = callAndParse(messages, state);
        if (result.isOk()) {
            greeted = true;
            recordAssistant(result.getTurn());
        }
        return result;
    }

    public boolean hasGreeted() {
        return greeted;
    }

    /**
     * Advance the conversation with the user's message. The user turn is appended to history first (so a retry
     * after MODEL_UNAVAILABLE re-sends the same context); a successful, parseable answer appends the assistant
     * turn and folds any scope update into the confirmed scope.
     */
    public TeamAgentResult respond(String userText, TeamAgentStateView state) {
        String text = userText == null ? "" : userText.trim();
        history.add(ChatMessage.user(text));
        List<ChatMessage> messages = baseMessages(state);
        TeamAgentResult result = callAndParse(messages, state);
        if (result.isOk()) {
            recordAssistant(result.getTurn());
            foldScope(result.getTurn());
        }
        return result;
    }

    // ------------------------------------------------------------------ internals

    /** system(playbook) + system(live state context) + the running user/assistant history. */
    private List<ChatMessage> baseMessages(TeamAgentStateView state) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(TeamAgentPlaybook.systemPrompt()));
        messages.add(ChatMessage.system(TeamAgentPlaybook.stateContext(state, question, aspects)));
        messages.addAll(history);
        return messages;
    }

    /**
     * Call the model, parse the structured turn, and on a parse failure make EXACTLY ONE bounded repair
     * attempt (appending a nudge) before returning an honest UNUSABLE_ANSWER. A non-OK model call is
     * MODEL_UNAVAILABLE. Neither failure mutates the history's assistant side or fabricates a turn.
     */
    private TeamAgentResult callAndParse(List<ChatMessage> messages, TeamAgentStateView state) {
        MainModelChatResult call = model.complete(messages, TEMPERATURE, MAX_OUTPUT_TOKENS);
        if (!call.isOk()) {
            return TeamAgentResult.modelUnavailable(call.getDetail());
        }
        TeamAgentTurnParser.Result parsed = TeamAgentTurnParser.parse(call.getText());
        if (!parsed.isOk()) {
            // One bounded repair: re-ask with the same context plus a "valid JSON only" nudge.
            List<ChatMessage> repair = new ArrayList<ChatMessage>(messages);
            repair.add(ChatMessage.assistant(call.getText()));
            repair.add(ChatMessage.user(TeamAgentPlaybook.repairNudge()));
            MainModelChatResult retry = model.complete(repair, TEMPERATURE, MAX_OUTPUT_TOKENS);
            if (!retry.isOk()) {
                return TeamAgentResult.modelUnavailable(retry.getDetail());
            }
            parsed = TeamAgentTurnParser.parse(retry.getText());
            if (!parsed.isOk()) {
                return TeamAgentResult.unusableAnswer(parsed.getError());
            }
        }
        TeamAgentTurn turn = parsed.getTurn();
        String validatedCommand = turn.hasProposedCommand() && state.allows(turn.getProposedCommand())
                ? turn.getProposedCommand() : null;
        return TeamAgentResult.ok(turn, validatedCommand);
    }

    private void recordAssistant(TeamAgentTurn turn) {
        history.add(ChatMessage.assistant(turn.getAssistantMessage()));
    }

    /** Fold a scope update from the model into the confirmed scope (last statement wins for the question). */
    private void foldScope(TeamAgentTurn turn) {
        if (turn.getQuestion() != null) {
            question = turn.getQuestion();
        }
        if (!turn.getAspects().isEmpty()) {
            aspects.clear();
            aspects.addAll(turn.getAspects());
        }
    }
}
