package com.aresstack.askai.research.text.opennlp;

import com.aresstack.askai.research.knowledge.RegexSentenceSegmenter;
import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The resolver's catalog/caching/fallback contract, exercised with fake catalog + loader (no real OpenNLP .bin,
 * no filesystem, no network): supported → correct model; loaded once; missing → regex fallback; corrupt →
 * typed error, never fallback; unknown → no random default; OpenNLP types never leak; per-language correctness
 * across "sessions".
 */
public class OpenNlpModelResolverTest {

    /** A distinguishable neutral segmenter labelled by the model file it was "loaded" from. */
    private static final class LabelPort implements SentenceSegmentationPort {
        final String label;

        LabelPort(String label) {
            this.label = label;
        }

        public List<String> segment(String text) {
            return Collections.singletonList(label + ":" + text);
        }
    }

    /** Fake loader: counts loads, returns a labelled port, throws for files marked corrupt. */
    private static final class FakeLoader implements SentenceModelLoader {
        int loads = 0;
        final Set<String> corrupt = new HashSet<String>();

        public SentenceSegmentationPort load(File modelFile) throws IOException {
            loads++;
            if (corrupt.contains(modelFile.getName())) {
                throw new IOException("simulated corrupt model: " + modelFile.getName());
            }
            return new LabelPort(modelFile.getName());
        }
    }

    /** Fake catalog: an in-memory language→file map (keys already normalized as the resolver passes them). */
    private static final class MapCatalog implements OpenNlpModelCatalog {
        final Map<String, File> byKey = new HashMap<String, File>();

        MapCatalog with(String key, String fileName) {
            byKey.put(key, new File(fileName));
            return this;
        }

        public Optional<File> sentenceModel(String languageKey) {
            return Optional.ofNullable(byKey.get(languageKey));
        }
    }

    @Test
    public void aSupportedLanguageResolvesItsOwnModel() {
        MapCatalog catalog = new MapCatalog().with("en", "sentence-en.bin").with("de", "sentence-de.bin");
        OpenNlpModelResolver resolver = new OpenNlpModelResolver(catalog, new FakeLoader());

        // case/whitespace-insensitive: the session code "EN " resolves the same model
        assertEquals(Collections.singletonList("sentence-en.bin:x"),
                resolver.openNlpSegmenterFor("EN ").get().segment("x"));
        assertEquals(Collections.singletonList("sentence-de.bin:x"),
                resolver.openNlpSegmenterFor("de").get().segment("x"));
    }

    @Test
    public void aModelIsLoadedOnlyOnceAndCached() {
        FakeLoader loader = new FakeLoader();
        OpenNlpModelResolver resolver =
                new OpenNlpModelResolver(new MapCatalog().with("en", "sentence-en.bin"), loader);

        SentenceSegmentationPort first = resolver.openNlpSegmenterFor("en").get();
        SentenceSegmentationPort second = resolver.openNlpSegmenterFor("en").get();
        assertSame("the same cached segmenter is returned", first, second);
        assertEquals("the model file is opened exactly once", 1, loader.loads);
    }

    @Test
    public void aMissingModelFallsBackToTheRegexSegmenter() {
        OpenNlpModelResolver resolver = new OpenNlpModelResolver(new MapCatalog(), new FakeLoader());
        assertFalse(resolver.openNlpSegmenterFor("de").isPresent());
        assertTrue("the defined fallback is the deterministic regex segmenter",
                resolver.segmenterFor("de") instanceof RegexSentenceSegmenter);
    }

    @Test
    public void aCorruptDeployedModelThrowsInsteadOfFallingBack() {
        FakeLoader loader = new FakeLoader();
        loader.corrupt.add("sentence-en.bin");
        final OpenNlpModelResolver resolver =
                new OpenNlpModelResolver(new MapCatalog().with("en", "sentence-en.bin"), loader);

        assertThrows(OpenNlpModelException.class, new org.junit.function.ThrowingRunnable() {
            public void run() {
                resolver.openNlpSegmenterFor("en");
            }
        });
        // segmenterFor must NOT swallow the corrupt-model error into a silent regex fallback
        assertThrows(OpenNlpModelException.class, new org.junit.function.ThrowingRunnable() {
            public void run() {
                resolver.segmenterFor("en");
            }
        });
    }

    @Test
    public void anUnknownLanguageOpensNoRandomDefaultModel() {
        FakeLoader loader = new FakeLoader();
        OpenNlpModelResolver resolver =
                new OpenNlpModelResolver(new MapCatalog().with("en", "sentence-en.bin"), loader);

        assertFalse("no model is invented for an unknown language",
                resolver.openNlpSegmenterFor("xx").isPresent());
        assertTrue(resolver.segmenterFor("xx") instanceof RegexSentenceSegmenter);
        assertEquals("no model file is opened for an unknown language", 0, loader.loads);
    }

    @Test
    public void resolutionIsPurelyLocalWithNoNetworkOrClasspathScan() {
        // With an empty in-memory catalog the resolver reaches no filesystem, no network and no classpath —
        // it simply returns the local regex fallback and never invokes the loader.
        FakeLoader loader = new FakeLoader();
        OpenNlpModelResolver resolver = new OpenNlpModelResolver(new MapCatalog(), loader);
        assertTrue(resolver.segmenterFor("en") instanceof RegexSentenceSegmenter);
        assertEquals(0, loader.loads);
    }

    @Test
    public void theResolverApiNeverLeaksOpenNlpTypes() throws Exception {
        for (Method m : OpenNlpModelResolver.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            assertNotOpenNlp(m.getReturnType());
            for (Class<?> p : m.getParameterTypes()) {
                assertNotOpenNlp(p);
            }
        }
        for (Constructor<?> c : OpenNlpModelResolver.class.getDeclaredConstructors()) {
            if (!java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
                continue;
            }
            for (Class<?> p : c.getParameterTypes()) {
                assertNotOpenNlp(p);
            }
        }
    }

    private static void assertNotOpenNlp(Class<?> type) {
        Class<?> component = type.isArray() ? type.getComponentType() : type;
        String name = component.getName();
        assertFalse("public resolver API leaks the OpenNLP type " + name, name.startsWith("opennlp."));
    }

    @Test
    public void concurrentSessionsWithDifferentLanguagesEachGetTheCorrectSegmentation() {
        MapCatalog catalog = new MapCatalog().with("en", "sentence-en.bin").with("de", "sentence-de.bin");
        OpenNlpModelResolver resolver = new OpenNlpModelResolver(catalog, new FakeLoader());

        SentenceSegmentationPort en = resolver.segmenterFor("en");
        SentenceSegmentationPort de = resolver.segmenterFor("de");
        assertEquals(Collections.singletonList("sentence-en.bin:s"), en.segment("s"));
        assertEquals(Collections.singletonList("sentence-de.bin:s"), de.segment("s"));
        assertFalse("different languages get distinct segmenters", en == de);
    }
}
