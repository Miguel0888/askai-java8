package com.aresstack.askai.java8.localmodels;

import com.aresstack.windirectml.catalog.CatalogModelFamily;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ModelStatus;

/**
 * Resolves how a repository should be installed locally, driven EXCLUSIVELY by the neutral catalog
 * ({@link LocalRuntimeModelDescriptor#runtimeFamily()} / {@link LocalRuntimeModelDescriptor#packageLifecycleId()})
 * — never by a model-name heuristic. C2 productively supports the encoder and reranker families through the
 * published {@code EncoderPackageLifecycle}; the native generation families are catalogued but their local
 * installation is not wired yet and is reported as a clear typed state rather than being force-fed into the
 * reranker installer.
 */
public final class LocalModelInstallResolution {

    /** The outcome kind of resolving a repository against the catalog. */
    public enum Kind {
        /** An embedding encoder (encoder.wdmlpack) — installable in C2. */
        ENCODER,
        /** A cross-encoder reranker (reranker.wdmlpack) — installable in C2. */
        RERANKER,
        /** A catalogued native generation family whose local installer is not implemented yet. */
        LOCAL_RUNTIME_FAMILY_NOT_AVAILABLE_YET,
        /** Present in the catalog but not RUNNABLE (e.g. the UNVERIFIED L-12 reranker). */
        NOT_RUNNABLE,
        /** Not a catalogued local-engine model at all. */
        NOT_IN_CATALOG
    }

    private final Kind kind;
    private final LocalRuntimeModelDescriptor descriptor;
    private final String message;

    private LocalModelInstallResolution(Kind kind, LocalRuntimeModelDescriptor descriptor, String message) {
        this.kind = kind;
        this.descriptor = descriptor;
        this.message = message;
    }

    public Kind getKind() {
        return kind;
    }

    /** The catalog descriptor, or {@code null} for {@link Kind#NOT_IN_CATALOG}. */
    public LocalRuntimeModelDescriptor getDescriptor() {
        return descriptor;
    }

    public String getMessage() {
        return message;
    }

    /** Whether C2's encoder/reranker installer can install this repository right now. */
    public boolean isInstallable() {
        return kind == Kind.ENCODER || kind == Kind.RERANKER;
    }

    /** Resolve a repository id against the catalog. */
    public static LocalModelInstallResolution resolve(String repositoryId) {
        LocalRuntimeModelDescriptor descriptor = LocalModelCatalog.findByRepositoryId(repositoryId);
        if (descriptor == null) {
            return new LocalModelInstallResolution(Kind.NOT_IN_CATALOG, null,
                    "'" + repositoryId + "' is not a catalogued AskAI local-engine model.");
        }
        if (descriptor.status() != ModelStatus.RUNNABLE) {
            return new LocalModelInstallResolution(Kind.NOT_RUNNABLE, descriptor,
                    "'" + repositoryId + "' is catalogued but not RUNNABLE (status="
                            + descriptor.status().token() + "); it is not offered for local installation.");
        }
        CatalogModelFamily family = descriptor.runtimeFamily();
        if (family == CatalogModelFamily.MINILM || family == CatalogModelFamily.E5) {
            return new LocalModelInstallResolution(Kind.ENCODER, descriptor, null);
        }
        if (family == CatalogModelFamily.CROSS_ENCODER) {
            return new LocalModelInstallResolution(Kind.RERANKER, descriptor, null);
        }
        return new LocalModelInstallResolution(Kind.LOCAL_RUNTIME_FAMILY_NOT_AVAILABLE_YET, descriptor,
                "The local runtime family '" + family.token() + "' ("
                        + descriptor.huggingFaceRepositoryId() + ") is catalogued but its local installer "
                        + "is not available yet; it will be enabled in a later commit.");
    }
}
