package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;

import java.util.Set;

/**
 * Pure compatibility logic (no PF4J types), so it is fully unit-testable: it compares a discovered plugin's
 * descriptor against the PF4J manifest, the host's supported plugin-API version and the set of ids already
 * seen. The first failing rule wins, yielding a clear verdict instead of a later ClassCastException.
 */
public final class PluginCompatibilityChecker {

    private final int supportedPluginApiVersion;

    public PluginCompatibilityChecker(int supportedPluginApiVersion) {
        this.supportedPluginApiVersion = supportedPluginApiVersion;
    }

    /**
     * @param descriptor      the descriptor returned by the extension (never null)
     * @param manifestId      the PF4J {@code Plugin-Id} from the manifest
     * @param manifestVersion the PF4J {@code Plugin-Version} from the manifest
     * @param extensionCount  how many workspace extensions the plugin exposes
     * @param alreadySeenIds  descriptor ids already accepted from other plugins
     */
    public PluginCompatibility check(WorkspacePluginDescriptor descriptor, String manifestId,
                                     String manifestVersion, int extensionCount, Set<String> alreadySeenIds) {
        if (extensionCount == 0) {
            return PluginCompatibility.MISSING_EXTENSION;
        }
        if (extensionCount > 1) {
            return PluginCompatibility.MULTIPLE_EXTENSIONS;
        }
        if (descriptor.getPluginApiVersion() != supportedPluginApiVersion) {
            return PluginCompatibility.INCOMPATIBLE_API_VERSION;
        }
        if (manifestId != null && !manifestId.trim().isEmpty()
                && !manifestId.trim().equals(descriptor.getId())) {
            return PluginCompatibility.ID_MISMATCH;
        }
        if (manifestVersion != null && !manifestVersion.trim().isEmpty()
                && !manifestVersion.trim().equals(descriptor.getVersion())) {
            return PluginCompatibility.VERSION_MISMATCH;
        }
        if (alreadySeenIds != null && alreadySeenIds.contains(descriptor.getId())) {
            return PluginCompatibility.DUPLICATE_ID;
        }
        return PluginCompatibility.COMPATIBLE;
    }
}
