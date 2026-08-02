package com.aresstack.askai.plugin.api.agent.composer;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;

/** Everything a {@link ComposerAccessoryContribution} needs to build its accessory for one active session. */
public interface ComposerAccessoryContext {

    AgentSession getSession();

    UiExecutor getUiExecutor();

    ThemeService getThemeService();

    /** Host Markdown/Mermaid view factory — so the accessory reuses the host renderer, never its own. */
    MarkdownViewFactory getMarkdownViewFactory();
}
