package com.aresstack.askai.plugin.api.agent.toolbar;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;

/** Everything an {@link AgentToolbarContribution} needs to build its component for one active session. */
public interface AgentToolbarContext {

    AgentSession getSession();

    UiExecutor getUiExecutor();

    ThemeService getThemeService();

    /**
     * Show content as a closable OVERLAY over the active chat's transcript (e.g. a generated
     * diagram). The host owns backdrop, plate and close control. Default: no-op.
     */
    default void showTranscriptOverlay(javax.swing.JComponent content, String title) {
    }
}
