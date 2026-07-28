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
 * BrowserContext → one Page + history. The ENTIRE object graph lives on a single {@link PlaywrightThread}
 * (Playwright Java is not thread-safe); every method marshals its Playwright work there. Popups are closed
 * immediately, downloads are refused, and when a request filter is given, browser-side requests to disallowed
 * targets are aborted via route interception.</p>
 *
 * <p>After each navigation {@link PageReadiness} waits for the rendered content to settle before the state is
 * read, so JS-injected result lists are not captured half-built. {@link #close()} tears down page → context →
 * browser → Playwright (ending the driver child) on the owning thread and is idempotent.</p>
 */
final class Playwright4jDriver implements PlaywrightDriver {

    private final PlaywrightThread thread;
    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final Page page;
    private final PageReadiness readiness;
    private final int timeoutMillis;
    private volatile boolean closed;

    private Playwright4jDriver(PlaywrightThread thread, Playwright playwright, Browser browser,
                              BrowserContext context, Page page, PageReadiness readiness, int timeoutMillis) {
        this.thread = thread;
        this.playwright = playwright;
        this.browser = browser;
        this.context = context;
        this.page = page;
        this.readiness = readiness;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Launch the full chain ON the Playwright thread. @param requestAllowed null = no interception; otherwise
     * every browser-side request URL is checked and aborted when not allowed (cheap best-effort SSRF net below
     * the authoritative post-navigation URL-policy check in the session).
     */
    static Playwright4jDriver launch(final String channel, final boolean headless, final int timeoutMillis,
                                     final Predicate<String> requestAllowed) throws BrowserException {
        final PlaywrightThread thread = new PlaywrightThread();
        try {
            return thread.call(new PlaywrightThread.Call<Playwright4jDriver>() {
                public Playwright4jDriver run() throws BrowserException {
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
                        return new Playwright4jDriver(thread, playwright, browser, context, page,
                                new PageReadiness(), timeoutMillis);
                    } catch (RuntimeException ex) {
                        closeQuietly(context, browser, playwright);
                        throw new BrowserException("Browser start failed (channel=" + channel + "): "
                                + firstLine(ex));
                    }
                }
            });
        } catch (BrowserException ex) {
            thread.close();
            throw ex;
        }
    }

    @Override
    public PlaywrightPageState open(final String url) throws BrowserException {
        requireOpen();
        return thread.call(new PlaywrightThread.Call<PlaywrightPageState>() {
            public PlaywrightPageState run() throws BrowserException {
                try {
                    page.navigate(url);
                    readiness.awaitContentReady(page, timeoutMillis);
                    return state();
                } catch (PlaywrightException ex) {
                    throw new BrowserException("Navigation failed: " + firstLine(ex));
                }
            }
        });
    }

    @Override
    public PlaywrightPageState current() throws BrowserException {
        requireOpen();
        return thread.call(new PlaywrightThread.Call<PlaywrightPageState>() {
            public PlaywrightPageState run() throws BrowserException {
                try {
                    return state();
                } catch (PlaywrightException ex) {
                    throw new BrowserException("Reading the current page failed: " + firstLine(ex));
                }
            }
        });
    }

    @Override
    public PlaywrightPageState back() throws BrowserException {
        requireOpen();
        return thread.call(new PlaywrightThread.Call<PlaywrightPageState>() {
            public PlaywrightPageState run() throws BrowserException {
                try {
                    if (page.goBack() == null) {
                        throw new BrowserException("No previous page in history.");
                    }
                    readiness.awaitContentReady(page, timeoutMillis);
                    return state();
                } catch (PlaywrightException ex) {
                    throw new BrowserException("Going back failed: " + firstLine(ex));
                }
            }
        });
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Tear down page → context → browser → Playwright on their owning thread, then stop it. Bounded so a
        // wedged navigation cannot block JVM shutdown; the thread is a daemon and is forced down regardless.
        thread.shutdown(new Runnable() {
            public void run() {
                try {
                    page.close();
                } catch (RuntimeException ignored) {
                }
                closeQuietly(context, browser, playwright);
            }
        }, Math.max(1_000, timeoutMillis));
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
