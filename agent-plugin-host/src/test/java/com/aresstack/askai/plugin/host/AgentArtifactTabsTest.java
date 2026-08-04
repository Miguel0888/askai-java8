package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.agent.AgentSessionFactory;
import com.aresstack.askai.plugin.api.agent.AgentStateSnapshot;
import com.aresstack.askai.plugin.api.agent.ChatSubmissionTarget;
import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;

import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The sidebar-tab provider builds one tab per artifact of the active session and clears on switch. */
public class AgentArtifactTabsTest {

    private final Map<String, AgentPluginExtension> registry = new HashMap<String, AgentPluginExtension>();
    private int changeCount;

    private AgentSessionCoordinator coordinator() {
        registry.put("agent.a", new FakeExtension(Arrays.asList(
                artifact("outline", "Outline", AgentArtifactTabs.MARKDOWN_TYPE_ID),
                artifact("state", "State", "custom.state"))));
        registry.put("agent.b", new FakeExtension(Collections.singletonList(
                artifact("notes", "Notes", AgentArtifactTabs.MARKDOWN_TYPE_ID))));
        AgentSessionCoordinator.AgentExtensionResolver resolver =
                new AgentSessionCoordinator.AgentExtensionResolver() {
                    public AgentPluginExtension resolve(String agentId) {
                        return registry.get(agentId);
                    }
                };
        AgentSessionCoordinator.AgentHostContextProvider provider =
                new AgentSessionCoordinator.AgentHostContextProvider() {
                    public AgentHostContext create(String agentId, String sessionInstanceId) {
                        return null;
                    }
                };
        return new AgentSessionCoordinator(resolver, provider, new InlineUiExecutor());
    }

    private AgentArtifactTabs tabs(AgentSessionCoordinator coordinator) {
        AgentArtifactTabs tabs = new AgentArtifactTabs(coordinator, new InlineUiExecutor(), null, null);
        tabs.addChangeListener(new Runnable() {
            public void run() {
                changeCount++;
            }
        });
        return tabs;
    }

    @Test
    public void emptyWhenNoAgentIsActive() {
        AgentSessionCoordinator c = coordinator();
        AgentArtifactTabs tabs = tabs(c);
        assertTrue(tabs.tabs().isEmpty());
    }

    @Test
    public void buildsOneTitledTabPerArtifactOfTheActiveAgent() {
        AgentSessionCoordinator c = coordinator();
        AgentArtifactTabs tabs = tabs(c);
        c.setActiveAgent("agent.a");
        assertEquals(2, tabs.tabs().size());
        assertEquals("Outline", tabs.tabs().get(0).getTitle());
        assertEquals("State", tabs.tabs().get(1).getTitle());
        assertTrue("a rebuild notified the sidebar", changeCount >= 1);
    }

    @Test
    public void viewsAreCachedBetweenReadsSoEditingStateSurvivesDrawerToggles() {
        AgentSessionCoordinator c = coordinator();
        AgentArtifactTabs tabs = tabs(c);
        c.setActiveAgent("agent.a");
        assertTrue("same component instance until the next coordinator change",
                tabs.tabs().get(0).getComponent() == tabs.tabs().get(0).getComponent());
    }

    @Test
    public void tabForArtifactResolvesByIdForTheOpenReveal() {
        AgentSessionCoordinator c = coordinator();
        AgentArtifactTabs tabs = tabs(c);
        c.setActiveAgent("agent.a");
        assertEquals("State", tabs.tabForArtifact("state").getTitle());
        assertTrue(tabs.tabForArtifact("nope") == null);
    }

    @Test
    public void switchingAgentReplacesTheTabs() {
        AgentSessionCoordinator c = coordinator();
        AgentArtifactTabs tabs = tabs(c);
        c.setActiveAgent("agent.a");
        assertEquals(2, tabs.tabs().size());
        c.setActiveAgent("agent.b");
        assertEquals(1, tabs.tabs().size());
        assertEquals("Notes", tabs.tabs().get(0).getTitle());
    }

    @Test
    public void deactivatingClearsTheTabs() {
        AgentSessionCoordinator c = coordinator();
        AgentArtifactTabs tabs = tabs(c);
        c.setActiveAgent("agent.a");
        c.deactivateActive();
        assertTrue(tabs.tabs().isEmpty());
    }

    @Test
    public void readsRefreshThemselvesEvenWhenTheChangeEventWasMissed() {
        // Simulate an event race (startup ordering): the provider's executor DROPS the rebuild event.
        // A later read must still deliver the active session's tabs instead of a stale empty list.
        AgentSessionCoordinator c = coordinator();
        AgentArtifactTabs lateReader = new AgentArtifactTabs(c, new DroppingUiExecutor(), null, null);
        c.setActiveAgent("agent.a"); // its change event is swallowed by the executor
        assertEquals("the read repaired the missed event", 2, lateReader.tabs().size());
        assertEquals("Outline", lateReader.tabs().get(0).getTitle());
    }

    @Test
    public void markdownArtifactViewReloadsAlreadyOpenContentOnSessionChange() {
        MutableStore store = new MutableStore("# Outline\n\nNoch keine verarbeiteten Recherchequellen.", 1L);
        ObservableSession session = new ObservableSession(
                Collections.singletonList(artifact("outline", "Outline", AgentArtifactTabs.MARKDOWN_TYPE_ID)),
                store);
        HostMarkdownArtifactView view = new HostMarkdownArtifactView(new DefaultArtifactViewContext(
                session.getArtifacts().get(0), session, new InlineUiExecutor(), null, null));

        assertTrue(textAreaIn(view).getText().contains("Noch keine verarbeiteten"));
        store.force("# Outline\n\n## Wearable health monitoring\n- Sensors", 2L);
        session.fire();

        assertTrue(textAreaIn(view).getText().contains("Wearable health monitoring"));
    }

    @Test
    public void markdownArtifactViewUnregistersWhenTabSetIsRebuilt() {
        MutableStore store = new MutableStore("# one", 1L);
        ObservableSession session = new ObservableSession(
                Collections.singletonList(artifact("outline", "Outline", AgentArtifactTabs.MARKDOWN_TYPE_ID)),
                store);
        HostMarkdownArtifactView view = new HostMarkdownArtifactView(new DefaultArtifactViewContext(
                session.getArtifacts().get(0), session, new InlineUiExecutor(), null, null));
        assertEquals(1, session.listenerCount());

        view.close();

        assertEquals(0, session.listenerCount());
    }

    // ------------------------------------------------------------------ fakes

    /** Swallows every posted runnable — models a change event that never reaches the provider. */
    private static final class DroppingUiExecutor implements UiExecutor {
        public boolean isUiThread() {
            return true;
        }

        public void execute(Runnable runnable) {
            // dropped on purpose
        }

        public void assertUiThread() {
        }
    }

    private static AgentArtifact artifact(String id, String name, String type) {
        return new AgentArtifact() {
            public String getId() {
                return id;
            }

            public String getDisplayName() {
                return name;
            }

            public String getArtifactTypeId() {
                return type;
            }

            public String getRelativePath() {
                return "";
            }

            public long getRevision() {
                return 0L;
            }
        };
    }

    private static JTextArea textAreaIn(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JTextArea) {
                return (JTextArea) component;
            }
            if (component instanceof Container) {
                JTextArea nested = textAreaIn((Container) component);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new AssertionError("No JTextArea found");
    }

    private static final class InlineUiExecutor implements UiExecutor {
        public boolean isUiThread() {
            return true;
        }

        public void execute(Runnable runnable) {
            runnable.run();
        }

        public void assertUiThread() {
        }
    }

    private static final class FakeStore implements AgentArtifactStore {
        public ArtifactContent read(String artifactId) {
            return new ArtifactContent("# " + artifactId, 1L);
        }

        public ArtifactWriteResult replace(String artifactId, long expectedRevision, String markdown) {
            return ArtifactWriteResult.ok(expectedRevision + 1);
        }
    }

    private static final class MutableStore implements AgentArtifactStore {
        private String markdown;
        private long revision;

        MutableStore(String markdown, long revision) {
            this.markdown = markdown;
            this.revision = revision;
        }

        public ArtifactContent read(String artifactId) {
            return new ArtifactContent(markdown, revision);
        }

        public ArtifactWriteResult replace(String artifactId, long expectedRevision, String markdown) {
            if (expectedRevision != revision) {
                return ArtifactWriteResult.conflict(this.markdown, revision);
            }
            force(markdown, revision + 1L);
            return ArtifactWriteResult.ok(revision);
        }

        void force(String markdown, long revision) {
            this.markdown = markdown;
            this.revision = revision;
        }
    }

    private static final class FakeExtension implements AgentPluginExtension {
        private final List<AgentArtifact> artifacts;

        FakeExtension(List<AgentArtifact> artifacts) {
            this.artifacts = artifacts;
        }

        public AgentPluginDescriptor getAgentDescriptor() {
            return AgentPluginDescriptor.builder().id("a.b").displayName("A").version("1").build();
        }

        public AgentSessionFactory getSessionFactory() {
            return new AgentSessionFactory() {
                public AgentSession create(AgentSessionCreationRequest request, AgentHostContext hostContext) {
                    return new FakeSession(artifacts);
                }
            };
        }

        public List<ChatCommandContribution> getChatCommands() {
            return Collections.emptyList();
        }

        public List<ArtifactViewContribution> getArtifactViews() {
            return Collections.<ArtifactViewContribution>singletonList(new CustomStateView());
        }
    }

    /** A specialized view for the non-markdown "custom.state" artifact. */
    private static final class CustomStateView implements ArtifactViewContribution {
        public String getArtifactTypeId() {
            return "custom.state";
        }

        public String getDisplayName() {
            return "State";
        }

        public JComponent createView(ArtifactViewContext context) {
            JPanel panel = new JPanel();
            panel.add(new JLabel("state: " + context.getArtifact().getId()));
            return panel;
        }
    }

    private static final class FakeSession implements AgentSession {
        private final List<AgentArtifact> artifacts;
        private final AgentArtifactStore store = new FakeStore();

        FakeSession(List<AgentArtifact> artifacts) {
            this.artifacts = artifacts;
        }

        public ChatSubmissionTarget getChatTarget() {
            return new ChatSubmissionTarget() {
                public SubmissionAvailability getAvailability() {
                    return SubmissionAvailability.AVAILABLE;
                }

                public void submitText(String text) {
                }

                public void stop() {
                }
            };
        }

        public List<AgentArtifact> getArtifacts() {
            return artifacts;
        }

        public AgentArtifactStore getArtifactStore() {
            return store;
        }

        public AgentStateSnapshot getState() {
            return AgentStateSnapshot.builder().build();
        }

        public void activate() {
        }

        public void deactivate() {
        }

        public void close() {
        }
    }

    private static final class ObservableSession implements AgentSession {
        private final List<AgentArtifact> artifacts;
        private final AgentArtifactStore store;
        private final List<Runnable> listeners = new ArrayList<Runnable>();

        ObservableSession(List<AgentArtifact> artifacts, AgentArtifactStore store) {
            this.artifacts = artifacts;
            this.store = store;
        }

        public ChatSubmissionTarget getChatTarget() {
            return new ChatSubmissionTarget() {
                public SubmissionAvailability getAvailability() {
                    return SubmissionAvailability.AVAILABLE;
                }

                public void submitText(String text) {
                }

                public void stop() {
                }
            };
        }

        public List<AgentArtifact> getArtifacts() {
            return artifacts;
        }

        public AgentArtifactStore getArtifactStore() {
            return store;
        }

        public AgentStateSnapshot getState() {
            return AgentStateSnapshot.builder().build();
        }

        public void addStateListener(Runnable listener) {
            listeners.add(listener);
        }

        public void removeStateListener(Runnable listener) {
            listeners.remove(listener);
        }

        void fire() {
            for (Runnable listener : new ArrayList<Runnable>(listeners)) {
                listener.run();
            }
        }

        int listenerCount() {
            return listeners.size();
        }

        public void activate() {
        }

        public void deactivate() {
        }

        public void close() {
        }
    }
}
