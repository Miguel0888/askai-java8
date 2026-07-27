package com.aresstack.askai.plugin.api.agent.artifact;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;

/** Everything an {@link ArtifactViewContribution} needs to build a view for one artifact instance. */
public interface ArtifactViewContext {

    AgentArtifact getArtifact();

    AgentSession getSession();

    UiExecutor getUiExecutor();

    ThemeService getThemeService();

    /** Host Markdown editor/viewer factory for building the default Markdown artifact view. */
    MarkdownViewFactory getMarkdownViewFactory();

    /** The store to read/write artifact content through, or {@code null} if the session has none. */
    AgentArtifactStore getArtifactStore();
}
