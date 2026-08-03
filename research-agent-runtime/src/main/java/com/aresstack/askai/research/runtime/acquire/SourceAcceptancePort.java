package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

/**
 * The seam through which the deterministic web-acquisition path accepts a visited capture as a persistent
 * research source, WITHOUT knowing whether the caller is the autonomous agent loop or a user-triggered service.
 * The two callers differ only in origin/authorization, never in the acquisition code:
 * <pre>
 *   AgentSourceAcceptancePort   → source_accept          (phase-gated agent MCP tool)
 *   ManualSourceAcceptancePort  → manual_source_accept   (internal, phase-independent; wired in T2c)
 * </pre>
 * The returned string is the acceptance result line exactly as the underlying tool returns it
 * ({@code status=ACCEPTED source_id=… duplicate=…}); {@code null}/unknown handling stays with the caller so
 * behavior is preserved during the extraction.
 */
public interface SourceAcceptancePort {

    String accept(String captureId) throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;
}
