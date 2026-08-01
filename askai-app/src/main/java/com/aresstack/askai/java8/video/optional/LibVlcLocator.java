package com.aresstack.askai.java8.video.optional;

import java.io.File;

/**
 * Locates an INSTALLED VLC runtime without bundling it (ported from the WD4J/corenth
 * {@code LibVlcLocator}/{@code MediaRuntimeBootstrap}). AskAI ships only the vlcj Java binding; libvlc
 * itself must already be installed by the user — otherwise the VLC backend stays unavailable and is
 * never offered (no silent fallback, no download, no process started by AskAI).
 *
 * <p>{@link #configureRuntime()} must run once before the first vlcj call: it points JNA at the found
 * VLC directory ({@code jna.library.path}, {@code VLC_PLUGIN_PATH}) and additionally runs vlcj's own
 * {@code NativeDiscovery} as a second net for non-standard install locations.</p>
 */
public final class LibVlcLocator {

    private static final String[] INSTALL_CANDIDATES = {
            "C:\\Program Files\\VideoLAN\\VLC",
            "C:\\Program Files (x86)\\VideoLAN\\VLC",
            "/Applications/VLC.app/Contents/MacOS/lib",
            "/usr/lib", "/usr/lib64", "/usr/lib/x86_64-linux-gnu"
    };

    private static volatile boolean configured;

    private LibVlcLocator() {
    }

    /** True only when the vlcj binding is loadable AND a VLC installation could be located. */
    public static boolean isAvailable() {
        return isVlcjOnClasspath() && findInstallDirectory() != null;
    }

    /**
     * Configure JNA/vlcj so libvlc can actually be loaded. Idempotent; returns false when no VLC
     * installation was found (the caller must then refuse — never fall back silently).
     */
    public static synchronized boolean configureRuntime() {
        if (configured) {
            return true;
        }
        if (!isVlcjOnClasspath()) {
            return false;
        }
        boolean found = false;
        File dir = findInstallDirectory();
        if (dir != null) {
            applyBasePath(dir);
            found = true;
        }
        try {
            // vlcj's own discovery finds non-standard installs and registers them with JNA.
            found |= new uk.co.caprica.vlcj.discovery.NativeDiscovery().discover();
        } catch (Throwable discoveryFailed) {
            // Discovery is only a second net; the explicit base path above may still suffice.
        }
        configured = found;
        return found;
    }

    private static void applyBasePath(File vlcHome) {
        String sep = System.getProperty("path.separator", ";");
        String existing = System.getProperty("jna.library.path");
        String combined = (existing == null || existing.trim().isEmpty())
                ? vlcHome.getAbsolutePath()
                : existing + sep + vlcHome.getAbsolutePath();
        System.setProperty("jna.library.path", combined);
        File plugins = new File(vlcHome, "plugins");
        if (plugins.isDirectory()) {
            System.setProperty("VLC_PLUGIN_PATH", plugins.getAbsolutePath());
        }
    }

    private static boolean isVlcjOnClasspath() {
        try {
            Class.forName("uk.co.caprica.vlcj.player.MediaPlayerFactory");
            return true;
        } catch (Throwable notPresent) {
            return false;
        }
    }

    /** First existing candidate matching the JVM bitness (a 64-bit JVM cannot load 32-bit libvlc). */
    private static File findInstallDirectory() {
        boolean jvm64 = System.getProperty("os.arch", "").contains("64");
        for (int i = 0; i < INSTALL_CANDIDATES.length; i++) {
            String candidate = INSTALL_CANDIDATES[i];
            if (jvm64 && candidate.contains("Program Files (x86)")) {
                continue;
            }
            File dir = new File(candidate);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }
}
