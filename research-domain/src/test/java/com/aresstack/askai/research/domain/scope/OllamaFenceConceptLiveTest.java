package com.aresstack.askai.research.domain.scope;

import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Reading;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Thresholds;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Weidezaun concept against REAL embeddings (local Ollama, nomic-embed-text — the same model
 * the knowledge worker runs on). Anchors carry ONLY the positive semantic text (never membership or
 * rationale — the negotiated decision must not leak into the embedding space). All assertions are
 * ORDINAL (which probe is closer to what), never absolute cosine thresholds: those are a later
 * calibration/settings question, not part of the concept.
 * <p>
 * Skips itself when no Ollama answers on 127.0.0.1:11434 or the model is missing.
 */
public class OllamaFenceConceptLiveTest {

    private static final String ENDPOINT = "http://127.0.0.1:11434/api/embed";
    private static final String MODEL = "nomic-embed-text:latest";

    @Test
    public void realEmbeddingsReproduceTheFenceGeometry() {
        // The negotiated fence: Arbeitsschutz-Wearables IN, Consumer-Fitness OUT.
        List<String> texts = Arrays.asList(
                "Schutzhelme mit integrierter Sensorik für Baustellen",        // 0 IN
                "Wearables zur Gasdetektion im industriellen Arbeitsschutz",   // 1 IN
                "Ermüdungserkennung bei Kranführern durch tragbare Sensoren",  // 2 IN
                "Fitness-Armbänder für private Läufer und Hobbysport",         // 3 OUT
                "Modische Accessoires mit LED-Beleuchtung",                    // 4 OUT
                // The probes:
                "Gaswarngerät an der Arbeitskleidung von Industriearbeitern",  // 5 → expect IN
                "Schrittzähler-Armband für den Freizeitgebrauch",              // 6 → expect OUT
                "Exoskelette zur Rückenentlastung von Lagerarbeitern");        // 7 → expect NOVEL-ish
        List<float[]> vectors = embedOrSkip(texts);

        ScopeFenceEvaluator fence = new ScopeFenceEvaluator(Arrays.asList(
                new AnchorVector("in-helme", ScopeAnchor.Membership.IN, vectors.get(0)),
                new AnchorVector("in-gas", ScopeAnchor.Membership.IN, vectors.get(1)),
                new AnchorVector("in-ermuedung", ScopeAnchor.Membership.IN, vectors.get(2)),
                new AnchorVector("out-fitness", ScopeAnchor.Membership.OUT, vectors.get(3)),
                new AnchorVector("out-mode", ScopeAnchor.Membership.OUT, vectors.get(4))));
        // Thresholds only shape the HINT; the assertions below are ordinal and independent of them.
        Thresholds thresholds = new Thresholds(0.6d, 0.05d);

        Reading inProbe = fence.evaluate(vectors.get(5), thresholds);
        Reading outProbe = fence.evaluate(vectors.get(6), thresholds);
        Reading novelProbe = fence.evaluate(vectors.get(7), thresholds);
        System.err.println("[fence-live] IN-probe    " + describe(inProbe));
        System.err.println("[fence-live] OUT-probe   " + describe(outProbe));
        System.err.println("[fence-live] NOVEL-probe " + describe(novelProbe));

        // 1) The gas-detection probe leans clearly IN — and to the RIGHT post.
        assertTrue("IN probe must lean IN (margin=" + inProbe.margin + ")", inProbe.margin > 0);
        assertEquals("in-gas", inProbe.nearestInAnchorId);

        // 2) The step-counter probe leans OUT — the ruled-out region works as a real fence side.
        assertTrue("OUT probe must lean OUT (margin=" + outProbe.margin + ")", outProbe.margin < 0);
        assertEquals("out-fitness", outProbe.nearestOutAnchorId);

        // 3) The exoskeleton probe is the most NOVEL of the three: farther from every post than
        //    the probes that match known regions — the "hole in the fence" is detectable.
        double inProbeMax = Math.max(inProbe.nearestInSimilarity, inProbe.nearestOutSimilarity);
        double outProbeMax = Math.max(outProbe.nearestInSimilarity, outProbe.nearestOutSimilarity);
        double novelMax = Math.max(novelProbe.nearestInSimilarity, novelProbe.nearestOutSimilarity);
        assertTrue("the unexplored region reads as most novel (novel=" + novelMax
                + " in=" + inProbeMax + " out=" + outProbeMax + ")",
                novelMax < inProbeMax && novelMax < outProbeMax);

        // 4) Ordinal sanity: the IN probe is closer to the IN side than the OUT probe is.
        assertTrue(inProbe.nearestInSimilarity > outProbe.nearestInSimilarity);
    }

    private static String describe(Reading reading) {
        return String.format(java.util.Locale.ROOT,
                "sIn=%.3f(%s) sOut=%.3f(%s) margin=%+.3f hint=%s",
                reading.nearestInSimilarity, reading.nearestInAnchorId,
                reading.nearestOutSimilarity, reading.nearestOutAnchorId,
                reading.margin, reading.hint);
    }

    // ------------------------------------------------------------------ minimal Ollama /api/embed client

    private static List<float[]> embedOrSkip(List<String> texts) {
        try {
            StringBuilder body = new StringBuilder("{\"model\":\"").append(MODEL)
                    .append("\",\"input\":[");
            for (int i = 0; i < texts.size(); i++) {
                if (i > 0) {
                    body.append(',');
                }
                body.append('"').append(texts.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"');
            }
            body.append("]}");
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setConnectTimeout(2_000);
            connection.setReadTimeout(60_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            OutputStream out = connection.getOutputStream();
            out.write(body.toString().getBytes(Charset.forName("UTF-8")));
            out.close();
            int status = connection.getResponseCode();
            Assume.assumeTrue("Ollama answered " + status + " (model installed?)", status == 200);
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            in.close();
            List<float[]> vectors = parseEmbeddings(
                    new String(buffer.toByteArray(), Charset.forName("UTF-8")));
            Assume.assumeTrue("embedding count matches input", vectors.size() == texts.size());
            return vectors;
        } catch (IOException noOllama) {
            Assume.assumeNoException("no local Ollama on 127.0.0.1:11434 — concept test skipped",
                    noOllama);
            throw new IllegalStateException("unreachable");
        }
    }

    /** Parse the {@code "embeddings": [[…],[…]]} matrix — the only field this test needs. */
    private static List<float[]> parseEmbeddings(String json) {
        int key = json.indexOf("\"embeddings\"");
        if (key < 0) {
            return new ArrayList<float[]>();
        }
        int outer = json.indexOf('[', key);
        List<float[]> vectors = new ArrayList<float[]>();
        int i = outer + 1;
        while (i < json.length() && json.charAt(i) != ']') {
            if (json.charAt(i) == '[') {
                int end = json.indexOf(']', i);
                String[] parts = json.substring(i + 1, end).split(",");
                float[] vector = new float[parts.length];
                for (int p = 0; p < parts.length; p++) {
                    vector[p] = Float.parseFloat(parts[p].trim());
                }
                vectors.add(vector);
                i = end + 1;
            } else {
                i++;
            }
        }
        return vectors;
    }
}
