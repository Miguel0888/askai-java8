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
 * ever downloaded or installed.
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
                sidecarJar, settings.getBrowserChannel(), settings.isHeadless(),
                settings.getSearchUrlTemplate(), settings.isAllowPrivateNetworks());
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
