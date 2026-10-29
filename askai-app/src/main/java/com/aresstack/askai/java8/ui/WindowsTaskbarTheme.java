package com.aresstack.askai.java8.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * Whether the Windows taskbar uses the light or the dark theme — the surface the window icon is shown
 * on. Read from {@code HKCU\...\Themes\Personalize\SystemUsesLightTheme} via {@code reg query} (Java 8
 * has no registry API). Unknown (not Windows, value missing, probe failed or timed out) is reported as
 * such; the caller decides the fallback.
 *
 * <p>The probe timeout is the system property {@code askai.icon.themeProbeTimeoutMs} (default 2000).</p>
 */
final class WindowsTaskbarTheme {

    enum Mode { LIGHT, DARK, UNKNOWN }

    static final String TIMEOUT_PROPERTY = "askai.icon.themeProbeTimeoutMs";
    private static final long DEFAULT_TIMEOUT_MILLIS = 2000L;
    private static final String KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final String VALUE = "SystemUsesLightTheme";

    private WindowsTaskbarTheme() {
    }

    static Mode detect() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return Mode.UNKNOWN;
        }
        Process process = null;
        try {
            process = new ProcessBuilder("reg", "query", KEY, "/v", VALUE).redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(timeoutMillis(), TimeUnit.MILLISECONDS)) {
                return Mode.UNKNOWN;
            }
            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    Charset.defaultCharset()));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } finally {
                reader.close();
            }
            return parse(output.toString());
        } catch (Exception unavailable) {
            return Mode.UNKNOWN;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** Parse {@code reg query} output: {@code SystemUsesLightTheme    REG_DWORD    0x1}. */
    static Mode parse(String regOutput) {
        if (regOutput == null) {
            return Mode.UNKNOWN;
        }
        for (String line : regOutput.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(VALUE) && trimmed.contains("REG_DWORD")) {
                String value = trimmed.substring(trimmed.lastIndexOf(' ') + 1).trim();
                if ("0x1".equalsIgnoreCase(value)) {
                    return Mode.LIGHT;
                }
                if ("0x0".equalsIgnoreCase(value)) {
                    return Mode.DARK;
                }
            }
        }
        return Mode.UNKNOWN;
    }

    private static long timeoutMillis() {
        try {
            return Long.parseLong(System.getProperty(TIMEOUT_PROPERTY, String.valueOf(DEFAULT_TIMEOUT_MILLIS)).trim());
        } catch (NumberFormatException invalid) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
    }
}
