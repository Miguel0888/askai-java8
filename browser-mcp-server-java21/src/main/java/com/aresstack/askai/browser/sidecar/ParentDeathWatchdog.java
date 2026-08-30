package com.aresstack.askai.browser.sidecar;

import java.io.IOException;
import java.io.InputStream;

/**
 * The Zulu-leak fix: this child must DIE WITH ITS PARENT. The host stops the app hard (IDE stop
 * button) — shutdown hooks in the parent never run, and orphaned sidecar JVMs piled up until the
 * machine ran out of commit memory (observed: 60+ corpses, a 59 GB pagefile, and the app failing
 * to start with an mmap error). The parent never writes to our stdin, but the OS closes the pipe
 * when the parent dies — reading until EOF is therefore a reliable death notice, whatever way the
 * parent went. Exit runs the shutdown hooks (server stop); a halt fallback guards against a hook
 * that hangs.
 */
final class ParentDeathWatchdog {

    private ParentDeathWatchdog() {
    }

    static void install(String name) {
        watch(name, System.in);
    }

    /** Visible for tests: watch an explicit stream. */
    static Thread watch(String name, InputStream parentPipe) {
        Thread watchdog = new Thread(() -> {
            try {
                byte[] buffer = new byte[256];
                while (parentPipe.read(buffer) >= 0) {
                    // the parent never writes; only EOF (parent death) ends this loop
                }
            } catch (IOException pipeBroken) {
                // same meaning as EOF
            }
            System.err.println("[" + name + "] parent gone (stdin EOF) — exiting");
            Thread reaper = new Thread(() -> {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                Runtime.getRuntime().halt(0); // a hanging shutdown hook must not recreate the leak
            }, name + "-halt-fallback");
            reaper.setDaemon(true);
            reaper.start();
            System.exit(0);
        }, name + "-parent-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return watchdog;
    }
}
