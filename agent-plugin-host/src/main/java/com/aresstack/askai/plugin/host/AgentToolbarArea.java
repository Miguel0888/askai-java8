package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContext;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Builds the ACTIVE agent's top-bar controls and places them left of the gear (via {@link AgentToolbarHost}),
 * mirroring {@link AgentComposerAccessoryArea}'s coordinator-driven lifecycle: on every active
 * agent/session/tab change this rebuilds — plain components, no dispose contract (a control that needs
 * teardown listens for its own removal). All work runs on the EDT via the {@link UiExecutor}.
 */
public final class AgentToolbarArea {

    private final AgentSessionCoordinator coordinator;
    private final UiExecutor uiExecutor;
    private final ThemeService themeService;
    private final AgentToolbarHost host;

    public AgentToolbarArea(AgentSessionCoordinator coordinator, UiExecutor uiExecutor,
                            ThemeService themeService, AgentToolbarHost host) {
        this.coordinator = coordinator;
        this.uiExecutor = uiExecutor;
        this.themeService = themeService;
        this.host = host;
        coordinator.addChangeListener(new Runnable() {
            public void run() {
                AgentToolbarArea.this.uiExecutor.execute(new Runnable() {
                    public void run() {
                        rebuild();
                    }
                });
            }
        });
        rebuild();
    }

    private void rebuild() {
        final AgentSession session = coordinator.getActiveSession();
        if (session == null) {
            host.clearToolbar();
            host.clearCenterToolbar();
            return;
        }
        AgentToolbarContext context = new AgentToolbarContext() {
            public AgentSession getSession() {
                return session;
            }

            public UiExecutor getUiExecutor() {
                return uiExecutor;
            }

            public ThemeService getThemeService() {
                return themeService;
            }
        };
        JPanel trailingRow = newRow();
        JPanel centerRow = newRow();
        int trailingBuilt = 0;
        int centerBuilt = 0;
        for (AgentToolbarContribution contribution : coordinator.getActiveToolbarContributions()) {
            if (contribution == null || !contribution.supports(session)) {
                continue;
            }
            JComponent component = contribution.createComponent(context);
            if (component == null) {
                continue;
            }
            if (contribution.getPlacement() == AgentToolbarContribution.Placement.CENTER) {
                centerRow.add(component);
                centerBuilt++;
            } else {
                trailingRow.add(component);
                trailingBuilt++;
            }
        }
        if (trailingBuilt == 0) {
            host.clearToolbar();
        } else {
            host.setToolbar(trailingRow);
        }
        if (centerBuilt == 0) {
            host.clearCenterToolbar();
        } else {
            host.setCenterToolbar(centerRow);
        }
    }

    private static JPanel newRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        return row;
    }
}
