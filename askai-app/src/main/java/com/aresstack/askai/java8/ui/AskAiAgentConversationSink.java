package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.ui.bubble.BubbleTranscriptPanel;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;

import java.util.HashMap;
import java.util.Map;

/**
 * Adapts the generic {@link AgentConversationSink} onto the SHARED chat transcript. There is no second
 * conversation surface: agent user/assistant/thinking/tool/approval activity renders in the exact same bubbles
 * as the normal Ollama chat. Activity ids map to the transcript's opaque handles. A refresh callback lets the
 * composer re-read Send/Stop availability as the run progresses. All calls must be on the EDT.
 */
final class AskAiAgentConversationSink implements AgentConversationSink {

    private final ChatTranscript transcript;
    private final Runnable afterUpdate;
    private final Map<String, BubbleTranscriptPanel.ThinkingHandle> thinking =
            new HashMap<String, BubbleTranscriptPanel.ThinkingHandle>();
    private final Map<String, BubbleTranscriptPanel.AgentActivityHandle> tools =
            new HashMap<String, BubbleTranscriptPanel.AgentActivityHandle>();

    AskAiAgentConversationSink(ChatTranscript transcript, Runnable afterUpdate) {
        this.transcript = transcript;
        this.afterUpdate = afterUpdate;
    }

    @Override
    public void appendUserMessage(String messageId, String markdown) {
        transcript.appendUser(markdown);
        refresh();
    }

    @Override
    public void appendAssistantMessage(String messageId, String markdown) {
        transcript.startAssistant("Agent");
        transcript.appendAssistantDelta(markdown);
        transcript.finishAssistant();
        refresh();
    }

    @Override
    public void startThinking(String activityId, String title) {
        thinking.put(activityId, transcript.startAssistantThinking(title));
        refresh();
    }

    @Override
    public void updateThinking(String activityId, String text) {
        BubbleTranscriptPanel.ThinkingHandle handle = thinking.get(activityId);
        if (handle != null) {
            transcript.appendAssistantThinkingDelta(handle, text);
        }
    }

    @Override
    public void finishThinking(String activityId, String summary) {
        BubbleTranscriptPanel.ThinkingHandle handle = thinking.remove(activityId);
        if (handle != null) {
            transcript.completeAssistantThinking(handle, summary);
        }
        refresh();
    }

    @Override
    public void startToolActivity(String activityId, String title, String explanation) {
        tools.put(activityId, transcript.startAgentActivity(title, explanation));
        refresh();
    }

    @Override
    public void updateToolActivity(String activityId, String title, String explanation) {
        BubbleTranscriptPanel.AgentActivityHandle handle = tools.get(activityId);
        if (handle != null) {
            transcript.updateAgentActivity(handle, title, explanation);
        }
    }

    @Override
    public void completeToolActivity(String activityId, String summary) {
        BubbleTranscriptPanel.AgentActivityHandle handle = tools.remove(activityId);
        if (handle != null) {
            transcript.completeAgentActivity(handle, summary);
        }
        refresh();
    }

    @Override
    public void failToolActivity(String activityId, String summary) {
        BubbleTranscriptPanel.AgentActivityHandle handle = tools.remove(activityId);
        if (handle != null) {
            transcript.failAgentActivity(handle, summary);
        }
        refresh();
    }

    @Override
    public void requestApproval(String approvalId, String prompt) {
        // Clearly recognizable, actionable via the shared composer's slash commands (/approve, /request-changes).
        transcript.startAssistant("Agent · approval");
        transcript.appendAssistantDelta(prompt
                + "\n\n_Type_ `/approve` _or_ `/request-changes` _to respond._");
        transcript.finishAssistant();
        refresh();
    }

    @Override
    public void showProblem(String problemId, String publicMessage) {
        transcript.appendInfo("⚠ " + publicMessage);
        refresh();
    }

    @Override
    public void showActionCard(String cardId, String markdown, final java.util.List<ActionOption> actions,
                               final ActionHandler handler) {
        transcript.startAssistant("Agent");
        transcript.appendAssistantDelta(markdown);
        transcript.finishAssistant();
        java.util.List<String> labels = new java.util.ArrayList<String>();
        for (ActionOption option : actions) {
            labels.add(option.getLabel());
        }
        transcript.appendActionButtons(labels,
                new BubbleTranscriptPanel.ActionInvoker() {
                    public void invoke(int index) {
                        if (handler != null && index >= 0 && index < actions.size()) {
                            handler.onAction(actions.get(index).getId());
                        }
                    }
                });
        refresh();
    }

    private void refresh() {
        if (afterUpdate != null) {
            afterUpdate.run();
        }
    }
}
