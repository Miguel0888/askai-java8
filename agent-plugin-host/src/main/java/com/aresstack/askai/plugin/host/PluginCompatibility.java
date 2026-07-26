package com.aresstack.askai.plugin.host;

/**
 * Result of validating a discovered plugin against the host. Only {@link #COMPATIBLE} plugins are offered in
 * the normal mode selector; everything else is surfaced as a clear status in plugin management rather than
 * failing later with a random ClassCastException.
 */
public enum PluginCompatibility {
    COMPATIBLE,
    INCOMPATIBLE_API_VERSION,
    INCOMPATIBLE_HOST_VERSION,
    DUPLICATE_ID,
    ID_MISMATCH,
    VERSION_MISMATCH,
    MISSING_EXTENSION,
    MULTIPLE_EXTENSIONS,
    DESCRIPTOR_INVALID
}
