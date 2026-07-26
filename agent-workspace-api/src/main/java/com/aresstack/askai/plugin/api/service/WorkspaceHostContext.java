package com.aresstack.askai.plugin.api.service;

/**
 * The bundle of host services handed to a workspace when it is created. It exposes only stable service
 * interfaces — never {@code AskAiFrame}, {@code OllamaChatPanel} or any other app implementation class.
 *
 * <p>A context is <b>scoped to one {@code (pluginId, workspaceInstanceId)} pair</b>, not shared globally.
 * The host builds it per workspace so that {@link WorkspaceStateStore} and {@link PluginPathService} (and
 * notifications) are isolated: one plugin/workspace can never read another's state or collide on keys.</p>
 */
public interface WorkspaceHostContext {

    UiExecutor getUiExecutor();

    ThemeService getThemeService();

    MarkdownViewFactory getMarkdownViewFactory();

    ConversationSurfaceFactory getConversationSurfaceFactory();

    /** Reusable Yapping/Questing + agent selector bound to the host's shared interaction-mode controller. */
    InteractionModeControlsFactory getInteractionModeControlsFactory();

    WorkspaceStateStore getWorkspaceStateStore();

    PluginPathService getPluginPathService();

    NotificationService getNotificationService();
}
