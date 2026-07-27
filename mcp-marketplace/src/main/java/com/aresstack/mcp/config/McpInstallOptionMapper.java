package com.aresstack.mcp.config;

import com.aresstack.mcp.marketplace.McpInstallOption;

import java.util.Locale;

/**
 * Maps a marketplace {@link McpInstallOption} onto the neutral {@link McpServerConfiguration}. This is the
 * "Save Configuration" step of the flow (Search → Select → Preview → Save → explicit Enable → Runtime
 * Connection): the result is ALWAYS disabled — selecting or importing a marketplace entry must never start a
 * foreign process on its own. Environment and headers are carried over verbatim.
 */
public final class McpInstallOptionMapper {

    private McpInstallOptionMapper() {
    }

    /**
     * @throws IllegalArgumentException for an unknown transport type or a structurally invalid option
     *         (missing command for stdio, missing url for http), with a readable message.
     */
    public static McpServerConfiguration toConfiguration(String id, String displayName,
                                                         McpInstallOption option) {
        if (option == null) {
            throw new IllegalArgumentException("install option must not be null");
        }
        McpTransport transport = transportOf(option.getType());
        McpServerConfiguration.Builder builder = McpServerConfiguration.builder(id)
                .displayName(displayName)
                .transport(transport)
                .environment(option.getEnv())
                .headers(option.getHeaders());
        if (transport == McpTransport.STDIO) {
            builder.command(option.getCommand()).arguments(option.getArgs());
        } else {
            builder.endpoint(option.getUrl());
        }
        return builder.build(); // enabled=false by construction
    }

    /** Marketplace type string → transport. Unknown types are rejected, never guessed. */
    public static McpTransport transportOf(String type) {
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if ("stdio".equals(t) || "local".equals(t) || t.isEmpty()) {
            return McpTransport.STDIO;
        }
        if ("http".equals(t) || "sse".equals(t)) {
            return McpTransport.HTTP;
        }
        if ("streamable-http".equals(t) || "streamable_http".equals(t) || "streamablehttp".equals(t)
                || "streamable".equals(t)) {
            return McpTransport.STREAMABLE_HTTP;
        }
        throw new IllegalArgumentException("Unknown MCP transport type: " + type);
    }
}
