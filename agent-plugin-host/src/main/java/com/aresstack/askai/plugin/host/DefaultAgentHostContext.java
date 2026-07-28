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
    private final java.util.Map<Class<?>, Object> services;

    public DefaultAgentHostContext(UiExecutor uiExecutor, ThemeService themeService,
                                   MarkdownViewFactory markdownViewFactory,
                                   NotificationService notificationService, WorkspaceStateStore stateStore,
                                   PluginPathService pluginPathService, AgentConversationSink conversationSink) {
        this(uiExecutor, themeService, markdownViewFactory, notificationService, stateStore,
                pluginPathService, conversationSink,
                java.util.Collections.<Class<?>, Object>emptyMap());
    }

    /** @param services optional host runtime services by interface type (see AgentHostContext#getService). */
    public DefaultAgentHostContext(UiExecutor uiExecutor, ThemeService themeService,
                                   MarkdownViewFactory markdownViewFactory,
                                   NotificationService notificationService, WorkspaceStateStore stateStore,
                                   PluginPathService pluginPathService, AgentConversationSink conversationSink,
                                   java.util.Map<Class<?>, Object> services) {
        this.uiExecutor = uiExecutor;
        this.themeService = themeService;
        this.markdownViewFactory = markdownViewFactory;
        this.notificationService = notificationService;
        this.stateStore = stateStore;
        this.pluginPathService = pluginPathService;
        this.conversationSink = conversationSink;
        this.services = new java.util.LinkedHashMap<Class<?>, Object>(services);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getService(Class<T> type) {
        Object service = services.get(type);
        return service == null ? null : (T) service;
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
