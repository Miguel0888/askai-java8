package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.ConversationSurfaceFactory;
import com.aresstack.askai.plugin.api.service.InteractionModeControlsFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceHostContext;

import java.io.File;

/**
 * Builds a {@link WorkspaceHostContext} scoped to one {@code (pluginId, workspaceInstanceId)} pair. The
 * cross-cutting services (EDT executor, theme, Markdown/Conversation factories, notifications) are supplied
 * once by the host application and shared; the per-workspace state store and path service are created fresh
 * and isolated for each call so plugins and workspaces cannot read each other's state or collide on keys.
 */
public final class WorkspaceHostContextFactory {

    private final File dataDirectory;
    private final UiExecutor uiExecutor;
    private final ThemeService themeService;
    private final MarkdownViewFactory markdownViewFactory;
    private final ConversationSurfaceFactory conversationSurfaceFactory;
    private final NotificationService notificationService;
    // Set after construction: the controls factory needs the mode controller, which is created with the
    // ChatWorkspaceHostPanel that this factory is passed into.
    private InteractionModeControlsFactory interactionModeControlsFactory;

    public WorkspaceHostContextFactory(File dataDirectory, UiExecutor uiExecutor, ThemeService themeService,
                                       MarkdownViewFactory markdownViewFactory,
                                       ConversationSurfaceFactory conversationSurfaceFactory,
                                       NotificationService notificationService) {
        this.dataDirectory = dataDirectory;
        this.uiExecutor = uiExecutor;
        this.themeService = themeService;
        this.markdownViewFactory = markdownViewFactory;
        this.conversationSurfaceFactory = conversationSurfaceFactory;
        this.notificationService = notificationService;
    }

    /** Wires the interaction-mode controls factory once the mode controller exists. */
    public void setInteractionModeControlsFactory(InteractionModeControlsFactory factory) {
        this.interactionModeControlsFactory = factory;
    }

    public WorkspaceHostContext create(String pluginId, String workspaceInstanceId) {
        ScopedPluginPathService pathService = new ScopedPluginPathService(dataDirectory, pluginId);
        File stateFile = new File(pathService.getWorkspaceDirectory(workspaceInstanceId), "workspace-state.properties");
        FileWorkspaceStateStore stateStore = new FileWorkspaceStateStore(stateFile);
        return new DefaultWorkspaceHostContext(uiExecutor, themeService, markdownViewFactory,
                conversationSurfaceFactory, interactionModeControlsFactory, stateStore, pathService,
                notificationService);
    }
}
