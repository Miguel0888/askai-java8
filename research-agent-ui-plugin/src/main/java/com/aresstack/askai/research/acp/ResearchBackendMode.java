package com.aresstack.askai.research.acp;

/**
 * Which {@link com.aresstack.askai.research.backend.ResearchSessionBackend} implementation a session uses.
 * The UI never knows which one is active — both live behind the same port; contract tests run against both
 * where functionally possible. FAKE stays fully functional (demo + deterministic tests); ACP drives the
 * external agent process.
 */
public enum ResearchBackendMode {
    FAKE,
    ACP
}
