package com.aresstack.askai.research.text.opennlp;

import com.aresstack.askai.research.knowledge.RegexSentenceSegmenter;
import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves the session's language (its own ISO code, e.g. {@code "en"} / {@code "de"} — the SAME representation
 * the research runtime already persists, never a new {@code knowledge.language} concept) to a neutral
 * {@link SentenceSegmentationPort}, encapsulating ALL OpenNLP handling inside this module.
 *
 * <p>Contract:</p>
 * <ul>
 * <li>a deployed, loadable model for the language → the OpenNLP segmenter (loaded ONCE and cached);</li>
 * <li>no model deployed for the language (or an unknown language) → {@link Optional#empty()} from
 *     {@link #openNlpSegmenterFor}, and {@link #segmenterFor} applies the deterministic
 *     {@link RegexSentenceSegmenter} fallback — never a "random default" model;</li>
 * <li>a model that IS deployed but cannot be loaded (corrupt/unsupported) → a typed {@link OpenNlpModelException}
 *     from BOTH methods (it never silently falls back).</li>
 * </ul>
 *
 * <p>Models are deployment artifacts resolved from a configurable {@link OpenNlpModelCatalog}; nothing is
 * downloaded at runtime. Loaded segmenters are cached per language, so a model is opened once regardless of how
 * many captures or sessions ask for it; the cache is thread-safe and different languages load independently.</p>
 */
public final class OpenNlpModelResolver {

    private final OpenNlpModelCatalog catalog;
    private final SentenceModelLoader loader;
    private final ConcurrentMap<String, SentenceSegmentationPort> loadedByLanguage =
            new ConcurrentHashMap<String, SentenceSegmentationPort>();
    private final Object loadLock = new Object();
    private final SentenceSegmentationPort fallback = new RegexSentenceSegmenter();

    /** Productive resolver over the real OpenNLP loader. */
    public OpenNlpModelResolver(OpenNlpModelCatalog catalog) {
        this(catalog, SentenceModelLoader.openNlp());
    }

    /** Testable resolver: inject a loader so corrupt/missing/caching behaviour is exercised without a real .bin. */
    public OpenNlpModelResolver(OpenNlpModelCatalog catalog, SentenceModelLoader loader) {
        if (catalog == null || loader == null) {
            throw new IllegalArgumentException("catalog and loader are required");
        }
        this.catalog = catalog;
        this.loader = loader;
    }

    /**
     * The OpenNLP segmenter for the language, or empty when no model is deployed for it (the expected,
     * fallback-permitting state). Loaded once and cached.
     *
     * @throws OpenNlpModelException when a model IS deployed but cannot be loaded (corrupt/unsupported)
     */
    public Optional<SentenceSegmentationPort> openNlpSegmenterFor(String languageKey) {
        String key = normalize(languageKey);
        SentenceSegmentationPort cached = loadedByLanguage.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<File> model = catalog.sentenceModel(key);
        if (!model.isPresent()) {
            return Optional.empty(); // no model for this language — an expected state, not an error
        }
        synchronized (loadLock) {
            cached = loadedByLanguage.get(key);
            if (cached != null) {
                return Optional.of(cached);
            }
            File file = model.get();
            SentenceSegmentationPort port;
            try {
                port = loader.load(file);
            } catch (IOException | RuntimeException ex) {
                // A corrupt/unsupported artifact surfaces as an IOException OR an unchecked parser error
                // (e.g. OpenNLP throws NPE on a non-model file) — either way it is a hard error, never a
                // silent regex fallback.
                throw new OpenNlpModelException("the OpenNLP sentence model for language '" + key
                        + "' at " + file + " is deployed but could not be loaded (corrupt or unsupported "
                        + "format) — refusing to fall back silently", ex);
            }
            if (port == null) {
                throw new OpenNlpModelException("the OpenNLP model loader returned no segmenter for language '"
                        + key + "' at " + file, null);
            }
            loadedByLanguage.put(key, port);
            return Optional.of(port);
        }
    }

    /**
     * The productive choice for a session: the deployed OpenNLP segmenter for the language, or the deterministic
     * regex fallback when none is deployed. A deployed-but-corrupt model still throws {@link OpenNlpModelException}
     * (no silent fallback).
     */
    public SentenceSegmentationPort segmenterFor(String languageKey) {
        Optional<SentenceSegmentationPort> openNlp = openNlpSegmenterFor(languageKey);
        return openNlp.isPresent() ? openNlp.get() : fallback;
    }

    private static String normalize(String languageKey) {
        return languageKey == null ? "" : languageKey.trim().toLowerCase();
    }
}
