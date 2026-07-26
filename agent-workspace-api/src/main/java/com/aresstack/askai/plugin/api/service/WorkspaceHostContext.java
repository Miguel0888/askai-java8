package com.aresstack.askai.plugin.api.service;

/**
 * The bundle of host services handed to a workspace when it is created. It exposes only stable service
 * interfaces — never {@code AskAiFrame}, {@code OllamaChatPanel} or any other app implementation class.
 */
public interface WorkspaceHostContext {

    UiExecutor getUiExecutor();

    ThemeService getThemeService();

    MarkdownViewFactory getMarkdownViewFactory();

    ConversationSurfaceFactory getConversationSurfaceFactory();

    WorkspaceStateStore getWorkspaceStateStore();

    PluginPathService getPluginPathService();

    NotificationService getNotificationService();
}
