package com.aresstack.askai.research.capture;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #39 slice 4 — MECHANICAL, structure-preserving HTML extraction for user imports (jsoup):
 * scripts, styles, templates and navigation chrome never become evidence text; headings,
 * paragraphs, list items and table cells keep their line structure so the passage
 * segmentation sees real document shape instead of one flattened blob. Deterministic, no
 * model involved; oddities surface as WARNINGS, never as silent drops.
 */
final class HtmlSourceExtraction {

    static final String EXTRACTOR_ID = "jsoup-structural-v1";

    /** The extraction result: title, structured text, warnings. */
    static final class Extracted {
        final String title;
        final String text;
        final List<String> warnings;

        Extracted(String title, String text, List<String> warnings) {
            this.title = title;
            this.text = text;
            this.warnings = Collections.unmodifiableList(warnings);
        }
    }

    private HtmlSourceExtraction() {
    }

    static Extracted extract(String rawHtml, String baseUri) {
        List<String> warnings = new ArrayList<String>();
        if (rawHtml == null || rawHtml.trim().isEmpty()) {
            return new Extracted("", "", warnings);
        }
        Document document;
        try {
            document = baseUri == null || baseUri.trim().isEmpty()
                    ? Jsoup.parse(rawHtml) : Jsoup.parse(rawHtml, baseUri.trim());
        } catch (RuntimeException unparseable) {
            warnings.add("HTML could not be parsed; imported as plain text");
            return new Extracted("", rawHtml.trim(), warnings);
        }
        // Chrome and machinery are never evidence.
        int removed = document.select(
                "script, style, noscript, template, iframe, svg, nav, header > nav, footer")
                .size();
        document.select(
                "script, style, noscript, template, iframe, svg, nav, header > nav, footer")
                .remove();
        if (removed > 0) {
            warnings.add(removed + " non-content element(s) removed (script/style/nav/...)");
        }

        StringBuilder text = new StringBuilder();
        for (Element element : document.select(
                "h1, h2, h3, h4, h5, h6, p, li, td, th, pre, blockquote, figcaption")) {
            String line = element.text().trim();
            if (line.isEmpty()) {
                continue;
            }
            if (element.normalName().startsWith("h")) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(line).append('\n');
            } else {
                text.append(line).append('\n');
            }
        }
        String structured = text.toString().trim();
        if (structured.isEmpty()) {
            // A page without block elements: fall back to the body text, stated openly.
            structured = document.body() == null ? "" : document.body().text().trim();
            if (!structured.isEmpty()) {
                warnings.add("no block structure found; imported the flattened body text");
            }
        }
        String title = document.title() == null ? "" : document.title().trim();
        if (title.isEmpty()) {
            Element h1 = document.selectFirst("h1");
            title = h1 == null ? "" : h1.text().trim();
        }
        return new Extracted(title, structured, warnings);
    }
}
