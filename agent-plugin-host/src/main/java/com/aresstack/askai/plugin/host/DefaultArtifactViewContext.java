package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;

/** Immutable {@link ArtifactViewContext} the host builds for each artifact view in the shared area. */
final class DefaultArtifactViewContext implements ArtifactViewContext {

    private final AgentArtifact artifact;
    private final AgentSession session;
    private final UiExecutor uiExecutor;
    private final ThemeService themeService;
    private final MarkdownViewFactory markdownViewFactory;
    private final AgentOverlayHost overlayHost;

    DefaultArtifactViewContext(AgentArtifact artifact, AgentSession session, UiExecutor uiExecutor,
                               ThemeService themeService, MarkdownViewFactory markdownViewFactory) {
        this(artifact, session, uiExecutor, themeService, markdownViewFactory, null);
    }

    DefaultArtifactViewContext(AgentArtifact artifact, AgentSession session, UiExecutor uiExecutor,
                               ThemeService themeService, MarkdownViewFactory markdownViewFactory,
                               AgentOverlayHost overlayHost) {
        this.overlayHost = overlayHost;
        this.artifact = artifact;
        this.session = session;
        this.uiExecutor = uiExecutor;
        this.themeService = themeService;
        this.markdownViewFactory = markdownViewFactory;
    }

    public AgentArtifact getArtifact() {
        return artifact;
    }

    public AgentSession getSession() {
        return session;
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

    public AgentArtifactStore getArtifactStore() {
        return session == null ? null : session.getArtifactStore();
    }

    @Override
    public void showTranscriptOverlay(javax.swing.JComponent content, String title) {
        if (overlayHost != null) {
            overlayHost.showTranscriptOverlay(content, title);
        }
    }

    @Override
    public void showMainContent(javax.swing.JComponent content) {
        if (overlayHost != null && artifact != null) {
            overlayHost.setMainContent(artifact.getDisplayName(), content);
        }
    }

    @Override
    public void clearMainContent() {
        if (overlayHost != null && artifact != null) {
            overlayHost.setMainContent(artifact.getDisplayName(), null);
        }
    }

    @Override
    public void showDiagramOverlay(String mermaidSource, String title) {
        if (overlayHost != null) {
            overlayHost.showDiagramOverlay(mermaidSource, title);
        }
    }
}
