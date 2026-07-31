package com.aresstack.askai.acp.solon;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.acp.AcpConnection;
import com.aresstack.askai.acp.AcpConnectionState;
import com.aresstack.askai.acp.AcpException;
import com.aresstack.askai.acp.AcpPromptState;
import com.aresstack.askai.acp.AcpSession;
import com.aresstack.askai.acp.AcpSessionState;
import com.aresstack.askai.acp.AcpStates;
import com.aresstack.askai.acp.AcpUpdate;
import com.aresstack.askai.acp.AcpUpdateListener;
import com.aresstack.askai.acp.AgentLaunchSpec;
import com.aresstack.askai.acp.AgentProcessHandle;
import com.aresstack.askai.acp.PromptDispatcher;
import com.aresstack.askai.acp.PromptHandle;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The one place that touches acp-sdk/Reactor. Spawns the external agent via the SDK's STDIO transport,
 * initializes an {@link AcpSyncClient}, and adapts everything onto the neutral :acp-client-api ports.
 *
 * <p>Lifecycles are separate: the OS process (mediated by the SDK transport), the initialized connection
 * (guarded {@link AcpStates.Connection}), the logical session ({@link AcpStates.Session}) and each prompt run
 * (a {@link PromptDispatcher} with monotonic sequences and exactly one terminal). STDERR is drained by the
 * SDK reader into a BOUNDED ring buffer plus an optional host log consumer — never unbounded, never mixed
 * with the ACP STDOUT stream. All callbacks run on a private daemon executor, never a UI thread.</p>
 */
public final class SolonAcpAgentConnector implements AcpAgentConnector {

    private static final int STDERR_RING_LIMIT = 200;
    /** Upper bound on a connection close: a graceful transport shutdown must never wedge the caller. */
    private static final long CLOSE_TIMEOUT_MILLIS = 2500L;

    private final Duration requestTimeout;
    private final Consumer<String> hostLog;

    public SolonAcpAgentConnector(Duration requestTimeout, Consumer<String> hostLog) {
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        this.hostLog = hostLog;
    }

    @Override
    public AcpConnection connect(AgentLaunchSpec spec) throws AcpException {
        Connection connection = new Connection(spec);
        connection.establish();
        return connection;
    }

    // ------------------------------------------------------------------ connection

    private final class Connection implements AcpConnection {
        private final AgentLaunchSpec spec;
        private final AcpStates.Connection state = new AcpStates.Connection();
        private final Deque<String> stderrRing = new ArrayDeque<String>();
        private final ConcurrentHashMap<String, PromptDispatcher> activePromptBySession =
                new ConcurrentHashMap<String, PromptDispatcher>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "acp-prompt");
            t.setDaemon(true);
            return t;
        });
        private StdioAcpClientTransport transport;
        private AcpSyncClient client;
        private volatile boolean processAlive;

        private Connection(AgentLaunchSpec spec) {
            this.spec = spec;
        }

        void establish() throws AcpException {
            try {
                AgentParameters.Builder params = AgentParameters.builder(spec.getCommand())
                        .args(spec.getArgs());
                if (!spec.getEnv().isEmpty()) {
                    params.env(spec.getEnv());
                }
                transport = new StdioAcpClientTransport(params.build());
                transport.setStdErrorHandler(new Consumer<String>() {
                    public void accept(String line) {
                        synchronized (stderrRing) {
                            if (stderrRing.size() >= STDERR_RING_LIMIT) {
                                stderrRing.pollFirst();
                            }
                            stderrRing.addLast(line);
                        }
                        if (hostLog != null) {
                            hostLog.accept(line);
                        }
                    }
                });
                processAlive = true;
            } catch (RuntimeException ex) {
                state.to(AcpConnectionState.FAILED);
                throw new AcpException(AcpException.Phase.SPAWN,
                        "Could not spawn the agent process: " + ex.getMessage(), ex);
            }
            try {
                state.to(AcpConnectionState.INITIALIZING);
                client = AcpClient.sync(transport)
                        .requestTimeout(requestTimeout)
                        .sessionUpdateConsumer(new Consumer<AcpSchema.SessionNotification>() {
                            public void accept(AcpSchema.SessionNotification n) {
                                route(n);
                            }
                        })
                        .build();
                client.initialize(); // capability negotiation happens inside; result readable via client
                state.to(AcpConnectionState.READY);
            } catch (RuntimeException ex) {
                state.to(AcpConnectionState.FAILED);
                closeQuietly();
                throw new AcpException(AcpException.Phase.INITIALIZE,
                        "ACP initialize failed: " + ex.getMessage(), ex);
            }
        }

        /** Routes a streamed SessionNotification to the session's active prompt dispatcher. */
        private void route(AcpSchema.SessionNotification notification) {
            PromptDispatcher dispatcher = activePromptBySession.get(notification.sessionId());
            if (dispatcher == null) {
                return; // no active prompt (late/unknown) → drop, never crash the reader
            }
            AcpSchema.SessionUpdate update = notification.update();
            if (update instanceof AcpSchema.AgentMessageChunk) {
                dispatcher.update(AcpUpdate.Kind.MESSAGE, textOf(((AcpSchema.AgentMessageChunk) update).content()));
            } else if (update instanceof AcpSchema.AgentThoughtChunk) {
                dispatcher.update(AcpUpdate.Kind.THOUGHT, textOf(((AcpSchema.AgentThoughtChunk) update).content()));
            } else {
                // Unknown/custom update kinds are tolerated and surfaced generically, never fatal.
                dispatcher.update(AcpUpdate.Kind.OTHER, String.valueOf(update));
            }
        }

        private String textOf(AcpSchema.ContentBlock block) {
            return block instanceof AcpSchema.TextContent
                    ? ((AcpSchema.TextContent) block).text() : String.valueOf(block);
        }

        public AcpConnectionState getState() {
            return state.get();
        }

        public AgentProcessHandle getProcess() {
            return new AgentProcessHandle() {
                public boolean isAlive() {
                    return processAlive;
                }

                public void destroyForcibly() {
                    closeQuietly();
                }
            };
        }

        public AcpSession newSession() throws AcpException {
            if (state.get() != AcpConnectionState.READY) {
                throw new AcpException(AcpException.Phase.SESSION,
                        "Connection is not READY (" + state.get() + ").", null);
            }
            try {
                AcpSchema.NewSessionResponse response = client.newSession(new AcpSchema.NewSessionRequest(
                        System.getProperty("user.dir"), Collections.<AcpSchema.McpServer>emptyList()));
                return new Session(this, response.sessionId());
            } catch (RuntimeException ex) {
                throw new AcpException(AcpException.Phase.SESSION,
                        "session/new failed: " + ex.getMessage(), ex);
            }
        }

        public void close() {
            if (state.get() == AcpConnectionState.READY) {
                state.to(AcpConnectionState.CLOSED);
            }
            closeQuietly();
        }

        private void closeQuietly() {
            processAlive = false;
            final AcpSyncClient toClose = client;
            if (toClose != null) {
                // BOUNDED teardown: a graceful client.close() can block when the agent process is stuck
                // (e.g. mid /api/chat model call), and this runs on the CALLER's thread — the EDT on a tab
                // close, the shutdown thread on app exit. Never let that wedge the app: close on a daemon
                // thread and move on after a short budget. A lingering child is reaped by the OS; the app
                // still exits and its shutdown persistence still runs.
                Thread closer = new Thread(new Runnable() {
                    public void run() {
                        try {
                            toClose.close();
                        } catch (RuntimeException ignored) {
                            // best-effort
                        }
                    }
                }, "acp-connection-close");
                closer.setDaemon(true);
                closer.start();
                try {
                    closer.join(CLOSE_TIMEOUT_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ session + prompt

    private final class Session implements AcpSession {
        private final Connection connection;
        private final String sessionId;
        private final AcpStates.Session state = new AcpStates.Session();

        private Session(Connection connection, String sessionId) {
            this.connection = connection;
            this.sessionId = sessionId;
            state.to(AcpSessionState.ACTIVE);
        }

        public String getSessionId() {
            return sessionId;
        }

        public AcpSessionState getState() {
            return state.get();
        }

        public PromptHandle prompt(String text, AcpUpdateListener listener) {
            final String promptId = UUID.randomUUID().toString();
            final PromptDispatcher dispatcher = new PromptDispatcher(sessionId, promptId, listener);
            connection.activePromptBySession.put(sessionId, dispatcher);
            final String promptText = text == null ? "" : text;
            connection.executor.execute(new Runnable() {
                public void run() {
                    try {
                        AcpSchema.PromptResponse response = connection.client.prompt(
                                new AcpSchema.PromptRequest(sessionId, Collections.<AcpSchema.ContentBlock>
                                        singletonList(new AcpSchema.TextContent(promptText))));
                        AcpPromptState terminal = response != null
                                && response.stopReason() == AcpSchema.StopReason.CANCELLED
                                ? AcpPromptState.CANCELLED : AcpPromptState.COMPLETED;
                        dispatcher.terminal(terminal, String.valueOf(
                                response == null ? "" : response.stopReason()));
                    } catch (RuntimeException ex) {
                        // Process death / broken pipe / timeout during a prompt → FAILED (classified),
                        // and the connection is marked FAILED when the transport is gone.
                        dispatcher.terminal(AcpPromptState.FAILED, "prompt failed: " + ex.getMessage());
                        connection.state.to(AcpConnectionState.FAILED);
                        connection.processAlive = false;
                    } finally {
                        connection.activePromptBySession.remove(sessionId, dispatcher);
                    }
                }
            });
            return new PromptHandle() {
                public String getPromptId() {
                    return promptId;
                }

                public AcpPromptState getState() {
                    return dispatcher.getState();
                }

                public void cancel() {
                    // Idempotent; a no-op after completion. Cancelling never kills the process or session.
                    if (dispatcher.cancelling()) {
                        try {
                            connection.client.cancel(new AcpSchema.CancelNotification(sessionId));
                        } catch (RuntimeException ignored) {
                            // the prompt thread will surface the terminal state
                        }
                    }
                }
            };
        }

        public void close() {
            state.to(AcpSessionState.CLOSING);
            state.to(AcpSessionState.CLOSED);
            connection.activePromptBySession.remove(sessionId);
        }
    }
}
