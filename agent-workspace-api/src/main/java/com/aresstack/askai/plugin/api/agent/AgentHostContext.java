package com.aresstack.askai.plugin.api.agent;

import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

/**
 * The host services handed to an {@link AgentSession}. Unlike a workspace host context, this bundle contains
 * NO conversation-surface or composer factory: the chat and composer belong to the host and are shared. The
 * agent only receives a {@link AgentConversationSink} to feed the shared transcript, plus the generic services
 * it needs (threading, theme, a Markdown view factory for artifact rendering, persisted state, plugin paths,
 * notifications).
 *
 * <p>Scoped per (agentId, sessionId); nothing here is global. Every method is called on the UI thread unless
 * the individual service documents otherwise.</p>
 */
public interface AgentHostContext {

    UiExecutor getUiExecutor();

    ThemeService getThemeService();

    /** Host Markdown editor/viewer factory, reused for generic Markdown artifacts (no per-artifact Swing class). */
    MarkdownViewFactory getMarkdownViewFactory();

    NotificationService getNotificationService();

    WorkspaceStateStore getStateStore();

    PluginPathService getPluginPathService();

    /** The shared chat transcript the session pushes its activity into. */
    AgentConversationSink getConversationSink();
}
