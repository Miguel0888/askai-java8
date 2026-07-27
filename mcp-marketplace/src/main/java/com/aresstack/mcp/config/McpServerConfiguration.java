package com.aresstack.mcp.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The neutral, persisted description of one MCP server. This is DATA ONLY: saving/holding a configuration
 * never starts a process or opens a connection. Installation and activation are separate steps —
 * {@code enabled} defaults to {@code false} and must be flipped explicitly by the user; the actual runtime
 * connection happens elsewhere, and only for enabled configurations.
 */
public final class McpServerConfiguration {

    private final String id;
    private final String displayName;
    private final McpTransport transport;
    private final String command;
    private final List<String> arguments;
    private final String endpoint;
    private final Map<String, String> environment;
    private final Map<String, String> headers;
    private final boolean enabled;
    private final McpApprovalPolicy approvalPolicy;

    private McpServerConfiguration(Builder b) {
        if (b.id == null || b.id.trim().isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        if (b.transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        if (b.transport == McpTransport.STDIO) {
            if (b.command == null || b.command.trim().isEmpty()) {
                throw new IllegalArgumentException("a STDIO server needs a command");
            }
        } else {
            if (b.endpoint == null || b.endpoint.trim().isEmpty()) {
                throw new IllegalArgumentException("an HTTP server needs an endpoint URL");
            }
        }
        this.id = b.id.trim();
        this.displayName = b.displayName == null || b.displayName.trim().isEmpty() ? this.id : b.displayName;
        this.transport = b.transport;
        this.command = b.command == null ? "" : b.command;
        this.arguments = Collections.unmodifiableList(new ArrayList<String>(b.arguments));
        this.endpoint = b.endpoint == null ? "" : b.endpoint;
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<String, String>(b.environment));
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String>(b.headers));
        this.enabled = b.enabled;
        this.approvalPolicy = b.approvalPolicy == null ? McpApprovalPolicy.ALWAYS_ASK : b.approvalPolicy;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public McpTransport getTransport() {
        return transport;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArguments() {
        return arguments;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public McpApprovalPolicy getApprovalPolicy() {
        return approvalPolicy;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** @return a copy with {@code enabled} flipped — the explicit user activation step. */
    public McpServerConfiguration withEnabled(boolean value) {
        Builder b = builder(id).displayName(displayName).transport(transport).command(command)
                .arguments(arguments).endpoint(endpoint).environment(environment).headers(headers)
                .approvalPolicy(approvalPolicy);
        b.enabled = value;
        return b.build();
    }

    public static final class Builder {
        private final String id;
        private String displayName;
        private McpTransport transport;
        private String command;
        private List<String> arguments = new ArrayList<String>();
        private String endpoint;
        private Map<String, String> environment = new LinkedHashMap<String, String>();
        private Map<String, String> headers = new LinkedHashMap<String, String>();
        private boolean enabled; // deliberately false: saving never activates
        private McpApprovalPolicy approvalPolicy = McpApprovalPolicy.ALWAYS_ASK;

        private Builder(String id) {
            this.id = id;
        }

        public Builder displayName(String v) {
            this.displayName = v;
            return this;
        }

        public Builder transport(McpTransport v) {
            this.transport = v;
            return this;
        }

        public Builder command(String v) {
            this.command = v;
            return this;
        }

        public Builder arguments(List<String> v) {
            this.arguments = new ArrayList<String>(v == null ? Collections.<String>emptyList() : v);
            return this;
        }

        public Builder endpoint(String v) {
            this.endpoint = v;
            return this;
        }

        public Builder environment(Map<String, String> v) {
            this.environment = new LinkedHashMap<String, String>(
                    v == null ? Collections.<String, String>emptyMap() : v);
            return this;
        }

        public Builder headers(Map<String, String> v) {
            this.headers = new LinkedHashMap<String, String>(
                    v == null ? Collections.<String, String>emptyMap() : v);
            return this;
        }

        public Builder approvalPolicy(McpApprovalPolicy v) {
            this.approvalPolicy = v;
            return this;
        }

        public McpServerConfiguration build() {
            return new McpServerConfiguration(this);
        }
    }
}
