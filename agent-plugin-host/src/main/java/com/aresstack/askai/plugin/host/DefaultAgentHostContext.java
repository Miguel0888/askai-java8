package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

/** Immutable {@link AgentHostContext} bundle assembled by the app from its host services + the shared sink. */
public final class DefaultAgentHostContext implements AgentHostContext {

    private final UiExecutor uiExecutor;
    private final ThemeService themeService;
    private final MarkdownViewFactory markdownViewFactory;
    private final NotificationService notificationService;
    private final WorkspaceStateStore stateStore;
    private final PluginPathService pluginPathService;
    private final AgentConversationSink conversationSink;

    public DefaultAgentHostContext(UiExecutor uiExecutor, ThemeService themeService,
                                   MarkdownViewFactory markdownViewFactory,
                                   NotificationService notificationService, WorkspaceStateStore stateStore,
                                   PluginPathService pluginPathService, AgentConversationSink conversationSink) {
        this.uiExecutor = uiExecutor;
        this.themeService = themeService;
        this.markdownViewFactory = markdownViewFactory;
        this.notificationService = notificationService;
        this.stateStore = stateStore;
        this.pluginPathService = pluginPathService;
        this.conversationSink = conversationSink;
    }

    public UiExecutor getUiExecutor() {
        return uiExecutor;
    }

    public ThemeService getThemeService() {
        return themeService;
    }

    public MarkdownViewFactory getMarkdownViewFactory() {
        return markdownViewFactory;
    }

    public NotificationService getNotificationService() {
        return notificationService;
    }

    public WorkspaceStateStore getStateStore() {
        return stateStore;
    }

    public PluginPathService getPluginPathService() {
        return pluginPathService;
    }

    public AgentConversationSink getConversationSink() {
        return conversationSink;
    }
}
