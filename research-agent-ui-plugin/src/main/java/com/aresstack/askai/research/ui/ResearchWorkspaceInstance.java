package com.aresstack.askai.research.ui;

import com.aresstack.askai.plugin.api.WorkspaceCreationRequest;
import com.aresstack.askai.plugin.api.lifecycle.WorkspaceCloseCallback;
import com.aresstack.askai.plugin.api.lifecycle.WorkspaceInstance;
import com.aresstack.askai.plugin.api.service.ConversationSurface;
import com.aresstack.askai.plugin.api.service.ConversationSurfaceOptions;
import com.aresstack.askai.plugin.api.service.InteractionModeControls;
import com.aresstack.askai.plugin.api.service.InteractionModeControlsOptions;
import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceHostContext;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.plugin.api.ui.WorkspaceLayoutContribution;
import com.aresstack.askai.plugin.api.ui.WorkspaceLayoutHints;

import javax.swing.JComponent;
import java.util.Optional;

/**
 * One opened research workspace: its own panels, view-models, controller and host-service views. Nothing is
 * static or shared between instances. All host UI (Markdown/Mermaid, conversation surface, interaction-mode
 * controls) comes from the host context; there are no {@code askai-app} imports.
 */
public final class ResearchWorkspaceInstance implements WorkspaceInstance {

    private static final String KEY_NAV_WIDTH = "research.navWidth";
    private static final String KEY_ACTIVITY_WIDTH = "research.activityWidth";

    private final UiExecutor uiExecutor;
    private final com.aresstack.askai.plugin.api.service.ThemeService themeService;
    private final WorkspaceStateStore stateStore;

    private final ResearchWorkspaceController controller;
    private final MarkdownView markdownView;
    private final ConversationSurface conversation;
    private final InteractionModeControls modeControls;

    private final ResearchToolbarView toolbar;
    private final ResearchOutlineView outline;
    private final ResearchMainView main;
    private final ResearchComposerView composer;
    private final Layout layout;

    private final Runnable controllerListener;
    private final Runnable themeListener;
    private boolean themeListenerRegistered;
    private boolean disposed;

    public ResearchWorkspaceInstance(WorkspaceCreationRequest request, WorkspaceHostContext host) {
        this.uiExecutor = host.getUiExecutor();
        this.themeService = host.getThemeService();
        this.stateStore = host.getWorkspaceStateStore();

        this.controller = new ResearchWorkspaceController(request.getWorkspaceInstanceId());
        this.markdownView = host.getMarkdownViewFactory().create(MarkdownViewOptions.defaults());
        this.conversation = host.getConversationSurfaceFactory().create(ConversationSurfaceOptions.defaults());
        this.modeControls = host.getInteractionModeControlsFactory()
                .create(InteractionModeControlsOptions.defaults());

        this.toolbar = new ResearchToolbarView(controller);
        this.outline = new ResearchOutlineView(controller);
        this.main = new ResearchMainView(controller, markdownView);
        this.composer = new ResearchComposerView(controller, modeControls.getComponent(), conversation);
        this.layout = new Layout();

        this.controllerListener = new Runnable() {
            public void run() {
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        refreshViews();
                    }
                });
            }
        };
        controller.addChangeListener(controllerListener);

        this.themeListener = new Runnable() {
            public void run() {
                if (!disposed) {
                    outline.repaint();
                    main.repaint();
                }
            }
        };

        seedDemoActivity();
    }

    private void seedDemoActivity() {
        // Static demo activity only (no timing; the event-driven run arrives in Commit 8).
        conversation.addUserMessage("m0", "Research the plugin architecture.");
        conversation.addAssistantMessage("a0", "I will scope the work and propose an outline.");
        conversation.startToolActivity("t0", "Search web", "Looking up PF4J and Solon AI");
        conversation.completeToolActivity("t0", "Captured 3 sources");
    }

    private void refreshViews() {
        toolbar.refresh();
        outline.refresh();
        main.refresh();
        composer.refresh();
    }

    @Override
    public WorkspaceLayoutContribution getLayout() {
        return layout;
    }

    @Override
    public void activate() {
        if (disposed) {
            return;
        }
        if (!themeListenerRegistered) {
            themeService.addThemeChangeListener(themeListener);
            themeListenerRegistered = true;
        }
        refreshViews();
    }

    @Override
    public void deactivate() {
        // Keep all state; no disposal here.
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public void requestClose(WorkspaceCloseCallback callback) {
        callback.allowClose();
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        controller.removeChangeListener(controllerListener);
        if (themeListenerRegistered) {
            themeService.removeThemeChangeListener(themeListener);
            themeListenerRegistered = false;
        }
        modeControls.dispose();
        markdownView.dispose();
        conversation.dispose();
    }

    private int navWidth() {
        return stateStore == null ? 260 : stateStore.getInt(KEY_NAV_WIDTH, 260);
    }

    private int activityWidth() {
        return stateStore == null ? 320 : stateStore.getInt(KEY_ACTIVITY_WIDTH, 320);
    }

    /** Persists the current divider-derived widths (called by the host/UI when the user resizes). */
    public void persistLayoutWidths(int navigationWidth, int activityWidth) {
        if (stateStore != null) {
            stateStore.putInt(KEY_NAV_WIDTH, navigationWidth);
            stateStore.putInt(KEY_ACTIVITY_WIDTH, activityWidth);
        }
    }

    /** The five structured slots handed to the host. */
    private final class Layout implements WorkspaceLayoutContribution {
        @Override
        public Optional<JComponent> getToolbar() {
            return Optional.<JComponent>of(toolbar);
        }

        @Override
        public Optional<JComponent> getNavigation() {
            return Optional.<JComponent>of(outline);
        }

        @Override
        public JComponent getMainContent() {
            return main;
        }

        @Override
        public Optional<JComponent> getActivity() {
            return Optional.<JComponent>of(conversation.getComponent());
        }

        @Override
        public Optional<JComponent> getComposer() {
            return Optional.<JComponent>of(composer);
        }

        @Override
        public WorkspaceLayoutHints getLayoutHints() {
            return WorkspaceLayoutHints.builder()
                    .preferredNavigationWidth(navWidth())
                    .preferredActivityWidth(activityWidth())
                    .minimumMainWidth(360)
                    .navigationInitiallyVisible(true)
                    .activityInitiallyVisible(true)
                    .build();
        }
    }
}
