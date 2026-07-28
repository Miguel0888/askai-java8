package com.aresstack.askai.plugin.api.agent;

/**
 * Host port through which an {@link AgentSession} pushes conversation activity into the <em>shared</em> AskAI
 * chat transcript. There is exactly one conversation surface in the product — the normal chat — so an agent
 * never creates its own; it adapts its backend events onto this sink and the host renders them in the existing
 * user/assistant/thinking/tool/approval bubbles.
 *
 * <p>Everything is addressed by stable ids so updates target the right bubble. Raw tool arguments/results must
 * not be pushed unfiltered. All methods are called on the UI thread; after the session is closed the host may
 * ignore further calls.</p>
 */
public interface AgentConversationSink {

    void appendUserMessage(String messageId, String markdown);

    void appendAssistantMessage(String messageId, String markdown);

    void startThinking(String activityId, String title);

    void updateThinking(String activityId, String text);

    void finishThinking(String activityId, String summary);

    void startToolActivity(String activityId, String title, String explanation);

    void updateToolActivity(String activityId, String title, String explanation);

    void completeToolActivity(String activityId, String summary);

    void failToolActivity(String activityId, String summary);

    /** Render an interactive approval request in the chat; the id is the approval the user later acts on. */
    void requestApproval(String approvalId, String prompt);

    /** Render a blocked/failed condition as a readable status bubble (public message only). */
    void showProblem(String problemId, String publicMessage);

    /**
     * One technical diagnostic line for the host's collapsible "Technical details" area — NEVER rendered
     * in the visible chat or inside a card. Hosts without such an area may drop the line.
     */
    default void appendTechnicalLog(String line) {
    }

    /** One typed action offered on an interactive card (stable id + localized label). */
    final class ActionOption {
        private final String id;
        private final String label;

        public ActionOption(String id, String label) {
            this.id = id == null ? "" : id;
            this.label = label == null ? "" : label;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Receives the id of the action the user pressed on a card (called on the UI thread). */
    interface ActionHandler {
        void onAction(String actionId);
    }

    /**
     * Render ONE interactive result/decision card: readable markdown plus real buttons that dispatch typed
     * actions back to the session — never synthetic chat messages. A host without card support falls back
     * to the plain message (the text alone must remain understandable).
     */
    default void showActionCard(String cardId, String markdown, java.util.List<ActionOption> actions,
                                ActionHandler handler) {
        appendAssistantMessage(cardId, markdown);
    }
}
