package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContext;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;

/** Immutable {@link ComposerAccessoryContext} the host builds for the active session's composer accessories. */
final class DefaultComposerAccessoryContext implements ComposerAccessoryContext {

    private final AgentSession session;
    private final UiExecutor uiExecutor;
    private final ThemeService themeService;
    private final MarkdownViewFactory markdownViewFactory;

    DefaultComposerAccessoryContext(AgentSession session, UiExecutor uiExecutor, ThemeService themeService,
                                    MarkdownViewFactory markdownViewFactory) {
        this.session = session;
        this.uiExecutor = uiExecutor;
        this.themeService = themeService;
        this.markdownViewFactory = markdownViewFactory;
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
}
