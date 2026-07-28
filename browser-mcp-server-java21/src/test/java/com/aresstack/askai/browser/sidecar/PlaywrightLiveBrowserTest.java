package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.WebSearchResult;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * THE productive acceptance for 36B: a REAL locally installed Chromium-channel browser, driven through the
 * official Playwright API on playwright4j's GraalJS driver, against a local test server whose content and
 * links exist ONLY after JavaScript execution — a static HTTP fetch cannot pass this test. Covers: dynamic
 * rendering, JS-created links via web_links semantics, follow, back, redirect handling, search via a local
 * provider page, and controlled shutdown.
 *
 * <p>ENVIRONMENT-GATED, not silently green: when the capability probe is not READY (no Chrome/Edge
 * installed), the test SKIPS with the probe status as the message. It never downloads a browser.</p>
 */
public class PlaywrightLiveBrowserTest {

    private static final String START_PAGE = "<!doctype html><html><head><title>JS Start</title></head>"
            + "<body><div id='content'></div><script>"
            + "document.getElementById('content').textContent='Rendered by JavaScript';"
            + "var a=document.createElement('a');a.href='/second';"
            + "a.textContent='go to second page';document.body.appendChild(a);"
            + "</script></body></html>";

    private static final String SECOND_PAGE = "<!doctype html><html><head><title>Second</title></head>"
            + "<body><div id='c'></div><script>"
            + "document.getElementById('c').textContent='Second page rendered';"
            + "</script></body></html>";

    private static final String SEARCH_PAGE = "<!doctype html><html><head><title>Find</title></head>"
            + "<body><div id='r'></div><script>"
            + "var a=document.createElement('a');a.href='/start';"
            + "a.textContent='JS Start (result)';document.getElementById('r').appendChild(a);"
            + "</script></body></html>";

    @Test
    public void realBrowserRendersJavaScriptFollowsLinksAndShutsDownCleanly() throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(), readiness.isReady());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", page(START_PAGE));
        server.createContext("/second", page(SECOND_PAGE));
        server.createContext("/find", page(SEARCH_PAGE));
        server.createContext("/redirect", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Location", "/second");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        BrowserSession session = PlaywrightSessionFactory.create(channel, true, true,
                base + "/find?q={query}", BrowserLimits.defaults());
        try {
            assertEquals(BrowserBackendKind.PLAYWRIGHT_SIDECAR, session.getBackendKind());

            // 1) JavaScript-rendered content — the proof this is a browser, not an HTTP fetch.
            BrowserPageSnapshot start = session.open(base + "/start");
            assertEquals("JS Start", start.getTitle());
            assertTrue("dynamic text must be visible: " + start.getText(),
                    start.getText().contains("Rendered by JavaScript"));

            // 2) The link exists only in the JS-built DOM.
            List<BrowserLink> links = session.links();
            assertEquals(1, links.size());
            assertEquals("go to second page", links.get(0).getText());

            // 3) Follow → dynamically rendered second page.
            BrowserPageSnapshot second = session.follow(links.get(0).getId());
            assertTrue(second.getUrl().endsWith("/second"));
            assertTrue(second.getText().contains("Second page rendered"));

            // 4) Back → the start page is current again (still rendered).
            BrowserPageSnapshot backAtStart = session.back();
            assertTrue(backAtStart.getUrl().endsWith("/start"));
            assertTrue(backAtStart.getText().contains("Rendered by JavaScript"));

            // 5) Redirects: the snapshot reports the FINAL url (the policy check ran against it).
            BrowserPageSnapshot redirected = session.open(base + "/redirect");
            assertTrue("snapshot must carry the post-redirect url: " + redirected.getUrl(),
                    redirected.getUrl().endsWith("/second"));

            // 6) Search = navigation to the local provider; results extracted from the JS-built links.
            WebSearchResult found = session.search("anything");
            assertEquals(1, found.getItems().size());
            assertEquals("JS Start (result)", found.getItems().get(0).getTitle());

            // 7) Scheme gate stays active on the live backend.
            try {
                session.open("file:///C:/Windows/win.ini");
                fail("file: must be blocked");
            } catch (BrowserException expected) {
                assertTrue(expected.getMessage().contains("scheme"));
            }
        } finally {
            // 8) Controlled, idempotent shutdown: browser + GraalJS driver child end with the session.
            session.close();
            session.close();
            server.stop(0);
        }
    }

    private static HttpHandler page(final String html) {
        return new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                OutputStream out = exchange.getResponseBody();
                out.write(body);
                exchange.close();
            }
        };
    }
}
