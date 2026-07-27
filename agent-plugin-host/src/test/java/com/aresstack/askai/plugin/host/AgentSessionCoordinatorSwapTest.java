package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionContext;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.agent.AgentSessionFactory;
import com.aresstack.askai.plugin.api.agent.AgentStateSnapshot;
import com.aresstack.askai.plugin.api.agent.ChatSubmissionTarget;
import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Hardening of the generation swap at the coordinator: a session whose {@code close()} throws is retained for a
 * later retry (its reference is never dropped as a mere failure string), so a live object is never orphaned while
 * its plugin classloader is (correctly) still loaded.
 */
public class AgentSessionCoordinatorSwapTest {

    @Test
    public void failedCloseRetainsSessionAndRetriesOnNextDetach() {
        FakeExtension ext = new FakeExtension("agent.a");
        AgentSessionCoordinator c = coordinator(ext);
        c.setActiveAgent("agent.a");
        FakeSession first = ext.lastSession;
        first.failClose = true;

        SessionCloseResult r1 = c.detachOutgoing().closeAll();
        assertFalse("a failing close is not successful", r1.isSuccessful());
        assertEquals(1, first.closeAttempts);
        assertEquals("the unclosed session must be retained, not dropped", 1, c.getUnclosedSessionCount());

        // A new generation's session plus the retained one are closed on the next detach; close now succeeds.
        first.failClose = false;
        c.setActiveAgent("agent.a");
        FakeSession second = ext.lastSession;
        assertFalse(second == first);

        SessionCloseResult r2 = c.detachOutgoing().closeAll();
        assertTrue(r2.isSuccessful());
        assertEquals("the retained session is retried", 2, first.closeAttempts);
        assertEquals(1, second.closeAttempts);
        assertEquals(0, c.getUnclosedSessionCount());
    }

    @Test
    public void shutdownClosesRetainedUnclosedSessions() {
        FakeExtension ext = new FakeExtension("agent.a");
        AgentSessionCoordinator c = coordinator(ext);
        c.setActiveAgent("agent.a");
        FakeSession first = ext.lastSession;
        first.failClose = true;
        c.detachOutgoing().closeAll(); // fails, retained
        assertEquals(1, c.getUnclosedSessionCount());

        first.failClose = false;
        c.shutdown();
        assertEquals("shutdown retries the retained session", 2, first.closeAttempts);
        assertEquals(0, c.getUnclosedSessionCount());
    }

    @Test
    public void aBrokenChangeListenerDoesNotAbortDetachOrLoseSessions() {
        FakeExtension ext = new FakeExtension("agent.a");
        AgentSessionCoordinator c = coordinator(ext);
        final int[] goodListenerCalls = {0};
        c.addChangeListener(new Runnable() {
            public void run() {
                throw new RuntimeException("broken UI listener");
            }
        });
        c.addChangeListener(new Runnable() {
            public void run() {
                goodListenerCalls[0]++;
            }
        });
        c.setActiveAgent("agent.a");
        FakeSession session = ext.lastSession;

        // detach fires a change (the broken listener throws): it must still return a handle and keep the session.
        GenerationSwapHook.OutgoingSessions outgoing = c.detachOutgoing();
        assertNotNull("detach must not be turned into a failure by a broken listener", outgoing);
        SessionCloseResult result = outgoing.closeAll();
        assertTrue(result.isSuccessful());
        assertEquals(1, session.closeAttempts);
        assertEquals(0, c.getUnclosedSessionCount());
        assertTrue("the other listener still ran despite the broken one", goodListenerCalls[0] >= 1);
    }

    private static AgentSessionCoordinator coordinator(final FakeExtension ext) {
        AgentSessionCoordinator.AgentExtensionResolver resolver =
                new AgentSessionCoordinator.AgentExtensionResolver() {
                    public AgentPluginExtension resolve(String agentId) {
                        return ext.id.equals(agentId) ? ext : null;
                    }
                };
        AgentSessionCoordinator.AgentHostContextProvider provider =
                new AgentSessionCoordinator.AgentHostContextProvider() {
                    public AgentHostContext create(String agentId, String sessionInstanceId) {
                        return null;
                    }
                };
        return new AgentSessionCoordinator(resolver, provider, new InlineUi());
    }

    private static final class InlineUi implements UiExecutor {
        public boolean isUiThread() {
            return true;
        }

        public void execute(Runnable runnable) {
            runnable.run();
        }

        public void assertUiThread() {
        }
    }

    private static final class FakeExtension implements AgentPluginExtension {
        private final String id;
        private FakeSession lastSession;

        FakeExtension(String id) {
            this.id = id;
        }

        public AgentPluginDescriptor getAgentDescriptor() {
            return AgentPluginDescriptor.builder().id(id).displayName(id).version("1").build();
        }

        public AgentSessionFactory getSessionFactory() {
            return new AgentSessionFactory() {
                public AgentSession create(AgentSessionCreationRequest request, AgentHostContext hostContext) {
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
        private boolean failClose;
        private int closeAttempts;

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
            return Collections.emptyList();
        }

        public AgentArtifactStore getArtifactStore() {
            return null;
        }

        public AgentStateSnapshot getState() {
            return AgentStateSnapshot.builder().build();
        }

        public void activate() {
        }

        public void deactivate() {
        }

        public void close() {
            closeAttempts++;
            if (failClose) {
                throw new RuntimeException("close failed");
            }
        }
    }
}
