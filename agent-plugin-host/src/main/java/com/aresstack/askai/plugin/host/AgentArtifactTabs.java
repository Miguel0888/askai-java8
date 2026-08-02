package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ACTIVE agent's artifact views as sidebar-tab material (successor of the removed right-hand
 * {@code AgentArtifactArea}): one {@link Tab} per {@link AgentArtifact} of the active session —
 * {@code "markdown"} artifacts use the host's default {@link HostMarkdownArtifactView}, structured
 * artifacts the agent's own {@link ArtifactViewContribution}. The frame injects these tabs into the
 * LEFT chat sidebar, so they exist exactly while an agent (e.g. Research in Questing) is active and
 * vanish otherwise. Views are built ONCE per coordinator change and then reused, so editing state
 * survives drawer open/close; a rebuild on every coordinator change drops stale views on plugin
 * disable / agent switch. All rebuilds run on the EDT.
 */
public final class AgentArtifactTabs {

    /** Well-known generic type id for plain Markdown artifacts (host default view). */
    public static final String MARKDOWN_TYPE_ID = "markdown";

    /** One sidebar tab: the artifact id (for /open reveals), its display title and the cached view. */
    public static final class Tab {
        private final String artifactId;
        private final String title;
        private final JComponent component;

        Tab(String artifactId, String title, JComponent component) {
            this.artifactId = artifactId;
            this.title = title;
            this.component = component;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getTitle() {
            return title;
        }

        public JComponent getComponent() {
            return component;
        }
    }

    private final AgentSessionCoordinator coordinator;
    private final UiExecutor uiExecutor;
    private final ThemeService themeService;
    private final MarkdownViewFactory markdownViewFactory;
    private final List<Runnable> changeListeners = new ArrayList<Runnable>();

    private List<Tab> tabs = Collections.emptyList();
    private AgentSession builtFor; // the session the cached tabs belong to (identity)

    public AgentArtifactTabs(AgentSessionCoordinator coordinator, UiExecutor uiExecutor,
                             ThemeService themeService, MarkdownViewFactory markdownViewFactory) {
        this.coordinator = coordinator;
        this.uiExecutor = uiExecutor;
        this.themeService = themeService;
        this.markdownViewFactory = markdownViewFactory;
        coordinator.addChangeListener(new Runnable() {
            public void run() {
                AgentArtifactTabs.this.uiExecutor.execute(new Runnable() {
                    public void run() {
                        rebuild();
                    }
                });
            }
        });
        rebuild();
    }

    /** Notified (on the EDT) after the tab set was rebuilt, so the sidebar can refresh in place. */
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    /**
     * The current tabs — empty when no agent session is active (then the sidebar shows none). Reads
     * REFRESH themselves against the live active session, so a drawer opened after an event race
     * (startup ordering, tab switch) never sees stale or missing tabs.
     */
    public List<Tab> tabs() {
        AgentSession current = coordinator.getActiveSession();
        if (current != builtFor) {
            rebuildFor(current); // read-time refresh; no change event (the reader is already reading)
        }
        return tabs;
    }

    /** The tab for this artifact id, or null (used by the /open reveal). */
    public Tab tabForArtifact(String artifactId) {
        for (Tab tab : tabs) {
            if (tab.getArtifactId().equals(artifactId)) {
                return tab;
            }
        }
        return null;
    }

    private void rebuild() {
        rebuildFor(coordinator.getActiveSession());
        fireChanged();
    }

    private void rebuildFor(AgentSession session) {
        builtFor = session;
        if (session == null || session.getArtifacts().isEmpty()) {
            tabs = Collections.emptyList();
            return;
        }
        List<ArtifactViewContribution> contributions = coordinator.getActiveArtifactViews();
        List<Tab> rebuilt = new ArrayList<Tab>();
        for (AgentArtifact artifact : session.getArtifacts()) {
            rebuilt.add(new Tab(artifact.getId(), artifact.getDisplayName(),
                    buildView(artifact, session, contributions)));
        }
        tabs = Collections.unmodifiableList(rebuilt);
    }

    private void fireChanged() {
        for (Runnable listener : new ArrayList<Runnable>(changeListeners)) {
            listener.run();
        }
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
