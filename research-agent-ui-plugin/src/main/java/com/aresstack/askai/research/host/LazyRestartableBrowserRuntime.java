package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.McpToolClient;
import com.aresstack.askai.mcp.api.McpToolClientFactory;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * A lazy, restartable owner of ONE browser MCP sidecar generation. It is created STOPPED (no process, no
 * Playwright, no readiness probe) when the research session starts, so selecting the Research agent never
 * spawns a browser. The sidecar is started on the FIRST browser command ({@link #execute}), stopped again
 * with {@link #stop()} when the browsing phase ends, and can be thrown away + replaced with {@link #restart()}
 * on failure — the TeamAgent survives a browser crash untouched.
 *
 * <p>THREADING: every operation runs on a single dedicated owner thread (a one-thread executor), so the
 * generation is never touched concurrently and calls are naturally serialized — two concurrent first commands
 * share the one start. Callers (the MCP bridge handler thread, never the Swing EDT) block on the owner via a
 * future. Each start bumps a generation id so a broken generation is unambiguous.</p>
 */
public final class LazyRestartableBrowserRuntime implements BrowserRuntimePort {

    /** One started sidecar generation (process + client), abstracted so the lifecycle is unit-testable. */
    interface Sidecar {
        String call(String tool, Map<String, Object> arguments) throws BrowserRuntimeException;

        boolean isAlive();

        void close();
    }

    /** Starts a fresh sidecar generation, BLOCKING until it is ready; throws on any start failure. */
    interface SidecarStarter {
        Sidecar start() throws BrowserRuntimeException;
    }

    private final SidecarStarter starter;
    private final ExecutorService owner;
    private volatile Listener listener = Listener.NONE;

    // Touched ONLY on the owner thread (state is volatile for the fast READY read in isReady/ensureStarted).
    private volatile State state = State.STOPPED;
    private long generation;
    private Sidecar current;
    private volatile boolean closed;

    /** Production runtime over the real browser MCP sidecar process. */
    public LazyRestartableBrowserRuntime(ResearchRuntimeConfig config, long readyTimeoutSeconds,
                                         String browserConfigPath, McpToolClientFactory toolClients) {
        this(productionStarter(config, readyTimeoutSeconds, browserConfigPath, toolClients));
    }

    LazyRestartableBrowserRuntime(SidecarStarter starter) {
        this.starter = starter;
        this.owner = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "browser-runtime-owner");
                t.setDaemon(true);
                return t;
            }
        });
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener == null ? Listener.NONE : listener;
    }

    @Override
    public boolean isReady() {
        return state == State.READY;
    }

    @Override
    public String execute(final String tool, final Map<String, Object> arguments)
            throws BrowserRuntimeException {
        ensureStarted();
        try {
            return callOnOwner(tool, arguments);
        } catch (BrowserRuntimeException firstFailure) {
            // A live generation died mid-use: throw it away, start a fresh one and retry the command ONCE.
            if (firstFailure.isEndpointUnavailable() && !closed) {
                restart();
                return callOnOwner(tool, arguments);
            }
            throw firstFailure;
        }
    }

    @Override
    public void ensureStarted() throws BrowserRuntimeException {
        runOnOwner(new Callable<Void>() {
            public Void call() throws Exception {
                if (closed) {
                    throw new BrowserRuntimeException("browser runtime is closed", true);
                }
                if (state == State.READY && current != null && current.isAlive()) {
                    return null;
                }
                startFreshGeneration();
                return null;
            }
        });
    }

    @Override
    public void restart() throws BrowserRuntimeException {
        runOnOwner(new Callable<Void>() {
            public Void call() throws Exception {
                stopCurrentGeneration();
                if (closed) {
                    throw new BrowserRuntimeException("browser runtime is closed", true);
                }
                startFreshGeneration();
                return null;
            }
        });
    }

    @Override
    public void stop() {
        if (closed) {
            return;
        }
        // Fire-and-forget: the RUN_OUTCOME handler calls this on the EDT, so it must NOT block. The owner
        // thread tears the generation down in order (a later start queues behind it).
        try {
            owner.submit(new Runnable() {
                public void run() {
                    stopCurrentGeneration();
                }
            });
        } catch (RuntimeException rejectedOrShutdown) {
            // owner already gone → effectively stopped
        }
    }

    @Override
    public void close() {
        boolean alreadyClosed;
        synchronized (this) {
            alreadyClosed = closed;
            closed = true;
        }
        if (alreadyClosed) {
            return;
        }
        try {
            owner.submit(new Runnable() {
                public void run() {
                    stopCurrentGeneration();
                    state = State.CLOSED;
                }
            }).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // best-effort teardown
        } finally {
            owner.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ owner-thread internals

    private void startFreshGeneration() throws BrowserRuntimeException {
        stopCurrentGeneration(); // never leak a previous generation
        final long gen = ++generation;
        state = State.STARTING;
        listener.onStarting(gen);
        try {
            this.current = starter.start();
            state = State.READY;
            listener.onReady(gen);
        } catch (BrowserRuntimeException failure) {
            state = State.FAILED;
            this.current = null;
            listener.onFailed(gen, failure.getMessage());
            throw failure;
        }
    }

    private String callOnOwner(final String tool, final Map<String, Object> arguments)
            throws BrowserRuntimeException {
        return runOnOwner(new Callable<String>() {
            public String call() throws Exception {
                if (state != State.READY || current == null) {
                    throw new BrowserRuntimeException("browser runtime is not ready", true);
                }
                return current.call(tool, arguments);
            }
        });
    }

    /** Owner thread: tear the current generation down (idempotent). */
    private void stopCurrentGeneration() {
        long gen = generation;
        if (state == State.READY || state == State.STARTING || state == State.FAILED) {
            state = State.STOPPING;
        }
        Sidecar toClose = current;
        current = null;
        if (toClose != null) {
            toClose.close();
        }
        if (!closed) {
            state = State.STOPPED;
        }
        if (toClose != null) {
            listener.onStopped(gen);
        }
    }

    private <T> T runOnOwner(Callable<T> task) throws BrowserRuntimeException {
        Future<T> future;
        try {
            future = owner.submit(task);
        } catch (RuntimeException rejected) {
            throw new BrowserRuntimeException("browser runtime owner unavailable: " + rejected.getMessage(),
                    true);
        }
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrowserRuntimeException("interrupted waiting for the browser runtime", true);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof BrowserRuntimeException) {
                throw (BrowserRuntimeException) cause;
            }
            throw new BrowserRuntimeException(cause == null ? ex.getMessage() : cause.getMessage(), true);
        }
    }

    /** Log a dead/unreachable sidecar's exit code and its last stderr lines (bounded) — pure diagnostics. */
    private static void logSidecarPostMortem(String tool, BrowserMcpSidecarProcess process) {
        Integer code = process.exitCodeOrNull();
        java.util.List<String> stderr = process.recentStderr();
        System.err.println("[browser] sidecar endpoint unavailable on tool=" + tool + " exit="
                + (code == null ? "still-alive" : code) + " stderrLines=" + stderr.size());
        int from = Math.max(0, stderr.size() - 12);
        for (int i = from; i < stderr.size(); i++) {
            System.err.println("[browser]   " + stderr.get(i));
        }
    }

    /** The productive starter: spawn the Java-21 browser sidecar and connect its MCP client (blocks to ready). */
    private static SidecarStarter productionStarter(final ResearchRuntimeConfig config,
                                                    final long readyTimeoutSeconds,
                                                    final String browserConfigPath,
                                                    final McpToolClientFactory toolClients) {
        return new SidecarStarter() {
            public Sidecar start() throws BrowserRuntimeException {
                try {
                    final BrowserMcpSidecarProcess process = BrowserMcpSidecarProcess.start(config,
                            readyTimeoutSeconds, browserConfigPath);
                    final McpToolClient client = toolClients.connect(process.getMcpUrl(), "streamable");
                    return new Sidecar() {
                        public String call(String tool, Map<String, Object> arguments)
                                throws BrowserRuntimeException {
                            try {
                                return client.callTool(tool, arguments);
                            } catch (McpToolClient.McpToolCallException ex) {
                                if (ex.isEndpointUnavailable()) {
                                    // The sidecar just became unreachable — dump its exit code + last words
                                    // so a mid-run browser death is diagnosable, not a bare "endpoint gone".
                                    logSidecarPostMortem(tool, process);
                                }
                                throw new BrowserRuntimeException(ex.getMessage(), ex.isEndpointUnavailable());
                            }
                        }

                        public boolean isAlive() {
                            return process.isAlive();
                        }

                        public void close() {
                            // McpToolClient has its OWN close() and is NOT java.io.Closeable — an instanceof
                            // Closeable check silently skipped it, leaking the Solon client's non-daemon
                            // heartbeat scheduler ("pool-N-thread-1") and keeping the JVM alive after exit.
                            try {
                                client.close();
                            } catch (RuntimeException ignored) {
                                // best-effort
                            }
                            process.close();
                        }
                    };
                } catch (Exception failure) {
                    throw new BrowserRuntimeException("browser sidecar failed to start: "
                            + failure.getMessage(), true);
                }
            }
        };
    }
}
