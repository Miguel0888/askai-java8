package com.aresstack.askai.java8.stt;

import io.github.ollama4j.json.OllamaJson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Adapter for Ollama's OpenAI-compatible {@code POST /v1/audio/transcriptions} endpoint. The UI
 * never sees HTTP, multipart encoding, or JSON — it talks to {@link SpeechToTextService} and this
 * class stays an implementation detail behind {@link DefaultSpeechToTextService}.
 *
 * <p>One instance handles one request; {@link #abort()} disconnects the live connection so an
 * in-flight upload or read fails fast with an {@link IOException} (used for user cancellation).</p>
 *
 * <p>Note: the endpoint is available in newer Ollama versions only, and the model must accept
 * audio input. Both failure modes are mapped to clear messages.</p>
 */
final class OllamaSpeechToTextClient {

    private static final String BOUNDARY_PREFIX = "askai-java8-stt-";
    private static final String CRLF = "\r\n";

    private final String baseUrl;
    private final int timeoutSeconds;
    private volatile HttpURLConnection activeConnection;
    private volatile boolean aborted;

    OllamaSpeechToTextClient(String baseUrl, int timeoutSeconds) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : SpeechToTextConfiguration.DEFAULT_TIMEOUT_SECONDS;
    }

    /** Aborts the request in flight: in-progress IO fails with an IOException. Safe to call anytime. */
    void abort() {
        aborted = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    /**
     * Uploads the audio file and returns the transcription text.
     *
     * @throws SpeechToTextException with a user-readable message for every known failure mode.
     */
    String transcribe(SpeechToTextService.TranscriptionRequest request) throws SpeechToTextException {
        File audioFile = request.getAudioFile();
        String boundary = BOUNDARY_PREFIX + System.nanoTime();
        byte[] preamble = buildPreamble(boundary, request, audioFile.getName());
        byte[] epilogue = (CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8);
        long contentLength = preamble.length + audioFile.length() + epilogue.length;

        HttpURLConnection connection = null;
        try {
            connection = open(contentLength, boundary);
            activeConnection = connection;
            writeBody(connection, preamble, audioFile, epilogue);
            return readTranscription(connection);
        } catch (SpeechToTextException ex) {
            throw ex;
        } catch (SocketTimeoutException ex) {
            throw failure("The transcription timed out after " + timeoutSeconds + " seconds. "
                    + "Increase the Speech-to-Text timeout or use a shorter audio file.", ex);
        } catch (ConnectException ex) {
            throw failure("Ollama at " + baseUrl + " is not reachable: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            if (aborted) {
                throw failure("Transcription cancelled.", ex);
            }
            throw failure("Transcription failed: " + messageOf(ex), ex);
        } finally {
            activeConnection = null;
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection open(long contentLength, String boundary) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(baseUrl + "/v1/audio/transcriptions").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(timeoutSeconds * 1000);
        // Stream the file instead of buffering it in memory; audio files can be large.
        connection.setFixedLengthStreamingMode(contentLength);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "askai-java8");
        return connection;
    }

    /** The multipart fields (model, optional language/prompt) plus the header of the file part. */
    private byte[] buildPreamble(String boundary, SpeechToTextService.TranscriptionRequest request,
                                 String fileName) {
        StringBuilder builder = new StringBuilder();
        if (request.getModelName().length() > 0) {
            appendField(builder, boundary, "model", request.getModelName());
        }
        String language = request.getLanguage();
        if (language.length() > 0 && !"auto".equalsIgnoreCase(language)) {
            appendField(builder, boundary, "language", language);
        }
        if (request.getPrompt().length() > 0) {
            appendField(builder, boundary, "prompt", request.getPrompt());
        }
        appendField(builder, boundary, "response_format", "json");
        builder.append("--").append(boundary).append(CRLF);
        builder.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(sanitizeFileName(fileName)).append('"').append(CRLF);
        builder.append("Content-Type: application/octet-stream").append(CRLF);
        builder.append(CRLF);
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendField(StringBuilder builder, String boundary, String name, String value) {
        builder.append("--").append(boundary).append(CRLF);
        builder.append("Content-Disposition: form-data; name=\"").append(name).append('"').append(CRLF);
        builder.append(CRLF);
        builder.append(value).append(CRLF);
    }

    private static String sanitizeFileName(String fileName) {
        return fileName.replace('"', '_').replace('\\', '_').replace("\r", "").replace("\n", "");
    }

    private void writeBody(HttpURLConnection connection, byte[] preamble, File audioFile, byte[] epilogue)
            throws IOException {
        OutputStream outputStream = null;
        InputStream fileStream = null;
        try {
            outputStream = connection.getOutputStream();
            outputStream.write(preamble);
            fileStream = new FileInputStream(audioFile);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = fileStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.write(epilogue);
            outputStream.flush();
        } finally {
            closeQuietly(fileStream);
            closeQuietly(outputStream);
        }
    }

    private String readTranscription(HttpURLConnection connection) throws IOException, SpeechToTextException {
        int status = connection.getResponseCode();
        String body = readText(status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream());

        if (status == 404) {
            throw failure("This Ollama version does not offer /v1/audio/transcriptions. "
                    + "Update Ollama to a version with audio transcription support.", null);
        }
        if (status < 200 || status >= 300) {
            throw failure(describeHttpError(status, body), null);
        }

        Object parsed;
        try {
            parsed = OllamaJson.parse(body);
        } catch (RuntimeException ex) {
            throw failure("Ollama returned an invalid transcription response (not JSON): "
                    + excerpt(body), ex);
        }
        if (!(parsed instanceof Map)) {
            throw failure("Ollama returned an unexpected transcription response: " + excerpt(body), null);
        }
        Object text = ((Map) parsed).get("text");
        if (text == null) {
            throw failure("The transcription response contained no text field: " + excerpt(body), null);
        }
        String transcription = String.valueOf(text).trim();
        if (transcription.length() == 0) {
            throw failure("The transcription came back empty. The audio may be silent, or the model "
                    + "may not support audio input — configure an audio-capable STT model.", null);
        }
        return transcription;
    }

    /** Maps HTTP errors to user-readable messages, surfacing the server's own error text when present. */
    private String describeHttpError(int status, String body) {
        String serverMessage = extractErrorMessage(body);
        String hint = status == 400 || status == 422 || status == 500
                ? " The selected model may not support audio input — configure an audio-capable STT model."
                : "";
        return "Transcription failed with HTTP " + status
                + (serverMessage.length() > 0 ? ": " + serverMessage : "") + hint;
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.trim().length() == 0) {
            return "";
        }
        try {
            Object parsed = OllamaJson.parse(body);
            if (parsed instanceof Map) {
                Object error = ((Map) parsed).get("error");
                if (error instanceof Map) {
                    Object message = ((Map) error).get("message");
                    if (message != null) {
                        return String.valueOf(message);
                    }
                }
                if (error != null) {
                    return String.valueOf(error);
                }
            }
        } catch (RuntimeException ignored) {
            // Not JSON: fall through to the raw excerpt.
        }
        return excerpt(body);
    }

    private static String excerpt(String body) {
        String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return text.length() > 300 ? text.substring(0, 300) + " …" : text;
    }

    private String readText(InputStream inputStream) throws IOException {
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
            closeQuietly(inputStream);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private SpeechToTextException failure(String message, Throwable cause) {
        return new SpeechToTextException(message, cause);
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message != null && message.trim().length() > 0 ? message : throwable.getClass().getSimpleName();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null || value.trim().length() == 0
                ? "http://127.0.0.1:11434" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
