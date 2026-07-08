package com.aresstack.askai.java8.hf;

import com.aresstack.askai.java8.net.CertificateTrustConfiguration;
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
import java.util.List;
import java.util.Map;

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
    private final String accessToken;

    public HuggingFaceClient(ProxyConfiguration proxyConfiguration, String accessToken) {
        this(proxyConfiguration, CertificateTrustConfiguration.defaults(), accessToken);
    }

    public HuggingFaceClient(ProxyConfiguration proxyConfiguration,
                             CertificateTrustConfiguration trustConfiguration, String accessToken) {
        this.proxyConfiguration = proxyConfiguration == null ? ProxyConfiguration.defaults() : proxyConfiguration;
        this.trustConfiguration = trustConfiguration == null
                ? CertificateTrustConfiguration.defaults() : trustConfiguration;
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

        List<HuggingFaceConnectionTestResult.Endpoint> endpoints =
                new ArrayList<HuggingFaceConnectionTestResult.Endpoint>();
        for (int i = 0; i < TEST_URLS.length; i++) {
            endpoints.add(probe(TEST_URLS[i]));
        }
        return new HuggingFaceConnectionTestResult(resolvedProxy, trust, endpoints);
    }

    private HuggingFaceConnectionTestResult.Endpoint probe(String url) {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            int status = connection.getResponseCode();
            List<String> headers = collectRelevantHeaders(connection);
            boolean ok = status >= 200 && status < 300;
            InputStream inputStream = ok ? connection.getInputStream() : connection.getErrorStream();
            String body = readBodyExcerpt(inputStream);
            closeQuietly(inputStream);
            String assessment = ok ? "" : classifyNon2xx(url, status, connection.getHeaderFields(), body);
            return HuggingFaceConnectionTestResult.Endpoint.http(url, status, headers, body, assessment);
        } catch (Exception ex) {
            return HuggingFaceConnectionTestResult.Endpoint.error(url, isCertificateFailure(ex), messageOf(ex));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "askai-java8");
        if (accessToken.length() > 0) {
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        return connection;
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
