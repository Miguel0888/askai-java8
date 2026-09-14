package com.aresstack.askai.research.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #43 slice 9 — the PURE model behind the Document page view (UI spike ahead of #41): parse
 * the ONE canonical document markdown into presentation blocks (headings + paragraphs) and
 * surface the inline source markers {@code [1]}, {@code [2]} … as TYPED references instead of
 * invisible text. This is deliberately a projection contract only — how the numbers map to
 * sources is the {@code ReferenceResolver}'s business, which #41's citation registry will
 * implement properly; the model just finds and types the markers.
 */
public final class DocumentPageModel {

    public enum Kind { HEADING_1, HEADING_2, HEADING_3, PARAGRAPH }

    /** One rendered block; {@code references} = the marker numbers appearing in its text. */
    public static final class Block {
        public final Kind kind;
        public final String text;
        public final List<Integer> references;

        Block(Kind kind, String text, List<Integer> references) {
            this.kind = kind;
            this.text = text;
            this.references = Collections.unmodifiableList(references);
        }
    }

    private static final Pattern REFERENCE = Pattern.compile("\\[(\\d{1,3})\\]");

    private DocumentPageModel() {
    }

    /** Parse the document markdown into blocks, in document order. Blank input → no blocks. */
    public static List<Block> parse(String markdown) {
        List<Block> blocks = new ArrayList<Block>();
        if (markdown == null || markdown.trim().isEmpty()) {
            return blocks;
        }
        StringBuilder paragraph = new StringBuilder();
        for (String raw : markdown.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                flushParagraph(paragraph, blocks);
                continue;
            }
            Kind heading = headingKind(line);
            if (heading != null) {
                flushParagraph(paragraph, blocks);
                String text = line.replaceFirst("^#+\\s*", "").trim();
                blocks.add(new Block(heading, text, referencesIn(text)));
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(line);
        }
        flushParagraph(paragraph, blocks);
        return blocks;
    }

    private static void flushParagraph(StringBuilder paragraph, List<Block> blocks) {
        if (paragraph.length() == 0) {
            return;
        }
        String text = paragraph.toString();
        blocks.add(new Block(Kind.PARAGRAPH, text, referencesIn(text)));
        paragraph.setLength(0);
    }

    private static Kind headingKind(String line) {
        if (line.startsWith("# ")) {
            return Kind.HEADING_1;
        }
        if (line.startsWith("## ")) {
            return Kind.HEADING_2;
        }
        if (line.startsWith("### ") || line.startsWith("#### ")) {
            return Kind.HEADING_3;
        }
        return null;
    }

    /** The distinct {@code [n]} marker numbers of one text, in first-appearance order. */
    static List<Integer> referencesIn(String text) {
        List<Integer> references = new ArrayList<Integer>();
        Matcher matcher = REFERENCE.matcher(text);
        while (matcher.find()) {
            Integer number = Integer.valueOf(matcher.group(1));
            if (!references.contains(number)) {
                references.add(number);
            }
        }
        return references;
    }
}
