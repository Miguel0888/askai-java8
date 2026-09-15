package com.aresstack.askai.research.capture;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * #39 — the ONE per-format extraction seam behind the neutral import port: every
 * {@link SourceImportService.Kind} maps to exactly one mechanical extractor. HTML is the
 * jsoup structural extraction, TEXT is a passthrough; PDF joins as another implementation
 * of the SAME interface (its library choice is a registry entry, never a special path in
 * the service). Extractors are deterministic and model-free; oddities surface as warnings.
 */
final class SourceExtractors {

    /** One format's mechanical raw→normalized extraction. */
    interface SourceExtractor {
        /** The stable extractor identity recorded in the provenance. */
        String id();

        /** Extract the normalized text (+ title suggestion + warnings) from ONE delivery. */
        Extraction extract(String rawContent, String originUri);
    }

    /** The extraction result: non-empty title suggestion, normalized text, warnings. */
    static final class Extraction {
        final String title;
        final String text;
        final List<String> warnings;

        Extraction(String title, String text, List<String> warnings) {
            this.title = title;
            this.text = text;
            this.warnings = Collections.unmodifiableList(warnings);
        }
    }

    private SourceExtractors() {
    }

    /** The productive registry — EVERY kind has exactly one extractor (pinned by test). */
    static Map<SourceImportService.Kind, SourceExtractor> defaults() {
        Map<SourceImportService.Kind, SourceExtractor> registry =
                new EnumMap<SourceImportService.Kind, SourceExtractor>(
                        SourceImportService.Kind.class);
        registry.put(SourceImportService.Kind.HTML, new SourceExtractor() {
            public String id() {
                return HtmlSourceExtraction.EXTRACTOR_ID;
            }

            public Extraction extract(String rawContent, String originUri) {
                HtmlSourceExtraction.Extracted extracted =
                        HtmlSourceExtraction.extract(rawContent, originUri);
                return new Extraction(
                        extracted.title.isEmpty() ? "Imported HTML" : extracted.title,
                        extracted.text,
                        new java.util.ArrayList<String>(extracted.warnings));
            }
        });
        registry.put(SourceImportService.Kind.TEXT, new SourceExtractor() {
            public String id() {
                return "text-passthrough-v1";
            }

            public Extraction extract(String rawContent, String originUri) {
                String text = rawContent == null ? "" : rawContent.trim();
                return new Extraction(firstLineOf(text, "Imported text"), text,
                        new java.util.ArrayList<String>());
            }
        });
        return Collections.unmodifiableMap(registry);
    }

    private static String firstLineOf(String text, String fallback) {
        for (String line : text.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 80 ? trimmed.substring(0, 77) + "..." : trimmed;
            }
        }
        return fallback;
    }
}
