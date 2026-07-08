package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.hf.GgufFile;
import io.github.ollama4j.json.OllamaJson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RemoteGgufInstaller {

    private final String baseUrl;

    public RemoteGgufInstaller(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    public void install(String modelName, File file) throws Exception {
        if (modelName == null || modelName.trim().length() == 0) {
            throw new IllegalArgumentException("Model name is required.");
        }
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("GGUF file does not exist.");
        }
        // Reject a truncated/corrupt GGUF up front, so the failure is clear here instead of surfacing
        // as an opaque HTTP 500 from Ollama's import ("tensor offset+size exceeds file size").
        GgufFile.validate(file);
        String checksum = sha256(file);
        postFile("/api/" + "blobs/sha256:" + checksum, file);
        Map files = new LinkedHashMap();
        files.put(file.getName(), "sha256:" + checksum);
        Map body = new LinkedHashMap();
        body.put("model", modelName.trim());
        body.put("files", files);
        body.put("modelfile", "FROM " + file.getName());
        body.put("stream", Boolean.FALSE);
        postJson("/api/create", OllamaJson.toJson(body));
    }

    private void postFile(String path, File file) throws IOException {
        HttpURLConnection connection = open(path, "POST", "application/octet-stream");
        FileInputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Length", String.valueOf(file.length()));
            inputStream = new FileInputStream(file);
            outputStream = connection.getOutputStream();
            copy(inputStream, outputStream);
            outputStream.close();
            outputStream = null;
            requireSuccess(connection);
        } finally {
            closeQuietly(inputStream);
            closeQuietly(outputStream);
            connection.disconnect();
        }
    }

    private void postJson(String path, String json) throws IOException {
        HttpURLConnection connection = open(path, "POST", "application/json; charset=UTF-8");
        OutputStream outputStream = null;
        try {
            connection.setDoOutput(true);
            byte[] bytes = json.getBytes("UTF-8");
            connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            outputStream = connection.getOutputStream();
            outputStream.write(bytes);
            outputStream.close();
            outputStream = null;
            requireSuccess(connection);
        } finally {
            closeQuietly(outputStream);
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method, String contentType) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(3600000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", contentType);
        connection.setRequestProperty("User-Agent", "askai-java8");
        return connection;
    }

    private void requireSuccess(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        if (code >= 200 && code < 300) {
            return;
        }
        throw new IOException("Remote Ollama returned HTTP " + code + ": " + readBody(connection.getErrorStream()));
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
        return new String(buffer.toByteArray(), "UTF-8");
    }

    private void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[1024 * 1024];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream inputStream = new FileInputStream(file);
        try {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } finally {
            inputStream.close();
        }
        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(bytes[i] & 0xff);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
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

    private void closeQuietly(OutputStream outputStream) {
        if (outputStream == null) {
            return;
        }
        try {
            outputStream.close();
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
