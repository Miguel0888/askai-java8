package com.aresstack.askai.research.capture;

/**
 * Extraction port + the three MVP extractors (HTML remnants / plain text / markdown), kept together for
 * compactness. Heavy HTML cleanup already happens in the browser backends (jsoup lives there); the plugin-side
 * HTML extractor only strips residual tags/scripts from capture text — pure Java, no Tika, no new plugin deps.
 */
public interface DocumentExtractor {

    boolean supports(DocumentInput input);

    ExtractedDocument extract(DocumentInput input);

    // ------------------------------------------------------------------ value types

    final class DocumentInput {
        private final String contentType; // e.g. "text/html", "text/markdown", "text/plain"
        private final String filename;
        private final String text;

        public DocumentInput(String contentType, String filename, String text) {
            this.contentType = contentType == null ? "" : contentType;
            this.filename = filename == null ? "" : filename;
            this.text = text == null ? "" : text;
        }

        public String getContentType() { return contentType; }
        public String getFilename() { return filename; }
        public String getText() { return text; }
    }

    final class ExtractedDocument {
        private final String title;
        private final String text;
        private final int passageCount;

        public ExtractedDocument(String title, String text, int passageCount) {
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.passageCount = passageCount;
        }

        public String getTitle() { return title; }
        public String getText() { return text; }
        public int getPassageCount() { return passageCount; }
    }

    // ------------------------------------------------------------------ MVP extractors

    /** Strips residual HTML (scripts/styles/tags), keeps heading text; counts paragraphs as passages. */
    final class Html implements DocumentExtractor {
        public boolean supports(DocumentInput input) {
            return input.getContentType().contains("html")
                    || input.getText().toLowerCase().contains("<html");
        }

        public ExtractedDocument extract(DocumentInput input) {
            String s = input.getText();
            s = s.replaceAll("(?is)<script.*?</script>", " ")
                 .replaceAll("(?is)<style.*?</style>", " ");
            String title = "";
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?is)<title>(.*?)</title>").matcher(s);
            if (m.find()) {
                title = m.group(1).trim();
            }
            // Headings survive as text lines; paragraphs/headings count as passages.
            int passages = countMatches(s, "(?is)<(p|h[1-6])[ >]");
            String text = s.replaceAll("(?is)<br ?/?>", "\n")
                           .replaceAll("(?is)</(p|h[1-6]|li|div)>", "\n")
                           .replaceAll("(?s)<[^>]+>", " ")
                           .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                           .replaceAll(" ?\\n ?", "\n").trim();
            return new ExtractedDocument(title, text, Math.max(passages, text.isEmpty() ? 0 : 1));
        }
    }

    /** Markdown: kept verbatim (already readable); headings/paragraph blocks count as passages. */
    final class Markdown implements DocumentExtractor {
        public boolean supports(DocumentInput input) {
            return input.getContentType().contains("markdown") || input.getFilename().endsWith(".md");
        }

        public ExtractedDocument extract(DocumentInput input) {
            String text = input.getText().trim();
            String title = "";
            for (String line : text.split("\n")) {
                if (line.startsWith("# ")) {
                    title = line.substring(2).trim();
                    break;
                }
            }
            int passages = text.isEmpty() ? 0 : text.split("\\n\\s*\\n").length;
            return new ExtractedDocument(title, text, passages);
        }
    }

    /** Plain text fallback: verbatim, blank-line separated blocks count as passages. */
    final class Text implements DocumentExtractor {
        public boolean supports(DocumentInput input) {
            return true; // fallback
        }

        public ExtractedDocument extract(DocumentInput input) {
            String text = input.getText().trim();
            String title = text.isEmpty() ? "" : text.split("\n", 2)[0].trim();
            int passages = text.isEmpty() ? 0 : text.split("\\n\\s*\\n").length;
            return new ExtractedDocument(title, text, passages);
        }
    }

    /** Ordered chain: html → markdown → text (text always matches). */
    final class Chain {
        private static final DocumentExtractor[] EXTRACTORS =
                {new Html(), new Markdown(), new Text()};

        private Chain() {
        }

        public static ExtractedDocument extract(DocumentInput input) {
            for (DocumentExtractor extractor : EXTRACTORS) {
                if (extractor.supports(input)) {
                    return extractor.extract(input);
                }
            }
            return new Text().extract(input);
        }
    }

    static int countMatches(String s, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
