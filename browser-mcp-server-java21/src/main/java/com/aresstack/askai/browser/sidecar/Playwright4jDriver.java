package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@link PlaywrightDriver} over the OFFICIAL Playwright Java API, executed by playwright4j: the Driver
 * replacement spawns a second Java process ({@code GraalDriverMain}) hosting the Playwright Core JS driver on
 * GraalJS, which in turn launches the locally installed Chrome/Edge via the channel mechanism. Nothing is
 * downloaded ({@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1} is always set).
 *
 * <p>Lifecycle owned here (one research session per sidecar process): Playwright → Browser → one
 * BrowserContext → one Page + history. Popups are closed immediately, downloads are refused, and when a
 * request filter is given, browser-side requests to disallowed targets are aborted via route interception.
 * {@link #close()} tears down page → context → browser → Playwright (ending the driver child) and is
 * idempotent.</p>
 */
final class Playwright4jDriver implements PlaywrightDriver {

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final Page page;
    private volatile boolean closed;

    private Playwright4jDriver(Playwright playwright, Browser browser, BrowserContext context, Page page) {
        this.playwright = playwright;
        this.browser = browser;
        this.context = context;
        this.page = page;
    }

    /**
     * Launch the full chain. @param requestAllowed null = no interception; otherwise every browser-side
     * request URL is checked and aborted when not allowed (cheap best-effort SSRF net below the
     * authoritative post-navigation URL-policy check in the session).
     */
    static Playwright4jDriver launch(String channel, boolean headless, int timeoutMillis,
                                     final Predicate<String> requestAllowed) throws BrowserException {
        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        try {
            Map<String, String> env = new LinkedHashMap<String, String>();
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            env.put("PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS", "1");
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setChannel(channel)
                    .setHeadless(headless));
            context = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(false));
            context.setDefaultNavigationTimeout(timeoutMillis);
            context.setDefaultTimeout(timeoutMillis);
            if (requestAllowed != null) {
                context.route("**/*", route -> {
                    if (requestAllowed.test(route.request().url())) {
                        route.resume();
                    } else {
                        route.abort();
                    }
                });
            }
            Page page = context.newPage();
            page.onPopup(new Consumer<Page>() {
                public void accept(Page popup) {
                    try {
                        popup.close();
                    } catch (RuntimeException ignored) {
                        // A popup that is already gone must not take the session down.
                    }
                }
            });
            return new Playwright4jDriver(playwright, browser, context, page);
        } catch (RuntimeException ex) {
            closeQuietly(context, browser, playwright);
            throw new BrowserException("Browser start failed (channel=" + channel + "): " + firstLine(ex));
        }
    }

    @Override
    public PlaywrightPageState open(String url) throws BrowserException {
        requireOpen();
        try {
            page.navigate(url);
            return state();
        } catch (PlaywrightException ex) {
            throw new BrowserException("Navigation failed: " + firstLine(ex));
        }
    }

    @Override
    public PlaywrightPageState current() throws BrowserException {
        requireOpen();
        try {
            return state();
        } catch (PlaywrightException ex) {
            throw new BrowserException("Reading the current page failed: " + firstLine(ex));
        }
    }

    @Override
    public PlaywrightPageState back() throws BrowserException {
        requireOpen();
        try {
            if (page.goBack() == null) {
                throw new BrowserException("No previous page in history.");
            }
            return state();
        } catch (PlaywrightException ex) {
            throw new BrowserException("Going back failed: " + firstLine(ex));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            page.close();
        } catch (RuntimeException ignored) {
        }
        closeQuietly(context, browser, playwright);
    }

    /** RENDERED state via the DOM: url after redirects, title, body innerText, anchors with absolute hrefs. */
    private PlaywrightPageState state() {
        String url = page.url();
        String title = page.title();
        String text = page.innerText("body");
        List<PlaywrightPageState.Anchor> anchors = new ArrayList<PlaywrightPageState.Anchor>();
        Object raw = page.evaluate(
                "() => Array.from(document.querySelectorAll('a[href]'))"
                        + ".map(a => [String(a.innerText || '').trim(), String(a.href || '')])");
        if (raw instanceof List) {
            for (Object entry : (List<?>) raw) {
                if (entry instanceof List && ((List<?>) entry).size() == 2) {
                    List<?> pair = (List<?>) entry;
                    anchors.add(new PlaywrightPageState.Anchor(
                            String.valueOf(pair.get(0)), String.valueOf(pair.get(1))));
                }
            }
        }
        return new PlaywrightPageState(url, title, text, anchors);
    }

    private void requireOpen() throws BrowserException {
        if (closed) {
            throw new BrowserException("Browser session is closed.");
        }
    }

    private static void closeQuietly(BrowserContext context, Browser browser, Playwright playwright) {
        try {
            if (context != null) {
                context.close();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Tool errors carry the reason, never a multi-line driver stack trace. */
    private static String firstLine(Throwable ex) {
        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline).trim() : message.trim();
    }
}
