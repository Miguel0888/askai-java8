package com.aresstack.askai.java8.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.ollama4j.json.OllamaJson;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** RemoteGgufInstaller against a local fake Ollama: /api/create carries info.capabilities correctly. */
public class RemoteGgufInstallerTest {

    private HttpServer server;
    private final AtomicReference<String> createBody = new AtomicReference<String>();
    private final AtomicReference<String> createResponse =
            new AtomicReference<String>("{\"status\":\"success\"}\n");
    private File gguf;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if (path.startsWith("/api/blobs/")) {
                    exchange.sendResponseHeaders(200, -1); // pretend the blob is already present
                    exchange.close();
                    return;
                }
                if (path.equals("/api/create") && "POST".equals(method)) {
                    createBody.set(new String(drain(exchange.getRequestBody()), StandardCharsets.UTF_8));
                    byte[] response = createResponse.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    OutputStream out = exchange.getResponseBody();
                    out.write(response);
                    out.close();
                    return;
                }
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        gguf = writeMinimalGguf();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private RemoteGgufInstaller installer() {
        return new RemoteGgufInstaller("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createRequest() {
        return (Map<String, Object>) OllamaJson.parse(createBody.get());
    }

    // ------------------------------------------------------------------ info.capabilities

    @Test
    public void hfImportSendsInfoCapabilities() throws Exception {
        installer().install("audio-model", gguf, Collections.<File>emptyList(),
                Arrays.asList("completion", "audio"), null);
        Map<String, Object> body = createRequest();
        assertEquals("audio-model", body.get("model"));
        Object info = body.get("info");
        assertTrue("info present", info instanceof Map);
        assertEquals(Arrays.asList("completion", "audio"), ((Map) info).get("capabilities"));
    }

    @Test
    public void visionMapsThrough() throws Exception {
        installer().install("vision-model", gguf, Collections.<File>emptyList(),
                Arrays.asList("completion", "vision"), null);
        assertEquals(Arrays.asList("completion", "vision"), capabilities());
    }

    @Test
    public void toolsAndThinkingMapThrough() throws Exception {
        installer().install("m", gguf, Collections.<File>emptyList(),
                Arrays.asList("completion", "tools", "thinking"), null);
        assertEquals(Arrays.asList("completion", "tools", "thinking"), capabilities());
    }

    @Test
    public void manualImportSendsNoInfo() throws Exception {
        installer().install("manual-model", gguf, null);
        assertNull("no info for a manual import", createRequest().get("info"));
    }

    @Test
    public void emptyCapabilitiesOmitInfo() throws Exception {
        installer().install("m", gguf, Collections.<File>emptyList(),
                Collections.<String>emptyList(), null);
        assertNull(createRequest().get("info"));
    }

    @Test
    public void enumNamesAndDuplicatesAreNormalizedAway() throws Exception {
        // "TEXT" is not an Ollama tag (it is "completion") and must be dropped; "AUDIO" lower-cases to
        // the real "audio" tag; the duplicate is removed. No enum-style names reach the wire.
        installer().install("m", gguf, Collections.<File>emptyList(),
                Arrays.asList("TEXT", "AUDIO", "audio"), null);
        assertEquals(Arrays.asList("audio"), capabilities());
        String json = createBody.get();
        assertFalse(json, json.contains("TEXT"));
        assertFalse(json, json.contains("\"AUDIO\""));
    }

    @Test
    public void normalizeCapabilitiesIsStableAndTagOnly() {
        assertEquals(Arrays.asList("completion", "audio"),
                RemoteGgufInstaller.normalizeCapabilities(Arrays.asList("completion", "audio", "completion")));
        assertEquals(Collections.<String>emptyList(),
                RemoteGgufInstaller.normalizeCapabilities(Arrays.asList("TEXT", "nonsense", null)));
    }

    // ------------------------------------------------------------------ structured metadata

    @Test
    public void metadataInfoFieldsReachTheWire() throws Exception {
        com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata metadata =
                new com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata.Builder()
                        .capabilities(Arrays.asList("completion", "tools"))
                        .modelFamily(com.aresstack.askai.java8.hf.meta.MetadataValue.high(
                                "qwen3", com.aresstack.askai.java8.hf.meta.MetadataSource.CONFIG_JSON))
                        .quantizationLevel(com.aresstack.askai.java8.hf.meta.MetadataValue.high(
                                "Q4_K_M", com.aresstack.askai.java8.hf.meta.MetadataSource.FILE_NAME))
                        .build();
        installer().install("m", gguf, Collections.<File>emptyList(), metadata, null);
        Map<String, Object> info = (Map<String, Object>) createRequest().get("info");
        assertEquals(Arrays.asList("completion", "tools"), info.get("capabilities"));
        assertEquals("qwen3", info.get("model_family"));
        assertEquals("Q4_K_M", info.get("quantization_level"));
    }

    @Test
    public void emptyMetadataOmitsInfo() throws Exception {
        installer().install("m", gguf, Collections.<File>emptyList(),
                com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata.empty(), null);
        assertNull(createRequest().get("info"));
    }

    // ------------------------------------------------------------------ streamed create errors

    @Test
    public void streamedCreateErrorFailsTheInstall() throws Exception {
        // Ollama can report a failure mid-stream (e.g. unsupported architecture) and then just end the
        // stream — that must surface as an install failure, not a silent success.
        createResponse.set("{\"status\":\"reading model\"}\n{\"error\":\"unsupported model architecture\"}\n");
        try {
            installer().install("m", gguf, Collections.<File>emptyList(),
                    Collections.<String>emptyList(), null);
            org.junit.Assert.fail("expected an IOException for a streamed create error");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unsupported model architecture"));
        }
    }

    @Test
    public void createStreamWithoutSuccessFailsTheInstall() throws Exception {
        // A stream that ends with neither an error nor a success confirmation is not a completed install.
        createResponse.set("{\"status\":\"reading model\"}\n{\"status\":\"writing manifest\"}\n");
        try {
            installer().install("m", gguf, Collections.<File>emptyList(),
                    Collections.<String>emptyList(), null);
            org.junit.Assert.fail("expected an IOException when the stream ends without success");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("without success"));
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> capabilities() {
        Map<String, Object> info = (Map<String, Object>) createRequest().get("info");
        return (List<String>) info.get("capabilities");
    }

    /** A minimal but valid GGUF v3 header (0 tensors, 0 metadata), padded to the aligned data start. */
    private static File writeMinimalGguf() throws Exception {
        File file = File.createTempFile("askai-min-", ".gguf");
        file.deleteOnExit();
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.writeBytes("GGUF");
            writeIntLe(raf, 3);          // version
            writeLongLe(raf, 0L);        // tensor count (u64 for version != 1)
            writeLongLe(raf, 0L);        // metadata kv count
            raf.write(new byte[8]);      // pad so length >= alignUp(24, 32) = 32
        } finally {
            raf.close();
        }
        return file;
    }

    private static void writeIntLe(RandomAccessFile raf, int value) throws java.io.IOException {
        raf.write(value & 0xFF);
        raf.write((value >> 8) & 0xFF);
        raf.write((value >> 16) & 0xFF);
        raf.write((value >> 24) & 0xFF);
    }

    private static void writeLongLe(RandomAccessFile raf, long value) throws java.io.IOException {
        for (int i = 0; i < 8; i++) {
            raf.write((int) ((value >> (8 * i)) & 0xFF));
        }
    }

    private static byte[] drain(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
