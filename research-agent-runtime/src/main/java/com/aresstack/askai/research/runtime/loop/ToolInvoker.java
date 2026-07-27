package com.aresstack.askai.research.runtime.loop;

import java.util.Map;

/**
 * The loop's only way to act: an MCP tool call on one endpoint (browser or research control). The production
 * implementation wraps a Solon MCP client; unit tests use deterministic fakes. A tool-level failure is thrown
 * as ToolFailure; an unreachable endpoint as EndpointUnavailable.
 */
public interface ToolInvoker {

    String call(String toolName, Map<String, Object> args) throws ToolFailure, EndpointUnavailable;

    class ToolFailure extends Exception {
        public ToolFailure(String message) {
            super(message);
        }
    }

    class EndpointUnavailable extends Exception {
        public EndpointUnavailable(String message) {
            super(message);
        }
    }
}
