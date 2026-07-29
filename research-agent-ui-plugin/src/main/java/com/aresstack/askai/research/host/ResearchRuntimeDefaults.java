package com.aresstack.askai.research.host;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Automatic defaults for the productive research runtime so a normal development start has NO empty
 * mandatory fields: the agent's Java 8 comes from the running JVM ({@code java.home}), the two jars come
 * from the assembled distribution directory (documented hand-off {@code askai.research.runtime.dir}, set by
 * {@code runWithDevPlugins} / an installer), and a Java 21 launcher is DISCOVERED in the standard JDK
 * install locations by parsing each candidate's {@code release} file ({@code askai.research.java21}
 * overrides discovery). Only EMPTY fields are completed — explicit user values always win, and nothing is
 * ever downloaded or installed. The single exception is the browser channel: when the configured browser
 * is provably absent but the other channel's browser is installed, the installed one is used (otherwise
 * every session would fail although a working browser exists).
 */
public final class ResearchRuntimeDefaults {

    /** Documented dev/installer hand-off for the assembled distribution directory. */
    public static final String RUNTIME_DIR_PROPERTY = "askai.research.runtime.dir";
    /** Documented override for the Java 21 launcher (skips discovery). */
    public static final String JAVA21_PROPERTY = "askai.research.java21";

    private ResearchRuntimeDefaults() {
    }

    /** Complete only the EMPTY fields of the settings from the detectable environment. */
    public static ResearchRuntimeSettings complete(ResearchRuntimeSettings settings) {
        String agentJava = settings.getAgentJavaExecutable();
        if (agentJava.isEmpty()) {
            agentJava = currentJvmJava();
        }
        String agentJar = settings.getAgentJar();
        String sidecarJar = settings.getSidecarJar();
        File dist = distributionDirectory();
        if (dist != null) {
            if (agentJar.isEmpty()) {
                agentJar = existingPath(new File(dist, "research-agent-runtime.jar"));
            }
            if (sidecarJar.isEmpty()) {
                sidecarJar = existingPath(new File(dist, "browser-mcp-sidecar.jar"));
            }
        }
        String sidecarJava = settings.getSidecarJavaExecutable();
        if (sidecarJava.isEmpty()) {
            sidecarJava = locateJava21();
        }
        return new ResearchRuntimeSettings(settings.getMode(), agentJava, agentJar, sidecarJava,
                sidecarJar, installedBrowserChannel(settings.getBrowserChannel()), settings.isHeadless(),
                settings.getSearchUrlTemplate(), settings.isAllowPrivateNetworks(),
                settings.getSelectedRerankerModel());
    }

    /**
     * The browser channel to actually use: the configured one when its browser is installed; otherwise the
     * OTHER channel when that one is provably present (e.g. a machine with Edge but no Chrome keeps
     * working after Chrome is uninstalled). When neither is found, the configured channel stays and the
     * sidecar's readiness probe reports BROWSER_NOT_INSTALLED readably. Discovery only — never installs.
     */
    public static String installedBrowserChannel(String configured) {
        String channel = "msedge".equals(configured) ? "msedge" : "chrome";
        String other = "msedge".equals(channel) ? "chrome" : "msedge";
        return pickChannel(channel, browserInstalled(channel), browserInstalled(other), other);
    }

    /** Pure channel decision, separated from the file-system probe for testability. */
    static String pickChannel(String configured, boolean configuredInstalled, boolean otherInstalled,
                              String other) {
        return configuredInstalled || !otherInstalled ? configured : other;
    }

    /** Standard install locations of the channel's browser (mirrors the sidecar's discovery paths). */
    static boolean browserInstalled(String channel) {
        boolean chrome = !"msedge".equals(channel);
        java.util.List<String> paths = new java.util.ArrayList<String>();
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            for (String root : new String[]{System.getenv("ProgramFiles"),
                    System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")}) {
                if (root != null) {
                    paths.add(root + (chrome ? "\\Google\\Chrome\\Application\\chrome.exe"
                            : "\\Microsoft\\Edge\\Application\\msedge.exe"));
                }
            }
        } else if (os.contains("mac")) {
            paths.add(chrome ? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                    : "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else {
            if (chrome) {
                paths.add("/usr/bin/google-chrome");
                paths.add("/usr/bin/google-chrome-stable");
                paths.add("/opt/google/chrome/chrome");
            } else {
                paths.add("/usr/bin/microsoft-edge");
                paths.add("/opt/microsoft/msedge/msedge");
            }
        }
        for (String candidate : paths) {
            if (new File(candidate).isFile()) {
                return true;
            }
        }
        return false;
    }

    /** The running JVM's launcher (the host itself is the Java 8 baseline). */
    public static String currentJvmJava() {
        String home = System.getProperty("java.home", "");
        if (home.isEmpty()) {
            return "";
        }
        File java = new File(home, "bin" + File.separator + (isWindows() ? "java.exe" : "java"));
        return existingPath(java);
    }

    /** The assembled distribution directory, or null when not provided/absent. */
    public static File distributionDirectory() {
        String dir = System.getProperty(RUNTIME_DIR_PROPERTY, "");
        if (dir.isEmpty()) {
            return null;
        }
        File file = new File(dir);
        return file.isDirectory() ? file : null;
    }

    /**
     * Find a Java 21 launcher: explicit override property first, then the standard install roots
     * (~/.jdks, Program Files vendors, /usr/lib/jvm, /Library/Java), verified via each JDK's
     * {@code release} file. @return the launcher path or "" when none is found (reported readably by
     * validation — never a silent guess).
     */
    public static String locateJava21() {
        String override = System.getProperty(JAVA21_PROPERTY, "");
        if (!override.isEmpty() && new File(override).isFile()) {
            return new File(override).getAbsolutePath();
        }
        // If AskAI itself already runs on >= 21, no separate runtime is needed at all.
        if (currentJvmIsAtLeast(21)) {
            String current = currentJvmJava();
            if (!current.isEmpty()) {
                return current;
            }
        }
        for (File root : candidateJdkRoots()) {
            File[] children = root.listFiles();
            if (children == null) {
                continue;
            }
            for (File jdk : children) {
                if (jdk.isDirectory() && isJava21(jdk)) {
                    String launcher = existingPath(new File(jdk,
                            "bin" + File.separator + (isWindows() ? "java.exe" : "java")));
                    if (!launcher.isEmpty()) {
                        return launcher;
                    }
                }
            }
        }
        return "";
    }

    private static File[] candidateJdkRoots() {
        String userHome = System.getProperty("user.home", "");
        String programFiles = System.getenv("ProgramFiles");
        return new File[]{
                new File(userHome, ".jdks"),
                programFiles == null ? new File("/nonexistent") : new File(programFiles, "Java"),
                programFiles == null ? new File("/nonexistent")
                        : new File(programFiles, "Eclipse Adoptium"),
                programFiles == null ? new File("/nonexistent") : new File(programFiles, "Zulu"),
                new File("/usr/lib/jvm"),
                new File("/Library/Java/JavaVirtualMachines")
        };
    }

    /** True when the JDK's release file declares JAVA_VERSION="21...". */
    static boolean isJava21(File jdkDir) {
        File release = new File(jdkDir, "release");
        if (!release.isFile()) {
            release = new File(new File(jdkDir, "Contents/Home"), "release"); // macOS bundle layout
            if (!release.isFile()) {
                return false;
            }
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(release));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("JAVA_VERSION=")) {
                    String version = line.substring("JAVA_VERSION=".length()).replace("\"", "");
                    return version.equals("21") || version.startsWith("21.");
                }
            }
        } catch (IOException ignored) {
            // unreadable candidate → not usable
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    static boolean currentJvmIsAtLeast(int major) {
        String spec = System.getProperty("java.specification.version", "");
        try {
            // "1.8" (Java 8) vs "9".."21" (Java 9+).
            int version = spec.startsWith("1.") ? Integer.parseInt(spec.substring(2))
                    : Integer.parseInt(spec);
            return version >= major;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String existingPath(File file) {
        return file.isFile() ? file.getAbsolutePath() : "";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
