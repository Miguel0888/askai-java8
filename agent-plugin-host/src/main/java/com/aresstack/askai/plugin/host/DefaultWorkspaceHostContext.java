package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.ConversationSurfaceFactory;
import com.aresstack.askai.plugin.api.service.InteractionModeControlsFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceHostContext;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

/** Plain holder for the seven host services, built per workspace by {@link WorkspaceHostContextFactory}. */
final class DefaultWorkspaceHostContext implements WorkspaceHostContext {

    private final UiExecutor uiExecutor;
    private final ThemeService themeService;
    private final MarkdownViewFactory markdownViewFactory;
    private final ConversationSurfaceFactory conversationSurfaceFactory;
    private final InteractionModeControlsFactory interactionModeControlsFactory;
    private final WorkspaceStateStore workspaceStateStore;
    private final PluginPathService pluginPathService;
    private final NotificationService notificationService;

    DefaultWorkspaceHostContext(UiExecutor uiExecutor, ThemeService themeService,
                                MarkdownViewFactory markdownViewFactory,
                                ConversationSurfaceFactory conversationSurfaceFactory,
                                InteractionModeControlsFactory interactionModeControlsFactory,
                                WorkspaceStateStore workspaceStateStore,
                                PluginPathService pluginPathService,
                                NotificationService notificationService) {
        this.uiExecutor = uiExecutor;
        this.themeService = themeService;
        this.markdownViewFactory = markdownViewFactory;
        this.conversationSurfaceFactory = conversationSurfaceFactory;
        this.interactionModeControlsFactory = interactionModeControlsFactory;
        this.workspaceStateStore = workspaceStateStore;
        this.pluginPathService = pluginPathService;
        this.notificationService = notificationService;
    }

    @Override
    public UiExecutor getUiExecutor() {
        return uiExecutor;
    }

    @Override
    public ThemeService getThemeService() {
        return themeService;
    }

    @Override
    public MarkdownViewFactory getMarkdownViewFactory() {
        return markdownViewFactory;
    }

    @Override
    public ConversationSurfaceFactory getConversationSurfaceFactory() {
        return conversationSurfaceFactory;
    }

    @Override
    public InteractionModeControlsFactory getInteractionModeControlsFactory() {
        return interactionModeControlsFactory;
    }

    @Override
    public WorkspaceStateStore getWorkspaceStateStore() {
        return workspaceStateStore;
    }

    @Override
    public PluginPathService getPluginPathService() {
        return pluginPathService;
    }

    @Override
    public NotificationService getNotificationService() {
        return notificationService;
    }
}
