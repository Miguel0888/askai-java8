package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptorCodec;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A shared test harness that spawns the REAL Java-21 R0 local-model runtime (backend=cpu) against the
 * installed cross-encoder and exposes it as a {@link RerankerConfigurationSnapshotProvider}. Returns
 * {@code null} from {@link #startOrNull()} when the Java-21 launcher, the staged runtime jar, or an
 * installed reranker model is absent, so callers can skip readably.
 */
public final class LiveLocalRerankerRuntime {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final Process process;
    public final String baseUrl;
    public final String modelName;

    private LiveLocalRerankerRuntime(Process process, String baseUrl, String modelName) {
        this.process = process;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
    }

    /** Prerequisites present in this environment? (Java-21 launcher, staged jar, installed model.) */
    public static boolean available() {
        String java21 = System.getProperty("sidecar.java");
        String jar = System.getProperty("localmodel.sidecar.jar");
        File modelRoot = localModelRoot();
        return java21 != null && new File(java21).isFile()
                && jar != null && new File(jar).isFile()
                && modelRoot.isDirectory() && hasInstalledModel(modelRoot);
    }

    /** Start the runtime, or return {@code null} when prerequisites are missing. */
    public static LiveLocalRerankerRuntime startOrNull() throws Exception {
        if (!available()) {
            return null;
        }
        String java21 = System.getProperty("sidecar.java");
        String jar = System.getProperty("localmodel.sidecar.jar");
        File modelRoot = localModelRoot();

        final Process process = new ProcessBuilder(Arrays.asList(java21, "-jar", jar,
                "--host=127.0.0.1", "--port=0", "--model-root=" + modelRoot.getAbsolutePath(),
                "--backend=cpu")).start();
        final CountDownLatch ready = new CountDownLatch(1);
        final String[] baseUrl = new String[1];
        startReader(process.getInputStream(), new java.util.function.Consumer<String>() {
            public void accept(String line) {
                int at = line.indexOf("\"baseUrl\"");
                if (line.contains("\"ready\"") && at >= 0) {
                    baseUrl[0] = between(line, "\"baseUrl\":\"", "\"");
                    ready.countDown();
                }
            }
        });
        startReader(process.getErrorStream(), null);
        if (!ready.await(90, TimeUnit.SECONDS) || baseUrl[0] == null) {
            process.destroyForcibly();
            return null;
        }
        String model = firstTaggedModel(baseUrl[0]);
        if (model == null) {
            process.destroyForcibly();
            return null;
        }
        return new LiveLocalRerankerRuntime(process, baseUrl[0], model);
    }

    public RerankerEndpointDescriptor descriptor(int topN) {
        return new RerankerEndpointDescriptor(RerankerProvider.ASKAI_LOCAL, baseUrl, modelName,
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT, 30_000L,
                RerankerSelectionConfiguration.topN(topN));
    }

    /** A provider that writes a real snapshot pointing at this running runtime. */
    public RerankerConfigurationSnapshotProvider asProvider(final int topN) {
        return new RerankerConfigurationSnapshotProvider() {
            public RerankerConfigurationSnapshot prepareForSession(String sessionId,
                                                                   File sessionDirectory)
                    throws RerankerConfigurationException {
                RerankerConfigurationDocument document =
                        RerankerConfigurationDocument.current(0L, descriptor(topN));
                File target = new File(sessionDirectory, "reranker-config.json");
                try {
                    Files.write(target.toPath(),
                            RerankerEndpointDescriptorCodec.toJson(document).getBytes(UTF_8));
                } catch (Exception ex) {
                    throw new RerankerConfigurationException("cannot write snapshot: "
                            + ex.getMessage(), ex);
                }
                return new RerankerConfigurationSnapshot(target.getAbsoluteFile(), document);
            }
        };
    }

    public void close() {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void startReader(final InputStream stream,
                                    final java.util.function.Consumer<String> onLine) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(stream, UTF_8));
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (onLine != null) {
                            onLine.accept(line);
                        }
                    }
                } catch (Exception ignored) {
                    // stream closes with the process
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static String firstTaggedModel(String baseUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(join(baseUrl, "/api/tags"))
                .openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        if (connection.getResponseCode() != 200) {
            return null;
        }
        String body = readAll(connection.getInputStream());
        connection.disconnect();
        return between(body, "\"model\":\"", "\"");
    }

    static File localModelRoot() {
        String appData = System.getenv("APPDATA");
        File base = appData != null && !appData.trim().isEmpty()
                ? new File(appData) : new File(System.getProperty("user.home"), "AppData/Roaming");
        return new File(new File(new File(base, ".askai"), "models"), "local");
    }

    private static boolean hasInstalledModel(File modelRoot) {
        File[] children = modelRoot.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.isDirectory() && new File(child, "askai-local-model.json").isFile()) {
                return true;
            }
        }
        return false;
    }

    private static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        if (start < 0) {
            return null;
        }
        start += open.length();
        int end = text.indexOf(close, start);
        return end < 0 ? null : text.substring(start, end);
    }

    private static String join(String baseUrl, String path) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + path
                : baseUrl + path;
    }

    private static String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        stream.close();
        return new String(buffer.toByteArray(), UTF_8);
    }
}
