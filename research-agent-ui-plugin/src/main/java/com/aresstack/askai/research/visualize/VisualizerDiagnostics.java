package com.aresstack.askai.research.visualize;

/**
 * Compact, non-sensitive trace of the lazy-visualizer path to STDERR (the runWithDevPlugins console /
 * technical output). It logs ONLY control-flow markers — hashes (shortened), booleans, result kinds and
 * character counts — never prompts, artifact contents, credentials or model responses.
 */
public final class VisualizerDiagnostics {

    private VisualizerDiagnostics() {
    }

    public static void log(String message) {
        System.err.println("[visualizer] " + message);
    }

    /** A short, non-reversible marker of a content hash for correlating log lines. */
    public static String shortHash(String hash) {
        if (hash == null || hash.isEmpty()) {
            return "none";
        }
        return hash.length() <= 8 ? hash : hash.substring(0, 8);
    }
}
