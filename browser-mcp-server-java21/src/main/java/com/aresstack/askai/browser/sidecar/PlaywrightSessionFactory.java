package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.WebSearchResult;

import java.util.List;

/**
 * Creates the Playwright-backed {@link BrowserSession} for the sidecar. playwright4j 0.1.0 is a GraalJS
 * runtime hosting the Playwright JS driver (no high-level Java Browser/Page API), so the full driver
 * orchestration is a dedicated work item — tracked as problems.md MCP-P005. Until it lands, every tool call
 * reports a readable NOT_INSTALLED-style error; a missing driver or browser binary NEVER breaks packaging,
 * the host build, or the MCP endpoint itself.
 */
final class PlaywrightSessionFactory {

    private PlaywrightSessionFactory() {
    }

    static BrowserSession createOrUnavailable() {
        // Driver orchestration over GraalPlaywrightRuntime is not implemented yet (MCP-P005); report honestly.
        return new UnavailableSession("Playwright driver orchestration is not implemented in this build "
                + "(playwright4j 0.1.0 exposes only the raw GraalJS driver runtime; see problems.md MCP-P005). "
                + "Run the STATIC_HTTP backend for static pages.");
    }

    /** Every call fails with the same readable reason; close() is a no-op. */
    private static final class UnavailableSession implements BrowserSession {
        private final String reason;

        private UnavailableSession(String reason) {
            this.reason = reason;
        }

        public BrowserBackendKind getBackendKind() {
            return BrowserBackendKind.PLAYWRIGHT_SIDECAR;
        }

        public WebSearchResult search(String query) throws BrowserException {
            throw new BrowserException(reason);
        }

        public BrowserPageSnapshot open(String url) throws BrowserException {
            throw new BrowserException(reason);
        }

        public BrowserPageSnapshot currentPage() throws BrowserException {
            throw new BrowserException(reason);
        }

        public List<BrowserLink> links() throws BrowserException {
            throw new BrowserException(reason);
        }

        public BrowserPageSnapshot follow(String linkId) throws BrowserException {
            throw new BrowserException(reason);
        }

        public BrowserPageSnapshot back() throws BrowserException {
            throw new BrowserException(reason);
        }

        public void close() {
        }
    }
}
