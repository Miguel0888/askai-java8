package com.aresstack.askai.java8.hf;

import com.aresstack.askai.java8.net.CertificateTrustConfiguration;
import com.aresstack.askai.java8.net.HttpClientConfiguration;
import com.aresstack.askai.java8.net.ProxyConfiguration;
import com.aresstack.askai.java8.net.SystemTrustSslSocketFactory;
import io.github.ollama4j.json.OllamaJson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

public final class HuggingFaceClient {

    // Probed in order; the last one (the JSON API) decides overall success. The bare site is probed
    // first so an HTML/policy page there can be compared against the API's JSON response.
    private static final String[] TEST_URLS = {
            "https://huggingface.co/",
            "https://huggingface.co/api/models?limit=1"
    };

    private static final int BODY_EXCERPT_LIMIT = 1200;

    private final ProxyConfiguration proxyConfiguration;
    private final CertificateTrustConfiguration trustConfiguration;
    private final HttpClientConfiguration httpClientConfiguration;
    private final String accessToken;

    public HuggingFaceClient(ProxyConfiguration proxyConfiguration, String accessToken) {
        this(proxyConfiguration, CertificateTrustConfiguration.defaults(),
                HttpClientConfiguration.defaults(), accessToken);
    }

    public HuggingFaceClient(ProxyConfiguration proxyConfiguration,
                             CertificateTrustConfiguration trustConfiguration, String accessToken) {
        this(proxyConfiguration, trustConfiguration, HttpClientConfiguration.defaults(), accessToken);
    }

    public HuggingFaceClient(ProxyConfiguration proxyConfiguration,
                             CertificateTrustConfiguration trustConfiguration,
                             HttpClientConfiguration httpClientConfiguration, String accessToken) {
        this.proxyConfiguration = proxyConfiguration == null ? ProxyConfiguration.defaults() : proxyConfiguration;
        this.trustConfiguration = trustConfiguration == null
                ? CertificateTrustConfiguration.defaults() : trustConfiguration;
        this.httpClientConfiguration = httpClientConfiguration == null
                ? HttpClientConfiguration.defaults() : httpClientConfiguration;
        this.accessToken = accessToken == null ? "" : accessToken.trim();
    }

    /**
     * Performs real HTTPS requests against HuggingFace using the exact same code path (proxy
     * resolution, TLS handshake, certificate validation, HTTP request) as search and download, so a
     * PKIX/certificate problem can be told apart from a proxy problem. For non-2xx responses the
     * relevant response headers and a body excerpt are captured and a heuristic decides whether the
     * block looks like a corporate proxy policy or a HuggingFace/Cloudflare origin response. Never
     * throws and never disables certificate validation; failures are reported through the result.
     */
    public HuggingFaceConnectionTestResult testConnection() {
        SystemTrustSslSocketFactory.Result trust = SystemTrustSslSocketFactory.build(trustConfiguration);

        String resolvedProxy;
        try {
            resolvedProxy = describeProxy(proxyConfiguration.resolveJavaProxy(TEST_URLS[TEST_URLS.length - 1]));
        } catch (IOException ex) {
            resolvedProxy = "unresolved (" + messageOf(ex) + ")";
        }

        // Probe every URL with an honest and a browser-like header profile. The honest profile is what
        // real operations send; the browser-like profile is diagnostic only. If browser-like is allowed
        // while the honest one is blocked, the proxy is blocking the application's client identity.
        List<HeaderProfile> profiles = testProfiles();

        List<HuggingFaceConnectionTestResult.Endpoint> endpoints =
                new ArrayList<HuggingFaceConnectionTestResult.Endpoint>();
        for (int u = 0; u < TEST_URLS.length; u++) {
            for (int p = 0; p < profiles.size(); p++) {
                endpoints.add(probe(TEST_URLS[u], profiles.get(p)));
            }
        }
        return new HuggingFaceConnectionTestResult(resolvedProxy, trust, buildNotes(), endpoints);
    }

    /** A named set of request headers to probe with. */
    private static final class HeaderProfile {
        final String label;
        final Map<String, String> headers;

        HeaderProfile(String label, Map<String, String> headers) {
            this.label = label;
            this.headers = headers;
        }
    }

    private List<HeaderProfile> testProfiles() {
        List<HeaderProfile> profiles = new ArrayList<HeaderProfile>();

        Map<String, String> honest = new LinkedHashMap<String, String>();
        honest.put("Accept", "application/json");
        honest.put("User-Agent", HttpClientConfiguration.DEFAULT_USER_AGENT);
        profiles.add(new HeaderProfile("askai-java8 (honest default)", honest));

        // Browser-like headers for diagnosis only. Deliberately no Accept-Encoding, so the body stays
        // readable for block-page detection.
        Map<String, String> browser = new LinkedHashMap<String, String>();
        browser.put("User-Agent", HttpClientConfiguration.BROWSER_USER_AGENT);
        browser.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        browser.put("Accept-Language", "de-DE,de;q=0.8,en-US;q=0.5,en;q=0.3");
        browser.put("Upgrade-Insecure-Requests", "1");
        browser.put("Sec-Fetch-Dest", "document");
        browser.put("Sec-Fetch-Mode", "navigate");
        browser.put("Sec-Fetch-Site", "none");
        browser.put("Sec-Fetch-User", "?1");
        profiles.add(new HeaderProfile("browser-like (diagnostic)", browser));

        return profiles;
    }

    private List<String> buildNotes() {
        List<String> notes = new ArrayList<String>();
        notes.add("Configured User-Agent (search/download): " + httpClientConfiguration.getUserAgent());
        notes.add("Header profiles probed here: askai-java8 (honest default), browser-like (diagnostic)");
        notes.add("Browser-like headers are for DIAGNOSIS only; real operations keep the honest identity.");
        HttpClientConfiguration.ProxyAuthMode mode = httpClientConfiguration.getProxyAuthMode();
        if (mode == HttpClientConfiguration.ProxyAuthMode.BASIC) {
            String user = httpClientConfiguration.getProxyAuthUsername();
            notes.add("Proxy auth: BASIC" + (user.length() > 0
                    ? " (user \"" + user + "\", Proxy-Authorization sent)"
                    : " (no username set — nothing sent)"));
        } else if (mode == HttpClientConfiguration.ProxyAuthMode.WINDOWS_INTEGRATED) {
            notes.add("Proxy auth: WINDOWS_INTEGRATED (prepared/diagnostic only — NTLM/Kerberos negotiation "
                    + "is not wired, so no credentials are sent. If the browser works via SSO, the proxy likely "
                    + "expects integrated auth that this client cannot perform yet.)");
        } else {
            notes.add("Proxy auth: NONE");
        }
        return notes;
    }

    private HuggingFaceConnectionTestResult.Endpoint probe(String url, HeaderProfile profile) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, profile.headers);
            int status = connection.getResponseCode();
            boolean ok = status >= 200 && status < 300;
            Map<String, List<String>> headerFields = connection.getHeaderFields();
            List<String> headers = collectRelevantHeaders(connection);
            InputStream inputStream = ok ? connection.getInputStream() : connection.getErrorStream();
            String body = readBodyExcerpt(inputStream);
            closeQuietly(inputStream);

            String contentType = safe(headerValue(headerFields, "Content-Type")).toLowerCase();
            boolean json = contentType.contains("application/json") || looksLikeJson(body);
            boolean blockPage = isBlockPage(body);
            String policyId = blockPage ? extractPolicyId(body) : null;
            String contentKind = describeContentKind(json, blockPage, contentType, body);

            String assessment = ok ? "" : classifyNon2xx(url, status, headerFields, body);
            List<String> detail = buildDetailLines(headerFields, headers, body);
            return HuggingFaceConnectionTestResult.Endpoint.http(url, profile.label, status, contentKind,
                    policyId, json, blockPage, assessment, detail);
        } catch (Exception ex) {
            return HuggingFaceConnectionTestResult.Endpoint.error(url, profile.label, isCertificateFailure(ex),
                    messageOf(ex));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String describeContentKind(boolean json, boolean blockPage, String contentType, String body) {
        if (json) {
            return "JSON (HuggingFace API data)";
        }
        if (blockPage) {
            return "HTML block page (proxy/web-filter policy)";
        }
        if (contentType.contains("text/html") || safe(body).toLowerCase().contains("<html")) {
            return "HTML page";
        }
        return safe(body).length() == 0 ? "empty" : "other";
    }

    private static final String[] BLOCK_PAGE_MARKERS = {
            "access denied", "blockpage", "block page", "zugriff wurde verweigert", "zugriff verweigert",
            "richtlinien-id", "richtlinien id", "policy id", "policy-id", "this request was blocked",
            "web filter", "url filtering"
    };

    /** Detects a corporate proxy/web-filter block page (e.g. ZFD "Access Denied"/"Blockpage"). */
    private static boolean isBlockPage(String body) {
        String haystack = safe(body).toLowerCase();
        for (int i = 0; i < BLOCK_PAGE_MARKERS.length; i++) {
            if (haystack.contains(BLOCK_PAGE_MARKERS[i])) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern POLICY_ID_PATTERN = Pattern.compile(
            "(?i)(?:richtlinien[- ]?id|policy[- ]?id|reference[- ]?id|ref[- ]?id)[^0-9a-f]{0,20}"
                    + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    /** Extracts a policy/reference id (UUID) from a block page, preferring one next to a policy label. */
    private static String extractPolicyId(String body) {
        String text = safe(body);
        Matcher labelled = POLICY_ID_PATTERN.matcher(text);
        if (labelled.find()) {
            return labelled.group(1);
        }
        Matcher any = UUID_PATTERN.matcher(text);
        if (any.find()) {
            return any.group();
        }
        return null;
    }

    /**
     * Builds the per-probe detail block: the relevant response headers, and for HTML responses the
     * extracted title, highlighted policy/auth terms and visible text (scripts/styles/tags stripped).
     * For non-HTML responses the raw body excerpt is shown instead.
     */
    private List<String> buildDetailLines(Map<String, List<String>> headerFields, List<String> headers, String body) {
        List<String> lines = new ArrayList<String>();
        if (!headers.isEmpty()) {
            lines.add("Response headers:");
            for (int i = 0; i < headers.size(); i++) {
                lines.add("  " + headers.get(i));
            }
        }
        String contentType = safe(headerValue(headerFields, "Content-Type")).toLowerCase();
        boolean html = contentType.contains("text/html") || safe(body).toLowerCase().contains("<html")
                || safe(body).toLowerCase().contains("<!doctype html");
        if (html) {
            String title = extractTitle(body);
            if (title.length() > 0) {
                lines.add("Page title: \"" + title + "\"");
            }
            String highlighted = joinValues(highlightTerms(body));
            if (highlighted.length() > 0) {
                lines.add("Highlighted terms: " + highlighted);
            }
            String visible = extractVisibleText(body);
            if (visible.length() > 0) {
                lines.add("Visible page text (excerpt):");
                lines.add("  " + visible);
            }
        } else if (safe(body).length() > 0) {
            lines.add("Body (excerpt):");
            lines.add("  " + body);
        }
        return lines;
    }

    private static final String[] RELEVANT_HEADERS = {
            "Server", "Via", "Content-Type", "Content-Length", "Connection",
            "Proxy-Connection", "Proxy-Authenticate", "WWW-Authenticate", "Cache-Control",
            "Location", "Date", "X-Cache", "CF-RAY"
    };

    /**
     * Returns a curated list of "Name: value" header lines: a fixed allowlist plus any header whose
     * name starts with {@code X-} or {@code CF-} (proxy and CDN vendors expose themselves there).
     */
    private List<String> collectRelevantHeaders(HttpURLConnection connection) {
        List<String> lines = new ArrayList<String>();
        Map<String, List<String>> fields = connection.getHeaderFields();
        if (fields == null) {
            return lines;
        }
        for (Map.Entry entry : fields.entrySet()) {
            Object nameObject = entry.getKey();
            if (nameObject == null) {
                continue;
            }
            String name = String.valueOf(nameObject);
            if (!isRelevantHeader(name)) {
                continue;
            }
            Object valueObject = entry.getValue();
            String value = valueObject instanceof List ? joinValues((List) valueObject) : String.valueOf(valueObject);
            lines.add(name + ": " + value);
        }
        return lines;
    }

    private boolean isRelevantHeader(String name) {
        String lower = name.toLowerCase();
        if (lower.startsWith("x-") || lower.startsWith("cf-")) {
            return true;
        }
        for (int i = 0; i < RELEVANT_HEADERS.length; i++) {
            if (RELEVANT_HEADERS[i].toLowerCase().equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static String joinValues(List values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.valueOf(values.get(i)));
        }
        return builder.toString();
    }

    /**
     * Reads at most {@link #BODY_EXCERPT_LIMIT} characters from the stream and normalises whitespace so
     * the excerpt stays log-friendly. Does not consume the whole (possibly large) body.
     */
    private String readBodyExcerpt(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        byte[] buffer = new byte[4096];
        StringBuilder builder = new StringBuilder();
        int read;
        while (builder.length() < BODY_EXCERPT_LIMIT && (read = inputStream.read(buffer)) >= 0) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        String text = builder.toString().replaceAll("\\s+", " ").trim();
        if (text.length() > BODY_EXCERPT_LIMIT) {
            text = text.substring(0, BODY_EXCERPT_LIMIT) + " …";
        }
        return text;
    }

    private static final String[] HIGHLIGHT_TERMS = {
            "access denied", "access to this", "not authorized", "unauthorized", "authentication",
            "authorization", "sign in", "log in", "login", "captive portal", "policy", "category",
            "web filter", "url filtering", "blocked", "forbidden", "denied", "proxy", "fortinet",
            "fortiguard", "zscaler", "blue coat", "bluecoat", "forcepoint", "websense", "mcafee",
            "netskope", "squid", "gesperrt", "blockiert", "richtlinie", "anmeldung", "nicht erlaubt"
    };

    /** Extracts the {@code <title>} text from an HTML body excerpt, or "" when absent. */
    private static String extractTitle(String body) {
        if (body == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<title[^>]*>(.*?)</title>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                .matcher(body);
        if (matcher.find()) {
            return decodeEntities(matcher.group(1)).replaceAll("\\s+", " ").trim();
        }
        return "";
    }

    /**
     * Strips {@code <script>}/{@code <style>} blocks and all tags from an HTML body excerpt and returns
     * the collapsed visible text, capped to keep the log readable.
     */
    private static String extractVisibleText(String body) {
        if (body == null) {
            return "";
        }
        String text = body
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?is)<[^>]+>", " ");
        text = decodeEntities(text).replaceAll("\\s+", " ").trim();
        int cap = 600;
        if (text.length() > cap) {
            text = text.substring(0, cap) + " …";
        }
        return text;
    }

    /** Returns the distinct policy/auth terms present in the body, in a stable order, for highlighting. */
    private static List<String> highlightTerms(String body) {
        List<String> matches = new ArrayList<String>();
        String haystack = safe(body).toLowerCase();
        for (int i = 0; i < HIGHLIGHT_TERMS.length; i++) {
            String term = HIGHLIGHT_TERMS[i];
            if (haystack.contains(term) && !matches.contains(term)) {
                matches.add(term);
            }
        }
        return matches;
    }

    private static String decodeEntities(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    private static final String[] PROXY_VENDOR_SIGNATURES = {
            "zscaler", "bluecoat", "blue coat", "blue-coat", "forcepoint", "websense", "fortinet",
            "fortigate", "fortiguard", "mcafee", "web gateway", "webwasher", "squid", "ironport",
            "sophos", "palo alto", "check point", "checkpoint", "netskope", "trend micro", "sonicwall",
            "barracuda", "symantec web", "cisco web", "iboss"
    };

    private static final String[] PROXY_POLICY_PHRASES = {
            "access denied", "access to this", "this site is blocked", "site blocked", "blocked category",
            "blocked by", "content blocked", "web filter", "url filtering", "filtering policy",
            "your organization", "company policy", "corporate policy", "not permitted", "denied by policy",
            "proxy policy", "gesperrt", "blockiert", "richtlinie", "nicht erlaubt"
    };

    private static final String[] CLOUDFLARE_SIGNATURES = {
            "cloudflare", "cf-ray", "attention required", "error 1020", "ray id"
    };

    /**
     * Heuristically classifies a non-2xx response as a corporate proxy block vs. a HuggingFace/
     * Cloudflare origin response, based on status code, selected headers and the body excerpt. This is
     * a hint only; the raw headers and body are always shown so the user can judge for themselves.
     */
    private String classifyNon2xx(String url, int status, Map<String, List<String>> headerFields, String body) {
        String proxyAuthenticate = headerValue(headerFields, "Proxy-Authenticate");
        if (status == 407 || proxyAuthenticate != null) {
            return "CORPORATE PROXY requires authentication (HTTP 407 / Proxy-Authenticate present).";
        }

        String server = safe(headerValue(headerFields, "Server"));
        String via = safe(headerValue(headerFields, "Via"));
        String contentType = safe(headerValue(headerFields, "Content-Type")).toLowerCase();
        String cfRay = headerValue(headerFields, "CF-RAY");
        String haystack = (server + " " + via + " " + safe(body)).toLowerCase();

        String vendor = firstMatch(haystack, PROXY_VENDOR_SIGNATURES);
        if (vendor != null) {
            return "Looks like a CORPORATE PROXY block (matched proxy vendor \"" + vendor + "\" in headers/body).";
        }

        boolean cloudflare = cfRay != null || firstMatch(haystack, CLOUDFLARE_SIGNATURES) != null;
        if (cloudflare) {
            return "Looks like a HuggingFace/Cloudflare (origin/CDN) response (Cloudflare signature present), "
                    + "not the corporate proxy.";
        }

        String phrase = firstMatch(safe(body).toLowerCase(), PROXY_POLICY_PHRASES);
        if (phrase != null) {
            return "Looks like a CORPORATE PROXY policy page (matched \"" + phrase + "\" in the body).";
        }

        boolean json = contentType.contains("application/json") || looksLikeJson(body);
        if (json) {
            return "Looks like a HuggingFace origin response (JSON body) — likely auth/authorization "
                    + "(token or gated repo), not the proxy.";
        }

        boolean html = contentType.contains("text/html") || safe(body).toLowerCase().contains("<html");
        if (html && url.contains("/api/")) {
            return "Likely a CORPORATE PROXY page: HTML returned where the API normally returns JSON.";
        }

        return "Origin unclear — inspect the headers and body below.";
    }

    private static String headerValue(Map<String, List<String>> headerFields, String name) {
        if (headerFields == null) {
            return null;
        }
        for (Map.Entry entry : headerFields.entrySet()) {
            Object key = entry.getKey();
            if (key != null && name.equalsIgnoreCase(String.valueOf(key))) {
                Object value = entry.getValue();
                if (value instanceof List && !((List) value).isEmpty()) {
                    return String.valueOf(((List) value).get(0));
                }
                return value == null ? null : String.valueOf(value);
            }
        }
        return null;
    }

    private static String firstMatch(String haystack, String[] needles) {
        for (int i = 0; i < needles.length; i++) {
            if (haystack.contains(needles[i])) {
                return needles[i];
            }
        }
        return null;
    }

    private static boolean looksLikeJson(String body) {
        String trimmed = safe(body).trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public List<HuggingFaceModel> searchModels(String query, int limit) throws IOException {
        String url = "https://huggingface.co/api/models?search=" + encode(query) + "&filter=gguf&limit=" + limit;
        Object parsed = OllamaJson.parse(getText(url));
        List values = parsed instanceof List ? (List) parsed : new ArrayList();
        List<HuggingFaceModel> models = new ArrayList<HuggingFaceModel>();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof Map) {
                Map map = (Map) value;
                models.add(new HuggingFaceModel(
                        firstNonEmpty(string(map, "id"), string(map, "modelId")),
                        string(map, "pipeline_tag"),
                        number(map, "downloads"),
                        number(map, "likes")));
            }
        }
        return models;
    }

    public List<HuggingFaceFile> listFiles(String modelId) throws IOException {
        String url = "https://huggingface.co/api/models/" + encodePath(modelId) + "/tree/main?recursive=true";
        Object parsed = OllamaJson.parse(getText(url));
        List values = parsed instanceof List ? (List) parsed : new ArrayList();
        List<HuggingFaceFile> files = new ArrayList<HuggingFaceFile>();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof Map) {
                Map map = (Map) value;
                String type = string(map, "type");
                String path = string(map, "path");
                if ((type.length() == 0 || "file".equals(type)) && path.toLowerCase().endsWith(".gguf")) {
                    files.add(new HuggingFaceFile(modelId, path, number(map, "size")));
                }
            }
        }
        return files;
    }

    public File download(HuggingFaceFile file, File targetDirectory, DownloadProgressListener listener) throws IOException {
        if (!targetDirectory.isDirectory() && !targetDirectory.mkdirs()) {
            throw new IOException("Could not create download directory: " + targetDirectory.getAbsolutePath());
        }
        File modelDirectory = new File(targetDirectory, sanitize(file.getModelId()));
        if (!modelDirectory.isDirectory() && !modelDirectory.mkdirs()) {
            throw new IOException("Could not create model directory: " + modelDirectory.getAbsolutePath());
        }
        File targetFile = new File(modelDirectory, file.getFileName());
        String url = "https://huggingface.co/" + encodePath(file.getModelId()) + "/resolve/main/" + encodePath(file.getPath());
        HttpURLConnection connection = open(url);
        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HuggingFace download failed with HTTP " + status);
            }
            long total = connection.getContentLengthLong();
            inputStream = connection.getInputStream();
            outputStream = new FileOutputStream(targetFile);
            byte[] buffer = new byte[1024 * 1024];
            long completed = 0L;
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
                completed += read;
                if (listener != null) {
                    listener.onProgress(completed, total);
                }
            }
            return targetFile;
        } finally {
            closeQuietly(inputStream);
            closeQuietly(outputStream);
            connection.disconnect();
        }
    }

    private String getText(String url) throws IOException {
        HttpURLConnection connection = open(url);
        InputStream inputStream = null;
        try {
            int status = connection.getResponseCode();
            inputStream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = readText(inputStream);
            if (status < 200 || status >= 300) {
                throw new IOException("HuggingFace request failed with HTTP " + status + ": " + body);
            }
            return body;
        } finally {
            closeQuietly(inputStream);
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        // Real operations keep the honest, configured identity (default: askai-java8).
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", httpClientConfiguration.getUserAgent());
        return open(url, headers);
    }

    /**
     * Opens a connection using the same proxy resolution and TLS trust as all HuggingFace operations,
     * but with an explicit set of request headers. The connection test uses this to probe an honest and
     * a browser-like header profile, so a proxy that blocks the application's client identity can be
     * distinguished from one that blocks the URL itself.
     */
    private HttpURLConnection open(String url, Map<String, String> headers) throws IOException {
        Proxy proxy = proxyConfiguration.resolveJavaProxy(url);
        HttpURLConnection connection = (HttpURLConnection) (proxy == Proxy.NO_PROXY
                ? new URL(url).openConnection()
                : new URL(url).openConnection(proxy));
        if (connection instanceof HttpsURLConnection) {
            // Corporate proxies terminate TLS with a private CA that lives in the Windows
            // certificate store but not in the JVM cacerts. The configured trust sources decide
            // which anchors to add so the handshake with huggingface.co does not fail with
            // "PKIX path building failed".
            ((HttpsURLConnection) connection).setSSLSocketFactory(
                    SystemTrustSslSocketFactory.build(trustConfiguration).getSocketFactory());
        }
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(3600000);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        applyProxyAuthorization(connection);
        if (accessToken.length() > 0) {
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        return connection;
    }

    /**
     * Applies proxy credentials when BASIC auth is configured. WINDOWS_INTEGRATED is intentionally not
     * wired here (it would require NTLM/Kerberos negotiation); it is only surfaced diagnostically.
     */
    private void applyProxyAuthorization(HttpURLConnection connection) {
        if (!httpClientConfiguration.hasBasicCredentials()) {
            return;
        }
        String raw = httpClientConfiguration.getProxyAuthUsername() + ":"
                + httpClientConfiguration.getProxyAuthPassword();
        String encoded = java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        connection.setRequestProperty("Proxy-Authorization", "Basic " + encoded);
    }

    private String readText(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        byte[] buffer = new byte[8192];
        StringBuilder builder = new StringBuilder();
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static String describeProxy(Proxy proxy) {
        if (proxy == null || proxy == Proxy.NO_PROXY || proxy.type() == Proxy.Type.DIRECT) {
            return "DIRECT (no proxy)";
        }
        if (proxy.address() instanceof InetSocketAddress) {
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            return proxy.type() + " " + address.getHostString() + ":" + address.getPort();
        }
        return String.valueOf(proxy);
    }

    /**
     * @return {@code true} when the throwable (or any of its causes) is a certificate/PKIX path
     *         building failure, i.e. a trust problem rather than a proxy or connectivity problem.
     */
    private static boolean isCertificateFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.security.cert.CertificateException
                    || current instanceof javax.net.ssl.SSLHandshakeException) {
                return true;
            }
            String className = current.getClass().getName();
            if (className.contains("CertPathBuilderException")
                    || className.contains("CertPathValidatorException")
                    || className.contains("ValidatorException")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("pkix path building failed")
                        || lower.contains("unable to find valid certification path")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String messageOf(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return message != null && message.trim().length() > 0
                ? message : throwable.getClass().getName();
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            return "";
        }
    }

    private String encodePath(String path) {
        String[] parts = (path == null ? "" : path).split("/");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(encode(parts[i]).replace("+", "%20"));
        }
        return builder.toString();
    }

    private String sanitize(String value) {
        return (value == null ? "model" : value).replace('/', '_').replace('\\', '_');
    }

    private static String string(Map map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && first.length() > 0 ? first : second;
    }

    private static long number(Map map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(FileOutputStream outputStream) {
        if (outputStream == null) {
            return;
        }
        try {
            outputStream.close();
        } catch (IOException ignored) {
        }
    }
}
