# Browser MCP (Playwright sidecar + static backend)

## Modules

- `:browser-api` (Java 8) — the shared port: `BrowserSession` (search/open/currentPage/links/follow/
  back/close), `BrowserPageSnapshot` (url/title/clean text/truncated — never raw HTML),
  `BrowserLink` (stable per-snapshot ids `link-1..n`), `WebSearchResult`, `UrlSafetyPolicy`,
  `BrowserLimits`, `BrowserBackendKind { PLAYWRIGHT_SIDECAR, STATIC_HTTP }`.
- `:browser-static-http` (Java 8) — `StaticHttpBrowserSession` (jsoup): static fetch + cleanup only;
  search is honestly unsupported. A separate, visibly limited backend — **never** an automatic
  fallback for the sidecar.
- `:browser-mcp-server-java21` — the ONE deliberate Java-21 process. The Java-8 host never compiles
  against it and never loads its classes; it only talks MCP to it.

## How the Playwright backend works (MCP-P005, resolved)

The sidecar uses the OFFICIAL Playwright Java API (`com.microsoft.playwright:playwright:1.59.0`,
with its `driver`/`driver-bundle` modules excluded) plus `com.aresstack:playwright4j:0.1.0`, which
replaces `com.microsoft.playwright.impl.driver.Driver`: instead of a Node.js process it spawns a
second **Java** process (`GraalDriverMain`) hosting the Playwright Core JS driver on GraalJS. The
browser is a **locally installed** Chrome or Edge via the channel mechanism
(`--browser-channel=chrome|msedge`). Firefox/WebKit are not supported by playwright4j 0.1.0.

Process tree: `sidecar JVM → GraalDriverMain JVM → Chromium processes`. playwright4j extracts driver
package assets from the driver-bundle jar to `%TEMP%/playwright4j-driver` (persists between runs).

**Nothing is ever downloaded or installed**: `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` is fixed; browser
discovery only probes standard install locations.

## Capability probing

`PlaywrightCapabilityProbe` reports a SPECIFIC status before the backend is offered:
`READY / INCOMPATIBLE_DRIVER / DRIVER_BUNDLE_NOT_FOUND / BROWSER_NOT_INSTALLED /
BROWSER_START_FAILED`. There is no `NODE_RUNTIME_NOT_FOUND` — playwright4j needs no Node; the
equivalent failure is a missing driver-bundle. When not READY, every tool returns the status as a
readable error; the endpoint stays up and there is no STATIC_HTTP fallback.

## Security

- `UrlSafetyPolicy` runs BEFORE navigation and again on the FINAL URL after redirects; a blocked
  redirect target abandons the page.
- Best-effort in-browser request interception aborts requests to loopback/RFC1918/link-local/
  cloud-metadata literals (strict mode).
- Downloads refused, popups closed immediately, one BrowserContext per session, navigation timeouts
  and text/link limits from `BrowserLimits`.
- `web_search` is navigation to a configured `--search-url={query}` provider (extraction behind
  `WebSearchProvider`); without configuration it fails honestly. No hardcoded search engine.

## Packaging (thin jar + lib/)

A fat jar is NOT viable: repackaging GraalJS/Truffle loses `Multi-Release: true` and collapses
duplicate `META-INF/services` files → `InternalError: Truffle could not be initialized` (found by
the 36C live test). The `sidecarJar` task builds `build/sidecar/browser-mcp-sidecar-<version>.jar`
(thin, `Class-Path` manifest) + `build/sidecar/lib/` (62 untouched dependency jars). `java -jar`
works; the `-cp`-spawned driver child inherits the classpath via the same jar mechanism.

## Running

```
java -jar browser-mcp-sidecar-<version>.jar --port=<port> --token=<token> \
     [--browser-channel=chrome|msedge] [--headless=true|false] \
     [--allow-private=true|false] [--search-url=<template with {query}>]
```

MCP endpoint: `http://127.0.0.1:<port>/mcp/browser/<token>` (streamable). Logs go to STDERR only;
the token never appears in them. Shutdown closes page → context → browser → Playwright (ending the
driver child and Chromium).

## Verification

Unit tests (session mapping, link ids, pre/post-redirect policy, probe classification, request
filter) plus `PlaywrightLiveBrowserTest` — a real Chrome run against JavaScript-only local pages,
environment-gated on the probe (skips readably without an installed browser).
