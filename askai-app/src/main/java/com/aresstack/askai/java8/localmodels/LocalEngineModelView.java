package com.aresstack.askai.java8.localmodels;

import com.aresstack.windirectml.catalog.CatalogModelFamily;
import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ModelCapability;

import java.util.List;

/**
 * Pure, catalog-driven presentation of an AskAI local-engine model, keyed by its virtual model id
 * ({@code local/<repository>:latest}). Every fact (family, capabilities, backends, package) comes from
 * {@link LocalModelCatalog} — never from a hardcoded "rerank / CPU" string and never from a name heuristic.
 * Non-runnable generation families are reported as runtime-integration-pending rather than pretending to be
 * runnable.
 */
public final class LocalEngineModelView {

    private LocalEngineModelView() {
    }

    /** The repository id encoded in a {@code local/<repo>:latest} virtual name, or "" when not one. */
    public static String repositoryOf(String virtualName) {
        if (virtualName == null) {
            return "";
        }
        String name = virtualName.trim();
        if (!name.startsWith(LocalModelNames.LOCAL_PREFIX)) {
            return "";
        }
        name = name.substring(LocalModelNames.LOCAL_PREFIX.length());
        int colon = name.lastIndexOf(':');
        if (colon >= 0) {
            name = name.substring(0, colon);
        }
        return name;
    }

    /** The catalog descriptor for a virtual model id, or {@code null} when it is not catalogued. */
    public static LocalRuntimeModelDescriptor descriptorOf(String virtualName) {
        return LocalModelCatalog.findByRepositoryId(repositoryOf(virtualName));
    }

    /** Whether this virtual model advertises a capability, per the catalog (never per its name). */
    public static boolean hasCapability(String virtualName, ModelCapability capability) {
        LocalRuntimeModelDescriptor descriptor = descriptorOf(virtualName);
        return descriptor != null && descriptor.hasCapability(capability);
    }

    /**
     * Whether this is a native generation family whose local runtime is not linked into AskAI yet, so the
     * UI must not claim it is runnable.
     */
    public static boolean isRuntimePending(String virtualName) {
        LocalRuntimeModelDescriptor descriptor = descriptorOf(virtualName);
        if (descriptor == null) {
            return false;
        }
        CatalogModelFamily family = descriptor.runtimeFamily();
        return family == CatalogModelFamily.QWEN || family == CatalogModelFamily.SMOLLM2
                || family == CatalogModelFamily.GEMMA3 || family == CatalogModelFamily.PHI3
                || family == CatalogModelFamily.T5;
    }

    /**
     * The family-aware detail line for a local-engine card. For an encoder/reranker it lists family,
     * capabilities and the catalogued backends; for a not-yet-linked generation family it states the
     * pending status instead of a success claim; for an unknown virtual name it degrades readably.
     */
    public static String detailLine(String virtualName) {
        LocalRuntimeModelDescriptor descriptor = descriptorOf(virtualName);
        if (descriptor == null) {
            return "AskAI Local Engine · Runtime: win-directml-java";
        }
        if (isRuntimePending(virtualName)) {
            return "Family: " + descriptor.runtimeFamily().displayName()
                    + " · Catalogued for AskAI Local Engine · Runtime integration pending";
        }
        return "Family: " + descriptor.runtimeFamily().displayName()
                + " · Capability: " + join(InstalledModelManifest.expectedCapabilityTokens(descriptor))
                + " · Backend: " + join(InstalledModelManifest.expectedBackendTokens(descriptor))
                + " · Runtime: win-directml-java";
    }

    private static String join(List<String> tokens) {
        return tokens.isEmpty() ? "?" : String.join(", ", tokens);
    }
}
