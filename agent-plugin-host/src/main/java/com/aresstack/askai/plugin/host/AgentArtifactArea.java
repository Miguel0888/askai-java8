package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The optional, collapsible artifact area shown beside the SHARED chat when an agent is active. Its tabs come
 * from the active session's {@link AgentArtifact}s: {@code "markdown"} artifacts use the host's default
 * {@link HostMarkdownArtifactView}; structured artifacts use the agent's own
 * {@link ArtifactViewContribution}. It never replaces the chat — closing it leaves AskAI looking like the
 * normal chat. Rebuilt on every coordinator change so a plugin disable / agent switch removes stale views.
 */
public final class AgentArtifactArea extends JPanel {

    /** Well-known generic type id for plain Markdown artifacts (host default view). */
    public static final String MARKDOWN_TYPE_ID = "markdown";

    private final AgentSessionCoordinator coordinator;
    private final UiExecutor uiExecutor;
    private final ThemeService themeService;
    private final MarkdownViewFactory markdownViewFactory;
    private final Runnable onRevealRequested;
    private final Runnable onEmpty;

    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<String, Integer> indexById = new HashMap<String, Integer>();

    public AgentArtifactArea(AgentSessionCoordinator coordinator, UiExecutor uiExecutor,
                             ThemeService themeService, MarkdownViewFactory markdownViewFactory,
                             Runnable onRevealRequested, Runnable onEmpty) {
        super(new BorderLayout());
        this.coordinator = coordinator;
        this.uiExecutor = uiExecutor;
        this.themeService = themeService;
        this.markdownViewFactory = markdownViewFactory;
        this.onRevealRequested = onRevealRequested;
        this.onEmpty = onEmpty;
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel header = new JPanel(new BorderLayout());
        header.add(new JLabel("Artifacts"), BorderLayout.WEST);
        JButton hide = new JButton("Hide");
        hide.addActionListener(e -> {
            if (onEmpty != null) {
                onEmpty.run();
            }
        });
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        headerRight.add(hide);
        header.add(headerRight, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);

        coordinator.addChangeListener(new Runnable() {
            public void run() {
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        rebuild();
                    }
                });
            }
        });
        rebuild();
    }

    /** Visible for tests: the number of artifact tabs currently built. */
    int tabCount() {
        return tabs.getTabCount();
    }

    /** @return whether the active session currently contributes any artifacts. */
    public boolean hasArtifacts() {
        AgentSession session = coordinator.getActiveSession();
        return session != null && !session.getArtifacts().isEmpty();
    }

    /** Select the tab for {@code artifactId} and ask the host to make the area visible. */
    public void revealArtifact(String artifactId) {
        Integer index = indexById.get(artifactId);
        if (index != null) {
            tabs.setSelectedIndex(index.intValue());
        }
        if (onRevealRequested != null) {
            onRevealRequested.run();
        }
    }

    private void rebuild() {
        tabs.removeAll();
        indexById.clear();
        AgentSession session = coordinator.getActiveSession();
        if (session == null || session.getArtifacts().isEmpty()) {
            if (onEmpty != null) {
                onEmpty.run(); // nothing to show → the host collapses the area
            }
            revalidate();
            repaint();
            return;
        }
        List<ArtifactViewContribution> contributions = coordinator.getActiveArtifactViews();
        int index = 0;
        for (AgentArtifact artifact : session.getArtifacts()) {
            JComponent view = buildView(artifact, session, contributions);
            tabs.addTab(artifact.getDisplayName(), view);
            indexById.put(artifact.getId(), Integer.valueOf(index));
            index++;
        }
        revalidate();
        repaint();
    }

    private JComponent buildView(AgentArtifact artifact, AgentSession session,
                                 List<ArtifactViewContribution> contributions) {
        ArtifactViewContext context = new DefaultArtifactViewContext(
                artifact, session, uiExecutor, themeService, markdownViewFactory);
        if (MARKDOWN_TYPE_ID.equals(artifact.getArtifactTypeId())) {
            return new HostMarkdownArtifactView(context);
        }
        for (ArtifactViewContribution contribution : contributions) {
            if (artifact.getArtifactTypeId().equals(contribution.getArtifactTypeId())) {
                JComponent view = contribution.createView(context);
                if (view != null) {
                    return view;
                }
            }
        }
        JPanel placeholder = new JPanel(new BorderLayout());
        placeholder.add(new JLabel("No view for artifact type: " + artifact.getArtifactTypeId()),
                BorderLayout.NORTH);
        return placeholder;
    }
}
