package com.aresstack.askai.research.host;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads the managed Camoufox browser in the background as soon as a productive research session with the
 * Camoufox channel starts, so the first browser command does not wait for the one-time download (about
 * 1.3 GB). Runs the browser sidecar jar in its {@code --install-camoufox} mode — the same installer the
 * sidecar uses at start, which serializes concurrent installs through its install lock — at most once per
 * AskAI process. An already verified installation finishes immediately. The child dies with this process
 * (its parent-death watchdog reads stdin), so the process object is kept referenced.
 */
final class CamoufoxPrefetch {

    private static Process running;
    private static boolean started;

    private CamoufoxPrefetch() {
    }

    static synchronized void startOnce(ResearchRuntimeConfig config) {
        if (started || !ResearchRuntimeSettings.CAMOUFOX_CHANNEL.equals(config.getBrowserChannel())
                || config.getSidecarJar().isEmpty() || config.getSidecarJavaExecutable().isEmpty()) {
            return;
        }
        started = true;
        List<String> command = new ArrayList<String>();
        command.add(config.getSidecarJavaExecutable());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-jar");
        command.add(config.getSidecarJar());
        command.add("--install-camoufox");
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            running = process;
            Thread drain = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(
                                process.getInputStream(), Charset.forName("UTF-8")));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.contains(BrowserMcpSidecarProcess.CAMOUFOX_INSTALL_MARKER)) {
                                System.err.println("[camoufox-prefetch] " + line.trim());
                            }
                        }
                        System.err.println("[camoufox-prefetch] finished with exit code " + process.waitFor());
                    } catch (IOException ignored) {
                        // the stream ends with the process
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "camoufox-prefetch");
            drain.setDaemon(true);
            drain.start();
        } catch (IOException failed) {
            // Not fatal: the sidecar installs on its first start anyway.
            System.err.println("[camoufox-prefetch] could not start: " + failed.getMessage());
        }
    }
}
