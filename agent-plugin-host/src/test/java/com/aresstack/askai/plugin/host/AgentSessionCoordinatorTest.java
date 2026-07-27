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
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The route matrix + session lifecycle, tested Swing-free with fake agent extensions/sessions. */
public class AgentSessionCoordinatorTest {

    private final Map<String, FakeExtension> registry = new HashMap<String, FakeExtension>();

    private AgentSessionCoordinator coordinator() {
        registry.put("agent.a", new FakeExtension("agent.a"));
        registry.put("agent.b", new FakeExtension("agent.b"));
        AgentSessionCoordinator.AgentExtensionResolver resolver =
                new AgentSessionCoordinator.AgentExtensionResolver() {
                    public AgentPluginExtension resolve(String agentId) {
                        return registry.get(agentId);
                    }
                };
        AgentSessionCoordinator.AgentHostContextProvider provider =
                new AgentSessionCoordinator.AgentHostContextProvider() {
                    public AgentHostContext create(String agentId, String sessionInstanceId) {
                        return null; // the fake session ignores its host context
                    }
                };
        return new AgentSessionCoordinator(resolver, provider);
    }

    @Test
    public void inactiveByDefault() {
        AgentSessionCoordinator c = coordinator();
        assertFalse(c.isActive());
        assertEquals(SubmissionAvailability.UNAVAILABLE, c.getAvailability());
    }

    @Test
    public void activatingCreatesAndActivatesExactlyOneSession() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        assertTrue(c.isActive());
        FakeSession session = registry.get("agent.a").lastSession;
        assertEquals(1, session.activateCount);
        assertEquals(1, registry.get("agent.a").created);
    }

    @Test
    public void returningToTheSameAgentReusesTheSession() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        FakeSession first = registry.get("agent.a").lastSession;
        c.deactivateActive();               // back to Yapping
        c.setActiveAgent("agent.a");        // back to Questing, same agent
        assertSame(first, registry.get("agent.a").lastSession);
        assertEquals(1, registry.get("agent.a").created); // not recreated
        assertEquals(0, first.closeCount);                // kept, not closed
    }

    @Test
    public void switchingAgentDeactivatesOldAndActivatesNewAtomically() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        FakeSession a = registry.get("agent.a").lastSession;
        c.setActiveAgent("agent.b");
        FakeSession b = registry.get("agent.b").lastSession;
        assertEquals(1, a.deactivateCount);
        assertEquals(0, a.closeCount);      // old session kept
        assertTrue(c.isActive());
        assertEquals(1, b.activateCount);
        assertEquals("agent.b", c.getActiveAgentId());
    }

    @Test
    public void deactivateRoutesBackToYappingButKeepsSession() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        c.deactivateActive();
        assertFalse(c.isActive());
        assertEquals(SubmissionAvailability.UNAVAILABLE, c.getAvailability());
        FakeSession a = registry.get("agent.a").lastSession;
        assertEquals(1, a.deactivateCount);
        assertEquals(0, a.closeCount);
    }

    @Test
    public void submitAndStopRouteToTheActiveTargetOnly() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        c.submitText("hello");
        c.stop();
        FakeSession a = registry.get("agent.a").lastSession;
        assertEquals(Collections.singletonList("hello"), a.target.submitted);
        assertEquals(1, a.target.stopCount);
    }

    @Test
    public void closeAgentClosesSessionAndFallsBack() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        FakeSession a = registry.get("agent.a").lastSession;
        c.closeAgent("agent.a");
        assertEquals(1, a.closeCount);
        assertFalse(c.isActive());
    }

    @Test
    public void retainOnlyClosesRemovedPluginsSessions() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        c.deactivateActive();
        c.setActiveAgent("agent.b");
        FakeSession a = registry.get("agent.a").lastSession;
        FakeSession b = registry.get("agent.b").lastSession;
        c.retainOnly(Arrays.asList("agent.b"));   // agent.a's plugin disabled
        assertEquals(1, a.closeCount);
        assertEquals(0, b.closeCount);
        assertTrue(c.isActive());                 // agent.b still active
    }

    @Test
    public void shutdownClosesEverything() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        c.deactivateActive();
        c.setActiveAgent("agent.b");
        c.shutdown();
        assertFalse(c.isActive());
        assertEquals(1, registry.get("agent.a").lastSession.closeCount);
        assertEquals(1, registry.get("agent.b").lastSession.closeCount);
    }

    @Test
    public void unresolvableAgentDeactivates() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        c.setActiveAgent("does.not.exist");
        assertFalse(c.isActive());
    }

    @Test
    public void availabilityComesFromTheActiveTarget() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        FakeSession a = registry.get("agent.a").lastSession;
        a.target.availability = SubmissionAvailability.BUSY;
        assertEquals(SubmissionAvailability.BUSY, c.getAvailability());
    }

    @Test
    public void changeListenerFiresOnActivationAndDeactivation() {
        AgentSessionCoordinator c = coordinator();
        final int[] count = {0};
        c.addChangeListener(new Runnable() {
            public void run() {
                count[0]++;
            }
        });
        c.setActiveAgent("agent.a");
        c.deactivateActive();
        assertTrue(count[0] >= 2);
    }

    // ------------------------------------------------------------------ fakes

    private static final class FakeExtension implements AgentPluginExtension {
        private final String id;
        int created;
        FakeSession lastSession;

        FakeExtension(String id) {
            this.id = id;
        }

        public AgentPluginDescriptor getAgentDescriptor() {
            return AgentPluginDescriptor.builder().id(id).displayName(id).version("1").build();
        }

        public AgentSessionFactory getSessionFactory() {
            return new AgentSessionFactory() {
                public AgentSession create(AgentSessionCreationRequest request, AgentHostContext hostContext) {
                    created++;
                    lastSession = new FakeSession();
                    return lastSession;
                }
            };
        }

        public List<ChatCommandContribution> getChatCommands() {
            return Collections.emptyList();
        }

        public List<ArtifactViewContribution> getArtifactViews() {
            return Collections.emptyList();
        }
    }

    private static final class FakeSession implements AgentSession {
        final FakeTarget target = new FakeTarget();
        int activateCount;
        int deactivateCount;
        int closeCount;

        public ChatSubmissionTarget getChatTarget() {
            return target;
        }

        public List<AgentArtifact> getArtifacts() {
            return Collections.emptyList();
        }

        public AgentStateSnapshot getState() {
            return AgentStateSnapshot.builder().build();
        }

        public void activate() {
            activateCount++;
        }

        public void deactivate() {
            deactivateCount++;
        }

        public void close() {
            closeCount++;
        }
    }

    private static final class FakeTarget implements ChatSubmissionTarget {
        final List<String> submitted = new ArrayList<String>();
        int stopCount;
        SubmissionAvailability availability = SubmissionAvailability.AVAILABLE;

        public SubmissionAvailability getAvailability() {
            return availability;
        }

        public void submitText(String text) {
            submitted.add(text);
        }

        public void stop() {
            stopCount++;
        }
    }
}
