package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.hf.GgufFile;
import io.github.ollama4j.json.OllamaJson;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Install a local GGUF file into a remote Ollama by uploading it as a blob and calling
 * {@code /api/create}. Reports progress for each phase (hashing, uploading, creating) and can be
 * cancelled: {@link #cancel()} disconnects the live connection so the upload/create aborts.
 */
public final class RemoteGgufInstaller {

    /** Receive a progress update; {@code total <= 0} means the step is indeterminate. */
    public interface ProgressListener {
        void onProgress(String phase, long completed, long total);
    }

    private static final int BUFFER_SIZE = 1024 * 1024;

    private final String baseUrl;
    private volatile boolean cancelled;
    private volatile HttpURLConnection activeConnection;

    public RemoteGgufInstaller(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    /** Abort a running install: mark cancelled and drop the live connection. */
    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    public void install(String modelName, File file, ProgressListener listener) throws Exception {
        install(modelName, file, java.util.Collections.<File>emptyList(), listener);
    }

    /**
     * Install the model GGUF plus optional companion files (e.g. the *mmproj* audio/vision encoder
     * a multimodal model needs). Every file is validated, hashed and uploaded (skipping blobs the
     * server already has), then referenced together in one {@code /api/create} call.
     */
    public void install(String modelName, File file, java.util.List<File> extraFiles,
                        ProgressListener listener) throws Exception {
        if (modelName == null || modelName.trim().length() == 0) {
            throw new IllegalArgumentException("Model name is required.");
        }
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("GGUF file does not exist.");
        }
        java.util.List<File> allFiles = new java.util.ArrayList<File>();
        allFiles.add(file);
        if (extraFiles != null) {
            for (int i = 0; i < extraFiles.size(); i++) {
                File extra = extraFiles.get(i);
                if (extra == null || !extra.isFile()) {
                    throw new IllegalArgumentException("Companion file does not exist: " + extra);
                }
                allFiles.add(extra);
            }
        }

        Map<String, String> files = new LinkedHashMap<String, String>();
        for (int i = 0; i < allFiles.size(); i++) {
            File current = allFiles.get(i);
            String label = allFiles.size() > 1 ? " (" + current.getName() + ")" : "";
            // Reject a truncated/corrupt GGUF up front, so the failure is clear here instead of
            // surfacing as an opaque HTTP 500 from Ollama's import.
            report(listener, "Validating GGUF" + label, 0, 0);
            GgufFile.validate(current);
            String digest = "sha256:" + sha256(current, listener);
            if (blobExists(digest)) {
                report(listener, "Blob already on server" + label, current.length(), current.length());
            } else {
                uploadBlob("/api/blobs/" + digest, current, listener);
            }
            files.put(current.getName(), digest);
        }

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", modelName.trim());
        body.put("files", files);
        body.put("stream", Boolean.TRUE);
        createModel("/api/create", OllamaJson.toJson(body), listener);
    }

    /** HEAD the blob: return true when Ollama already has it (200), false on 404. */
    private boolean blobExists(String digest) throws IOException {
        HttpURLConnection connection = open("/api/blobs/" + digest, "HEAD", null);
        try {
            int code = connection.getResponseCode();
            return code >= 200 && code < 300;
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    private void uploadBlob(String path, File file, ProgressListener listener) throws IOException {
        long total = file.length();
        HttpURLConnection connection = open(path, "POST", "application/octet-stream");
        FileInputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(total);
            outputStream = connection.getOutputStream();
            inputStream = new FileInputStream(file);
            byte[] buffer = new byte[BUFFER_SIZE];
            long uploaded = 0;
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                checkCancelled();
                outputStream.write(buffer, 0, read);
                uploaded += read;
                report(listener, "Uploading", uploaded, total);
            }
            outputStream.close();
            outputStream = null;
            requireSuccess(connection);
        } catch (IOException ex) {
            throw cancelAware(ex);
        } finally {
            activeConnection = null;
            closeQuietly(inputStream);
            closeQuietly(outputStream);
            connection.disconnect();
        }
    }

    /** POST /api/create with {@code stream:true} and report each status line. */
    private void createModel(String path, String json, ProgressListener listener) throws IOException {
        HttpURLConnection connection = open(path, "POST", "application/json; charset=UTF-8");
        OutputStream outputStream = null;
        BufferedReader reader = null;
        try {
            connection.setDoOutput(true);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            outputStream = connection.getOutputStream();
            outputStream.write(bytes);
            outputStream.close();
            outputStream = null;

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Remote Ollama returned HTTP " + code + ": "
                        + readBody(connection.getErrorStream()));
            }
            report(listener, "Creating model", 0, 0);
            reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                checkCancelled();
                String status = extractStatus(line);
                if (status.length() > 0) {
                    report(listener, status, 0, 0);
                }
            }
        } catch (IOException ex) {
            throw cancelAware(ex);
        } finally {
            activeConnection = null;
            closeQuietly(reader);
            closeQuietly(outputStream);
            connection.disconnect();
        }
    }

    private String sha256(File file, ProgressListener listener) throws Exception {
        long total = file.length();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream inputStream = new FileInputStream(file);
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            long hashed = 0;
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                checkCancelled();
                digest.update(buffer, 0, read);
                hashed += read;
                report(listener, "Hashing", hashed, total);
            }
        } finally {
            closeQuietly(inputStream);
        }
        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(bytes[i] & 0xff);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    private HttpURLConnection open(String path, String method, String contentType) throws IOException {
        checkCancelled();
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(3600000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        connection.setRequestProperty("User-Agent", "askai-java8");
        activeConnection = connection;
        return connection;
    }

    private void requireSuccess(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        if (code >= 200 && code < 300) {
            return;
        }
        throw new IOException("Remote Ollama returned HTTP " + code + ": " + readBody(connection.getErrorStream()));
    }

    /** Pull the {@code status} field out of one create/pull NDJSON line, or "" when absent. */
    private static String extractStatus(String line) {
        if (line == null || line.trim().length() == 0) {
            return "";
        }
        try {
            Object parsed = OllamaJson.parse(line);
            if (parsed instanceof Map) {
                Object status = ((Map) parsed).get("status");
                if (status != null) {
                    return String.valueOf(status);
                }
                Object error = ((Map) parsed).get("error");
                if (error != null) {
                    return "error: " + error;
                }
            }
        } catch (RuntimeException ignored) {
            // Not JSON: ignore this line.
        }
        return "";
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled) {
            throw new InterruptedIOException("Installation cancelled.");
        }
    }

    /** Turn an IOException caused by a cancel-disconnect into a clear cancellation error. */
    private IOException cancelAware(IOException ex) {
        if (cancelled) {
            return new InterruptedIOException("Installation cancelled.");
        }
        return ex;
    }

    private void report(ProgressListener listener, String phase, long completed, long total) {
        if (listener != null) {
            listener.onProgress(phase, completed, total);
        }
    }

    private String readBody(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int read;
        try {
            while ((read = inputStream.read(bytes)) >= 0) {
                buffer.write(bytes, 0, read);
            }
        } finally {
            inputStream.close();
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null || value.trim().length() == 0
                ? "http://127.0.0.1:11434"
                : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
