package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.HashMap;
import java.util.Map;

/**
 * The USER-triggered search's source-acceptance route: the internal, phase-INDEPENDENT
 * {@code manual_source_accept} tool on the host's service endpoint (never an agent tool). It delegates to the
 * SAME host-side {@code SourceAcceptanceService} the agent's phase-gated {@code source_accept} uses — the only
 * difference is the endpoint/authorization, never the acquisition or acceptance logic. Return contract is
 * identical to {@link AgentSourceAcceptancePort} (the acceptance result line), so the shared
 * {@link com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService} treats both the same.
 */
public final class ManualSourceAcceptancePort implements SourceAcceptancePort {

    private final ToolInvoker service;

    public ManualSourceAcceptancePort(ToolInvoker service) {
        this.service = service;
    }

    @Override
    public String accept(String captureId) throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("capture_id", captureId);
        return service.call("manual_source_accept", args);
    }
}
