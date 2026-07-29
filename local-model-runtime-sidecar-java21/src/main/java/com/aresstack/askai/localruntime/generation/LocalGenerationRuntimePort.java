package com.aresstack.askai.localruntime.generation;

/**
 * The AskAI-internal boundary to a local text-generation runtime. This is the ONLY seam the productive
 * win-directml generation library plugs into; nothing above this interface depends on that library. Until
 * a runtime is linked, the {@link NotLinkedGenerationRuntimePort} answers every load with
 * {@link LocalGenerationErrorCode#RUNTIME_NOT_LINKED}, and tests use a fake port.
 */
public interface LocalGenerationRuntimePort {

    /** Load a model from its compiled package (PACKAGE_ONLY) into a handle. */
    LoadedGenerationHandle load(LocalGenerationLoadRequest request) throws LocalGenerationException;

    /**
     * Whether a productive generation runtime is actually linked behind this port. The UI reads this
     * (never a hardcoded family list or a build-version compare) to decide whether generation models are
     * runnable or still pending. A real port returns {@code true}; {@link NotLinkedGenerationRuntimePort}
     * returns {@code false}.
     */
    default boolean isLinked() {
        return true;
    }
}
