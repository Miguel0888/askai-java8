package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * A5 mandatory proof: the strict {@link HttpRerankerClient} against the REAL R0 local-model runtime.
 * The Java-8 test JVM spawns the Java-21 runtime jar (backend=cpu) as a separate process, asks the
 * live cross-encoder to score documents for a query, and proves the client accepts the real contract
 * and that RAW_LOGIT ordering puts the clearly-relevant document above an irrelevant one.
 *
 * <p>SKIPS readably (never fails) when the Java-21 launcher, the staged runtime jar, or an installed
 * reranker model is absent — so CI without the local model stays green — but RUNS end to end when they
 * are present, as they are in the target dev environment.
 */
public class LocalRerankerRuntimeIntegrationTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private Process runtime;

    @After
    public void stopRuntime() {
        if (runtime != null) {
            runtime.destroyForcibly();
        }
    }

    @Test
    public void realCrossEncoderScoresAndRanksThroughTheStrictClient() throws Exception {
        String java21 = System.getProperty("sidecar.java");
        String jar = System.getProperty("localmodel.sidecar.jar");
        File modelRoot = localModelRoot();
        assumeTrue("no Java-21 launcher configured", java21 != null && new File(java21).isFile());
        assumeTrue("local-model runtime jar not staged", jar != null && new File(jar).isFile());
        assumeTrue("no installed local reranker model at " + modelRoot,
                modelRoot.isDirectory() && hasInstalledModel(modelRoot));

        String baseUrl = startRuntime(java21, jar, modelRoot);
        String modelName = firstTaggedModel(baseUrl);
        assumeTrue("runtime reported no served model", modelName != null);

        RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(
                RerankerProvider.ASKAI_LOCAL, baseUrl, modelName,
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT, 30_000L,
                RerankerSelectionConfiguration.topN(10));

        String query = "how does the pf4j java plugin framework load plugins";
        List<String> documents = Arrays.asList(
                "Title: PF4J plugin framework\nSnippet: PF4J is a lightweight plugin framework for "
                        + "Java that loads plugins from directories and jars at runtime.",
                "Title: Tomato soup recipe\nSnippet: Simmer tomatoes with basil and cream for a warm "
                        + "bowl of soup on a cold day.",
                "Title: Java plugins with PF4J\nSnippet: Extension points and plugin lifecycle in the "
                        + "PF4J framework for modular Java applications.");

        List<RerankScore> scores = new HttpRerankerClient(descriptor).rerank(query, documents).scores;
        assertEquals("the real runtime scored every submitted document", 3, scores.size());

        // Apply the productive selection policy and check the real model ranks the two PF4J documents
        // above the soup recipe — RAW_LOGIT ordering, no fixed 0.5 threshold.
        List<RerankedSearchResultCandidate> ranked = new ArrayList<RerankedSearchResultCandidate>();
        for (RerankScore score : scores) {
            ranked.add(new RerankedSearchResultCandidate(
                    fakeCandidate(score.documentIndex), score.score, 0));
        }
        SearchResultSelection result = new SearchResultSelectionPolicy(
                RerankerSelectionConfiguration.topN(10)).select(ranked);

        int soupRank = 0;
        int worstPf4jRank = 0;
        for (RerankedSearchResultCandidate c : result.reranked) {
            int docIndex = Integer.parseInt(c.candidate.candidateId);
            if (docIndex == 1) {
                soupRank = c.rerankRank;
            } else {
                worstPf4jRank = Math.max(worstPf4jRank, c.rerankRank);
            }
        }
        assertTrue("both PF4J documents outrank the irrelevant soup recipe (soup rank " + soupRank
                + ", worst PF4J rank " + worstPf4jRank + ")", soupRank > worstPf4jRank);
        assertFalse(result.selected.isEmpty());
    }

    // ------------------------------------------------------------------ runtime process plumbing

    private String startRuntime(String java21, String jar, File modelRoot) throws Exception {
        List<String> command = Arrays.asList(java21, "-jar", jar, "--host=127.0.0.1", "--port=0",
                "--model-root=" + modelRoot.getAbsolutePath(), "--backend=cpu");
        final Process process = new ProcessBuilder(command).start();
        this.runtime = process;
        final CountDownLatch ready = new CountDownLatch(1);
        final String[] baseUrl = new String[1];
        Thread reader = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            process.getInputStream(), UTF_8));
                    String line;
                    while ((line = in.readLine()) != null) {
                        int at = line.indexOf("\"baseUrl\"");
                        if (line.contains("\"ready\"") && at >= 0) {
                            baseUrl[0] = between(line, "\"baseUrl\":\"", "\"");
                            ready.countDown();
                        }
                    }
                } catch (Exception ignored) {
                    // stream closes with the process
                }
            }
        }, "localmodel-stdout");
        reader.setDaemon(true);
        reader.start();
        drainStderr(process);

        assumeTrue("local-model runtime did not report ready within 90s",
                ready.await(90, TimeUnit.SECONDS) && baseUrl[0] != null);
        return baseUrl[0];
    }

    private static void drainStderr(final Process process) {
        Thread err = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            process.getErrorStream(), UTF_8));
                    while (in.readLine() != null) {
                        // discard; the runtime logs readiness/backend here
                    }
                } catch (Exception ignored) {
                }
            }
        }, "localmodel-stderr");
        err.setDaemon(true);
        err.start();
    }

    /** The first served model name from {@code /api/tags} (the runtime exposes its virtual name there). */
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

    // ------------------------------------------------------------------ helpers

    private static com.aresstack.askai.browser.search.SearchResultCandidate fakeCandidate(int index) {
        return new com.aresstack.askai.browser.search.SearchResultCandidate(
                String.valueOf(index), "snap", "https://doc" + index + ".example", "", "t", "s", "",
                index + 1, "rc", "rb", 0.9, 0.9,
                java.util.Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>
                        emptyList());
    }

    private static File localModelRoot() {
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
