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

    private static final String TEST_URL = "https://huggingface.co/api/models?limit=1";

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
     * Performs a real HTTPS request against HuggingFace using the exact same code path (proxy
     * resolution, TLS handshake, certificate validation, HTTP request) as search and download, so a
     * PKIX/certificate problem can be told apart from a proxy problem. Never throws; failures are
     * reported through the returned result.
     */
    public HuggingFaceConnectionTestResult testConnection() {
        SystemTrustSslSocketFactory.Result trust = SystemTrustSslSocketFactory.build(trustConfiguration);

        String resolvedProxy;
        try {
            resolvedProxy = describeProxy(proxyConfiguration.resolveJavaProxy(TEST_URL));
        } catch (IOException ex) {
            return HuggingFaceConnectionTestResult.failure(TEST_URL, "unresolved", false,
                    "Proxy resolution failed: " + messageOf(ex), trust);
        }

        HttpURLConnection connection = null;
        try {
            connection = open(TEST_URL);
            int status = connection.getResponseCode();
            InputStream inputStream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            readText(inputStream);
            closeQuietly(inputStream);
            return HuggingFaceConnectionTestResult.success(TEST_URL, resolvedProxy, status, trust);
        } catch (Exception ex) {
            boolean certificateFailure = isCertificateFailure(ex);
            return HuggingFaceConnectionTestResult.failure(TEST_URL, resolvedProxy, certificateFailure,
                    messageOf(ex), trust);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
