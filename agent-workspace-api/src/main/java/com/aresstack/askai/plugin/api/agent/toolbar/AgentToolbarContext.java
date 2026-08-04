package com.aresstack.askai.plugin.api.agent.toolbar;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;

/** Everything an {@link AgentToolbarContribution} needs to build its component for one active session. */
public interface AgentToolbarContext {

    AgentSession getSession();

    UiExecutor getUiExecutor();

    ThemeService getThemeService();
}
