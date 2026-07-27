package com.aresstack.askai.browser;

import java.util.List;

/**
 * The browser port behind the MCP web_* tools. One session per research session. Implementations differ in
 * capability and MUST be visibly selected via {@link BrowserBackendKind} — there is no silent fallback from
 * the Playwright sidecar to the static backend. Failures are reported as {@link BrowserException} (mapped to
 * tool errors), never as raw stack traces to the model.
 */
public interface BrowserSession {

    BrowserBackendKind getBackendKind();

    WebSearchResult search(String query) throws BrowserException;

    BrowserPageSnapshot open(String url) throws BrowserException;

    BrowserPageSnapshot currentPage() throws BrowserException;

    List<BrowserLink> links() throws BrowserException;

    BrowserPageSnapshot follow(String linkId) throws BrowserException;

    BrowserPageSnapshot back() throws BrowserException;

    void close();
}
