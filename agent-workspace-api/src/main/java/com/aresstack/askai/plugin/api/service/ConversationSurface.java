package com.aresstack.askai.plugin.api.service;

import javax.swing.JComponent;

/**
 * A host-provided conversation/activity surface backed by the existing bubble, thinking and tool-activity
 * components. Everything is addressed by stable ids so updates target the right bubble. Raw tool arguments
 * and results must not be pushed unfiltered into public tool bubbles.
 */
public interface ConversationSurface {

    JComponent getComponent();

    void addUserMessage(String messageId, String markdown);

    void addAssistantMessage(String messageId, String markdown);

    void startAssistantStreaming(String messageId);

    void appendAssistantDelta(String messageId, String delta);

    void finishAssistantStreaming(String messageId);

    void startThinking(String activityId, String title);

    void updateThinking(String activityId, String text);

    void finishThinking(String activityId, String summary);

    void startToolActivity(String activityId, String title, String explanation);

    void updateToolActivity(String activityId, String title, String explanation);

    void markApprovalRequired(String activityId, String explanation);

    void completeToolActivity(String activityId, String summary);

    void failToolActivity(String activityId, String summary);

    void cancelActivity(String activityId, String summary);

    void clear();

    void dispose();
}
