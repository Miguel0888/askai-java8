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
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Commit-21c shutdown ordering through the single production path: when the plugin service is shut down from the
 * EDT it detaches the outgoing generation's sessions via the coordinator swap hook on the EDT, then closes them
 * <em>off</em> the EDT — the host no longer closes sessions itself, and no {@code session.close()} runs on the EDT.
 */
public class ShutdownOrderingTest {

    @Test
    public void shutdownDetachesOnEdtAndClosesSessionsOffEdt() throws Exception {
        ThreadedUi ui = new ThreadedUi();
        final FakeExtension ext = new FakeExtension("agent.a");
        AgentSessionCoordinator coordinator = new AgentSessionCoordinator(
                new AgentSessionCoordinator.AgentExtensionResolver() {
                    public AgentPluginExtension resolve(String agentId) {
                        return "agent.a".equals(agentId) ? ext : null;
                    }
                },
                new AgentSessionCoordinator.AgentHostContextProvider() {
                    public AgentHostContext create(String agentId, String sessionInstanceId) {
                        return null;
                    }
                }, ui);
        coordinator.setActiveAgent("agent.a");
        FakeSession session = ext.lastSession;
        assertNotNull(session);

        WorkspacePluginService service =
                new WorkspacePluginService(Paths.get("."), "0.1.0", 1, ui, null);
        service.setGenerationSwapHook(coordinator);

        // Invoke shutdown ON the EDT (like window close). Detach runs on the EDT; close must run off it.
        final CountDownLatch invoked = new CountDownLatch(1);
        ui.execute(new Runnable() {
            public void run() {
                service.shutdown();
                invoked.countDown();
            }
        });
        assertTrue(invoked.await(5, TimeUnit.SECONDS));
        assertTrue("off-EDT shutdown work must complete", service.awaitShutdownForTest(10_000));

        assertEquals("the session was closed exactly once", 1, session.closeCount);
        assertNotNull(session.closeThread.get());
        assertFalse("session.close() must NOT run on the EDT", ui.edtThread() == session.closeThread.get());
        assertFalse(coordinator.isActive());
        ui.shutdown();
    }

    private static final class ThreadedUi implements UiExecutor {
        private volatile Thread edt;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "test-edt");
                t.setDaemon(true);
                edt = t;
                return t;
            }
        });

        Thread edtThread() {
            return edt;
        }

        public boolean isUiThread() {
            return Thread.currentThread() == edt;
        }

        public void execute(Runnable runnable) {
            executor.execute(runnable);
        }

        public void assertUiThread() {
        }

        void shutdown() {
            executor.shutdownNow();
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
        private int closeCount;
        private final AtomicReference<Thread> closeThread = new AtomicReference<Thread>();

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
            closeCount++;
            closeThread.set(Thread.currentThread());
        }
    }
}
