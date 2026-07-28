package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserException;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The single OS thread that OWNS the whole Playwright object graph (Playwright → Browser → Context →
 * every Page). Playwright's Java binding is NOT thread-safe: the connection to the driver child processes
 * one message loop, and objects must be touched from one thread. So every driver call — launch, navigate,
 * evaluate, waitForLoadState, close — is marshalled here via {@link #call} and awaited by the caller.
 *
 * <p>Because the research loop drives the sidecar over serial HTTP tool calls, blocking the caller until
 * the confined task finishes costs no real concurrency; what it buys is the guarantee that concurrent MCP
 * handler threads can never race the same Page. Tasks must NOT re-enter {@link #call} (that would deadlock
 * the single worker on itself) — nest work as plain method calls inside one task instead.</p>
 */
final class PlaywrightThread implements AutoCloseable {

    /** A unit of work confined to the Playwright thread; may fail with the same readable errors as the API. */
    interface Call<T> {
        T run() throws BrowserException;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "playwright-driver");
            t.setDaemon(true);
            return t;
        }
    });

    /** Submit work to the Playwright thread and block until it completes, unwrapping the real failure. */
    <T> T call(final Call<T> task) throws BrowserException {
        Future<T> future;
        try {
            future = worker.submit(new Callable<T>() {
                public T call() throws Exception {
                    return task.run();
                }
            });
        } catch (RejectedExecutionException closed) {
            throw new BrowserException("Browser session is closed.");
        }
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrowserException("Interrupted while waiting for the browser.");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof BrowserException) {
                throw (BrowserException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new BrowserException("Browser call failed: " + cause.getMessage());
        }
    }

    /**
     * Run a best-effort teardown task on the Playwright thread (bounded, never rethrows) and then stop the
     * worker. Used by {@code close()} so page/context/browser/Playwright are released ON their owning thread
     * even from a shutdown hook, without letting a hung navigation block JVM exit forever.
     */
    void shutdown(final Runnable teardown, long teardownTimeoutMillis) {
        Future<?> done;
        try {
            done = worker.submit(teardown);
        } catch (RejectedExecutionException alreadyClosed) {
            return;
        }
        try {
            done.get(teardownTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            // teardown is best-effort — a failure to close cleanly must not crash shutdown.
        } catch (TimeoutException ex) {
            // A wedged Playwright call: stop waiting and force the worker down below.
        } finally {
            worker.shutdownNow();
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
