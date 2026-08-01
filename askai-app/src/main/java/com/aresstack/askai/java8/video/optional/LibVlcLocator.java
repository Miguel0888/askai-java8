package com.aresstack.askai.java8.video.optional;

import java.io.File;

/**
 * Detects an installed VLC runtime WITHOUT bundling it (principle ported from the WD4J/corenth
 * {@code LibVlcLocator}). AskAI ships no vlcj/VLC binaries, so this only reports availability when the
 * user already has both the vlcj binding on the classpath and a VLC installation present — otherwise the
 * VLC backend stays unavailable (never a silent fallback). Reflection-only: no compile dependency on vlcj.
 */
public final class LibVlcLocator {

    private static final String[] INSTALL_CANDIDATES = {
            "C:\\Program Files\\VideoLAN\\VLC",
            "C:\\Program Files (x86)\\VideoLAN\\VLC",
            "/Applications/VLC.app/Contents/MacOS/lib",
            "/usr/lib", "/usr/lib64", "/usr/lib/x86_64-linux-gnu"
    };

    private LibVlcLocator() {
    }

    /** True only when the vlcj 3.x binding is on the classpath AND a VLC install directory exists. */
    public static boolean isAvailable() {
        return isVlcjOnClasspath() && hasInstallDirectory();
    }

    private static boolean isVlcjOnClasspath() {
        try {
            Class.forName("uk.co.caprica.vlcj.player.MediaPlayerFactory");
            return true;
        } catch (Throwable notPresent) {
            return false;
        }
    }

    private static boolean hasInstallDirectory() {
        for (int i = 0; i < INSTALL_CANDIDATES.length; i++) {
            if (new File(INSTALL_CANDIDATES[i]).isDirectory()) {
                return true;
            }
        }
        return false;
    }
}
