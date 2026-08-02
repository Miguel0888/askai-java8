package com.aresstack.askai.java8;

import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.service.DefaultAskAiService;
import com.aresstack.askai.java8.ui.AskAiFrame;

import javax.swing.SwingUtilities;

public final class AskAiJava8App {

    /**
     * Held for the whole JVM lifetime (never closed) when launched from an immutable dev-plugin generation
     * via {@code runWithDevPlugins}. The build's cleanup step uses this OS-level lock to prove a generation
     * is no longer in use before deleting it. Kept in a static field so it is never garbage-collected.
     */
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private static java.nio.channels.FileLock devPluginRunLock;

    private AskAiJava8App() {
    }

    public static void main(String[] args) {
        holdDevPluginRunLock();
        final AppConfigurationRepository configurationRepository = new AppConfigurationRepository();
        // Must be set before any networking (java.net.InetAddress reads it once, at class init).
        if (configurationRepository.load().getHttpClientConfiguration().isPreferIpv6()) {
            System.setProperty("java.net.preferIPv6Addresses", "true");
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                DefaultAskAiService askAiService = new DefaultAskAiService(configurationRepository);
                AskAiFrame frame = new AskAiFrame(configurationRepository, askAiService);
                frame.showFrame();
            }
        });
    }

    /**
     * Dev-harness guard (D1): when launched from an immutable plugin generation, hold an exclusive OS-level
     * lock on the generation's {@code .run.lock} for the process lifetime so a later {@code runWithDevPlugins}
     * build only prunes generations whose JVM has already exited. A no-op in real deployments, where the
     * {@code askai.devPluginRun.lock} system property is absent.
     */
    private static void holdDevPluginRunLock() {
        final String lockPath = System.getProperty("askai.devPluginRun.lock");
        if (lockPath == null || lockPath.trim().isEmpty()) {
            return;
        }
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lockPath.trim(), "rw");
            // Deliberately never closed: the lock (and channel) live until the JVM exits, at which point the
            // OS releases it and the next build is free to reclaim this generation.
            devPluginRunLock = raf.getChannel().lock();
        } catch (Exception unableToLock) {
            // Dev-only: failing to hold the lock merely means stale generations may linger until manual
            // cleanup — never a correctness problem, so we degrade quietly to STDERR.
            System.err.println("askai: could not hold dev-plugin run lock (" + lockPath + "): " + unableToLock);
        }
    }
}
