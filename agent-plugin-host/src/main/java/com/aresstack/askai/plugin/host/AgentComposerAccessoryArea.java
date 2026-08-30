package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContext;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the ACTIVE agent's composer accessories and places them above the active tab's composer (via
 * {@link ComposerAccessoryHost}), mirroring {@link AgentArtifactTabs}'s coordinator-driven lifecycle. On every
 * active-agent/session/tab change the coordinator fires; this rebuilds: it {@link ComposerAccessory#dispose()}s
 * the previous accessories and creates the new active agent's — so the host owns the lifecycle explicitly, with
 * no per-view AncestorListener. Phase changes WITHIN a session do not fire the coordinator, so an accessory
 * that manages its own per-phase visibility/state (e.g. the scoping controls) is not rebuilt and keeps its
 * local state. All work runs on the EDT via the {@link UiExecutor}.
 */
public final class AgentComposerAccessoryArea {

    private final AgentSessionCoordinator coordinator;
    private final UiExecutor uiExecutor;
    private final ThemeService themeService;
    private final MarkdownViewFactory markdownViewFactory;
    private final ComposerAccessoryHost host;
    private final List<ComposerAccessory> live = new ArrayList<ComposerAccessory>();

    public AgentComposerAccessoryArea(AgentSessionCoordinator coordinator, UiExecutor uiExecutor,
                                      ThemeService themeService, MarkdownViewFactory markdownViewFactory,
                                      ComposerAccessoryHost host) {
        this.coordinator = coordinator;
        this.uiExecutor = uiExecutor;
        this.themeService = themeService;
        this.markdownViewFactory = markdownViewFactory;
        this.host = host;
        coordinator.addChangeListener(new Runnable() {
            public void run() {
                AgentComposerAccessoryArea.this.uiExecutor.execute(new Runnable() {
                    public void run() {
                        rebuild();
                    }
                });
            }
        });
        rebuild();
    }

    private void rebuild() {
        disposeLive();
        host.setComposerPlaceholder(null); // a previous accessory's placeholder never survives a rebuild
        AgentSession session = coordinator.getActiveSession();
        if (session == null) {
            host.clearAccessory();
            host.clearBelowAccessory();
            return;
        }
        ComposerAccessoryContext context = new DefaultComposerAccessoryContext(
                session, uiExecutor, themeService, markdownViewFactory);
        JPanel aboveStack = newStack();
        JPanel belowStack = newStack();
        int above = 0;
        int below = 0;
        for (ComposerAccessoryContribution contribution : coordinator.getActiveComposerAccessories()) {
            if (contribution == null || !contribution.supports(session)) {
                continue;
            }
            ComposerAccessory accessory = contribution.create(context);
            if (accessory == null) {
                continue;
            }
            live.add(accessory);
            JComponent component = accessory.getComponent();
            if (component != null) {
                if (accessory.getPlacement() == ComposerAccessory.Placement.BELOW_COMPOSER) {
                    belowStack.add(component);
                    below++;
                } else {
                    aboveStack.add(component);
                    above++;
                }
            }
        }
        if (above == 0) {
            host.clearAccessory();
        } else {
            host.setAccessory(aboveStack);
        }
        if (below == 0) {
            host.clearBelowAccessory();
        } else {
            host.setBelowAccessory(belowStack);
        }
        for (ComposerAccessory accessory : live) {
            accessory.bindPlaceholderSink(new java.util.function.Consumer<String>() {
                public void accept(String placeholder) {
                    host.setComposerPlaceholder(placeholder);
                }
            });
        }
    }

    private static JPanel newStack() {
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        return stack;
    }

    private void disposeLive() {
        for (ComposerAccessory accessory : live) {
            try {
                accessory.dispose();
            } catch (RuntimeException ignored) {
                // one accessory's dispose must never block the others or the rebuild
            }
        }
        live.clear();
    }
}
