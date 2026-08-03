package com.aresstack.askai.research.host;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationException;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshot;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.nlp.NlpModelDescriptor;
import com.aresstack.askai.research.knowledge.RegexSentenceSegmenter;
import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;
import com.aresstack.askai.research.text.opennlp.OpenNlpModelException;
import com.aresstack.askai.research.text.opennlp.SentenceModelLoader;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The session sentence resolver: soft reasons → regex, hard reasons/corrupt → hard fail, session-language authoritative. */
public class SessionSentenceSegmenterTest {

    /** A provider that returns a fixed descriptor for a language, or throws a fixed reason. */
    private static final class FakeProvider implements NlpConfigurationSnapshotProvider {
        NlpCapability lastCapability;
        String lastLanguage;
        NlpModelDescriptor descriptor;
        NlpConfigurationException.Reason throwReason;

        public NlpConfigurationSnapshot resolve(NlpCapability capability, String languageCode)
                throws NlpConfigurationException {
            lastCapability = capability;
            lastLanguage = languageCode;
            if (throwReason != null) {
                throw new NlpConfigurationException(throwReason, "test");
            }
            return new NlpConfigurationSnapshot(descriptor);
        }
    }

    /** A loader that returns a segmenter labelled by the artifact file name (no real OpenNLP parsing). */
    private static final class LabelLoader implements SentenceModelLoader {
        public SentenceSegmentationPort load(final File modelFile) {
            return new SentenceSegmentationPort() {
                public List<String> segment(String text) {
                    return Collections.singletonList(modelFile.getName() + ":" + text);
                }
            };
        }
    }

    private static File artifact(String name) throws IOException {
        File dir = Files.createTempDirectory("askai-n6").toFile();
        File file = new File(dir, name);
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        return file;
    }

    private static NlpModelDescriptor descriptor(String lang, File artifact) {
        return new NlpModelDescriptor("apache-opennlp/sentence-" + lang, NlpCapability.SENTENCE_DETECTION,
                lang, "opennlp", "1.5", "1.9.4", artifact.getAbsolutePath(), "sha");
    }

    @Test
    public void deResolvesExactlySentenceDetectionDe() throws IOException {
        FakeProvider provider = new FakeProvider();
        provider.descriptor = descriptor("de", artifact("de-sent.bin"));
        SessionSentenceSegmenter resolved =
                SessionSentenceSegmenter.resolve(provider, "de", new LabelLoader());

        assertEquals(NlpCapability.SENTENCE_DETECTION, provider.lastCapability);
        assertEquals("de", provider.lastLanguage);
        assertEquals(Collections.singletonList("de-sent.bin:x"), resolved.segmenter.segment("x"));
        assertTrue(resolved.description.contains("OpenNLP(modelId=apache-opennlp/sentence-de"));
        assertTrue(resolved.description.contains("artifact=de-sent.bin"));
    }

    @Test
    public void enResolvesExactlySentenceDetectionEn() throws IOException {
        FakeProvider provider = new FakeProvider();
        provider.descriptor = descriptor("en", artifact("en-sent.bin"));
        SessionSentenceSegmenter resolved =
                SessionSentenceSegmenter.resolve(provider, "en", new LabelLoader());
        assertEquals("en", provider.lastLanguage);
        assertEquals(Collections.singletonList("en-sent.bin:x"), resolved.segmenter.segment("x"));
    }

    @Test
    public void notConfiguredFallsBackToRegex() {
        FakeProvider provider = new FakeProvider();
        provider.throwReason = NlpConfigurationException.Reason.MODEL_NOT_CONFIGURED;
        SessionSentenceSegmenter resolved =
                SessionSentenceSegmenter.resolve(provider, "de", new LabelLoader());
        assertTrue(resolved.segmenter instanceof RegexSentenceSegmenter);
        assertEquals("regex-fallback(reason=MODEL_NOT_CONFIGURED)", resolved.description);
    }

    @Test
    public void notInstalledFallsBackToRegex() {
        FakeProvider provider = new FakeProvider();
        provider.throwReason = NlpConfigurationException.Reason.MODEL_NOT_INSTALLED;
        SessionSentenceSegmenter resolved =
                SessionSentenceSegmenter.resolve(provider, "de", new LabelLoader());
        assertTrue(resolved.segmenter instanceof RegexSentenceSegmenter);
        assertEquals("regex-fallback(reason=MODEL_NOT_INSTALLED)", resolved.description);
    }

    @Test
    public void noProviderFallsBackToRegex() {
        SessionSentenceSegmenter resolved = SessionSentenceSegmenter.resolve(null, "de", new LabelLoader());
        assertTrue(resolved.segmenter instanceof RegexSentenceSegmenter);
    }

    @Test
    public void artifactMissingFailsHard() {
        FakeProvider provider = new FakeProvider();
        provider.throwReason = NlpConfigurationException.Reason.ARTIFACT_MISSING;
        assertHardFailure(provider);
    }

    @Test
    public void checksumMismatchFailsHard() {
        FakeProvider provider = new FakeProvider();
        provider.throwReason = NlpConfigurationException.Reason.CHECKSUM_MISMATCH;
        assertHardFailure(provider);
    }

    @Test
    public void aPresentButCorruptModelFailsHardWithOpenNlpError() throws IOException {
        FakeProvider provider = new FakeProvider();
        provider.descriptor = descriptor("de", artifact("de-sent.bin")); // 3 garbage bytes, not a real model
        try {
            SessionSentenceSegmenter.resolve(provider, "de"); // PRODUCTIVE OpenNLP loader
            fail("a corrupt model must fail hard, never silently regex");
        } catch (OpenNlpModelException expected) {
            // ok
        }
    }

    @Test
    public void twoSessionsWithDifferentLanguagesGetTheirOwnSegmentation() throws IOException {
        FakeProvider de = new FakeProvider();
        de.descriptor = descriptor("de", artifact("de-sent.bin"));
        FakeProvider en = new FakeProvider();
        en.descriptor = descriptor("en", artifact("en-sent.bin"));

        assertEquals(Collections.singletonList("de-sent.bin:s"),
                SessionSentenceSegmenter.resolve(de, "de", new LabelLoader()).segmenter.segment("s"));
        assertEquals(Collections.singletonList("en-sent.bin:s"),
                SessionSentenceSegmenter.resolve(en, "en", new LabelLoader()).segmenter.segment("s"));
    }

    @Test
    public void theResolverApiTakesOnlyTheNeutralHostProvider() {
        // No store, no download client, no settings type in the resolve signature (and the plugin has NO
        // dependency on askai-app, so LocalNlpModelStore / NlpDownloadClient are not even on the classpath).
        for (java.lang.reflect.Method m : SessionSentenceSegmenter.class.getDeclaredMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                String name = p.getName();
                assertTrue("resolver API must not reference an askai-app localmodels type: " + name,
                        !name.contains("localmodels"));
            }
        }
    }

    private static void assertHardFailure(FakeProvider provider) {
        try {
            SessionSentenceSegmenter.resolve(provider, "de", new LabelLoader());
            fail("a broken/tampered selected model must fail hard, not regex");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(provider.throwReason.name()));
        }
    }
}
