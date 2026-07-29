package com.aresstack.askai.agent.model.reranker;

import java.io.File;

/**
 * The NEUTRAL host port through which the research agent host publishes a mandatory reranker start
 * snapshot for a session. The host implementation validates the explicitly selected rerank-capable
 * local model, ensures its runtime is started, and writes an atomic per-session snapshot; it returns
 * only the neutral {@link RerankerConfigurationSnapshot} (a file path plus the validated document).
 *
 * <p>The agent plugin obtains this port via {@code AgentHostContext.getService(...)} and therefore never
 * depends on the host application, its model manager, or any GPU/runtime types. Preparing a snapshot
 * NEVER silently falls back to a "first found" model: without an explicitly usable rerank model the call
 * throws {@link RerankerConfigurationException} and the productive session start fails visibly.
 */
public interface RerankerConfigurationSnapshotProvider {

    /**
     * Prepare and publish the reranker snapshot for the given session.
     *
     * @param sessionId        the session identifier (diagnostics/labelling only)
     * @param sessionDirectory the session's own directory; the snapshot is written beneath it
     * @param selectedModel    the EXPLICITLY selected virtual model id (persisted research runtime
     *                         setting); the host validates it against the installed models — an empty,
     *                         removed or incompatible selection fails, it is never replaced by a guess
     * @return the published snapshot (absolute file path + validated document)
     * @throws RerankerConfigurationException if the selection is missing or not usable, the runtime
     *                                        cannot be started, or the snapshot cannot be written
     */
    RerankerConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                    String selectedModel)
            throws RerankerConfigurationException;
}
