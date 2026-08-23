package com.aresstack.askai.research.host;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The host-side OWNER of one Java-21 browser MCP sidecar process (one per research session — process-level
 * browser isolation). Spawns the thin sidecar jar with a fresh random token and a free loopback port, drains
 * STDERR on a daemon thread and waits for the sidecar's own readiness line; anything but READY fails the
 * start with that specific status (no fallback). The child receives ONLY the arguments below — it never
 * inherits host secrets beyond the process environment Java itself passes, and neither the token nor the
 * URL is ever logged here. {@link #close()} is idempotent: destroy, bounded wait, forced kill.
 */
public final class BrowserMcpSidecarProcess {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** How many trailing stderr lines to retain for post-mortem diagnostics (a dead sidecar's last words). */
    private static final int STDERR_RING_CAPACITY = 60;

    private final Process process;
    private final int port;
    private final String token;
    private final String readinessLine;
    private final Deque<String> recentStderr; // bounded ring, appended by the daemon drain thread
    private volatile boolean closed;

    private BrowserMcpSidecarProcess(Process process, int port, String token, String readinessLine,
                                     Deque<String> recentStderr) {
        this.process = process;
        this.port = port;
        this.token = token;
        this.readinessLine = readinessLine;
        this.recentStderr = recentStderr;
    }

    public static BrowserMcpSidecarProcess start(ResearchRuntimeConfig config,
                                                 long readyTimeoutSeconds) throws IOException {
        return start(config, readyTimeoutSeconds, null);
    }

    /**
     * @param browserConfigPath absolute path of the versioned browser-search config document
     *                          ({@code --browser-config=}), or null (sidecar then uses the central
     *                          defaults). Precedence inside the sidecar: defaults &lt; document &lt;
     *                          explicit legacy CLI overrides (dev/test).
     */
    public static BrowserMcpSidecarProcess start(ResearchRuntimeConfig config,
                                                 long readyTimeoutSeconds,
                                                 String browserConfigPath) throws IOException {
        int port = freePort();
        String token = newToken();
        List<String> command = new ArrayList<String>();
        command.add(config.getSidecarJavaExecutable());
        // UTF-8 default, explicitly: a sidecar on an older JVM must never decode/encode tool payloads
        // with the Windows platform charset (Cp1252) — that is where SERP titles turn into mojibake.
        command.add("-Dfile.encoding=UTF-8");
        command.add("-jar");
        command.add(config.getSidecarJar());
        command.add("--port=" + port);
        command.add("--token=" + token);
        command.add("--browser-channel=" + config.getBrowserChannel());
        command.add("--headless=" + config.isHeadless());
        command.add("--allow-private=" + config.isAllowPrivateNetworks());
        if (browserConfigPath != null && !browserConfigPath.isEmpty()) {
            command.add("--browser-config=" + browserConfigPath);
        }
        // Documented DEV/TEST hand-off (like askai.research.runtime.dir): extra sidecar arguments,
        // e.g. "--domain-key-mode=host-port" so local multi-server worlds act as distinct domains.
        String extraArgs = System.getProperty("askai.research.sidecar.args", "").trim();
        if (!extraArgs.isEmpty()) {
            for (String extra : extraArgs.split("\\s+")) {
                command.add(extra);
            }
        }
        final Process process = new ProcessBuilder(command).start();

        final CountDownLatch ready = new CountDownLatch(1);
        final StringBuilder readiness = new StringBuilder();
        final Deque<String> recentStderr = new ArrayDeque<String>();
        Thread drain = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            process.getErrorStream(), Charset.forName("UTF-8")));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Retain EVERY line (bounded): the sidecar's last stderr is the only post-mortem when
                        // it dies mid-run — no longer discarded once readiness has been seen.
                        synchronized (recentStderr) {
                            recentStderr.addLast(line);
                            while (recentStderr.size() > STDERR_RING_CAPACITY) {
                                recentStderr.removeFirst();
                            }
                        }
                        if (line.contains("playwright readiness:")) {
                            synchronized (readiness) {
                                readiness.setLength(0);
                                readiness.append(line.substring(
                                        line.indexOf("playwright readiness:") + 21).trim());
                            }
                        }
                        if (line.contains("ready on 127.0.0.1:")) {
                            ready.countDown();
                        }
                    }
                } catch (IOException ignored) {
                    // Stream ends with the process; readiness stays whatever was reported.
                }
            }
        }, "browser-sidecar-stderr");
        drain.setDaemon(true);
        drain.start();

        boolean up;
        try {
            up = ready.await(readyTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            up = false;
        }
        String readinessLine;
        synchronized (readiness) {
            readinessLine = readiness.toString();
        }
        if (!up) {
            process.destroyForcibly();
            throw new IOException("Browser sidecar did not become ready within " + readyTimeoutSeconds
                    + "s" + (readinessLine.isEmpty() ? "" : " (last readiness: " + readinessLine + ")")
                    + tail(recentStderr));
        }
        if (!readinessLine.startsWith("READY")) {
            process.destroyForcibly();
            throw new IOException("Browser sidecar backend unavailable: " + readinessLine
                    + tail(recentStderr));
        }
        return new BrowserMcpSidecarProcess(process, port, token, readinessLine, recentStderr);
    }

    /** A compact ' | last stderr: …' suffix from the ring (empty when nothing captured) — for start failures. */
    private static String tail(Deque<String> ring) {
        List<String> lines;
        synchronized (ring) {
            lines = new ArrayList<String>(ring);
        }
        if (lines.isEmpty()) {
            return "";
        }
        int from = Math.max(0, lines.size() - 8);
        StringBuilder sb = new StringBuilder(" | last stderr: ");
        for (int i = from; i < lines.size(); i++) {
            if (i > from) {
                sb.append(" ⏎ ");
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    public String getMcpUrl() {
        return "http://127.0.0.1:" + port + "/mcp/browser/" + token;
    }

    public String getToken() {
        return token;
    }

    /** The sidecar's own probe status line (e.g. "READY: channel=chrome ..."), token-free. */
    public String getReadinessLine() {
        return readinessLine;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** The process exit code, or {@code null} while it is still alive — for post-mortem logging of a dead sidecar. */
    public Integer exitCodeOrNull() {
        return process.isAlive() ? null : Integer.valueOf(process.exitValue());
    }

    /** The last stderr lines the sidecar emitted (bounded ring, oldest first); a dead sidecar's final words. */
    public List<String> recentStderr() {
        synchronized (recentStderr) {
            return new ArrayList<String>(recentStderr);
        }
    }

    /** Idempotent: destroy → bounded wait → forced kill (the driver child + browser die with the pipes). */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
