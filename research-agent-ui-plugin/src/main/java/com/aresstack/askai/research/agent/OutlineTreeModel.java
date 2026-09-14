package com.aresstack.askai.research.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #43 slice 8 — the PURE projection behind the graphical Outline view: parse the persisted
 * outline markdown into a flat list of numbered chapter rows ({@code 1}, {@code 1.1},
 * {@code 1.1.1}) plus unnumbered annotation rows (bullets like open questions). Deliberately
 * a PROJECTION layer only: it renders whatever outline structure exists today (the live
 * projection's {@code ##} sections) and deeper {@code ###}/{@code ####} levels the #40
 * synthesis will produce later — no outline semantics, no staleness logic, no persistence.
 *
 * <p>Concept and Outline stay DIFFERENT artifacts: the concept tree shows the user's topic
 * hierarchy, this model shows the DOCUMENT's presentation structure with chapter numbers as
 * part of its visual identity.</p>
 */
final class OutlineTreeModel {

    /** One rendered row: a numbered chapter or an unnumbered annotation beneath one. */
    static final class Row {
        /** Chapter number like "1.2.1"; empty for annotation rows. */
        final String number;
        final String title;
        /** 0 = chapter, 1 = subchapter, … annotations sit one deeper than their chapter. */
        final int depth;
        final boolean annotation;

        Row(String number, String title, int depth, boolean annotation) {
            this.number = number;
            this.title = title;
            this.depth = depth;
            this.annotation = annotation;
        }
    }

    private OutlineTreeModel() {
    }

    /**
     * Parse outline markdown into rows, in DOCUMENT ORDER (stable): {@code ##}=chapter,
     * {@code ###}=subchapter, {@code ####}=sub-subchapter; {@code - } bullets become
     * annotation rows under the current heading. The document title ({@code # …}) and the
     * italic {@code _…_} meta lines are presentation noise and are skipped.
     */
    static List<Row> parse(String markdown) {
        List<Row> rows = new ArrayList<Row>();
        if (markdown == null || markdown.trim().isEmpty()) {
            return Collections.emptyList();
        }
        int[] counters = new int[3]; // chapter / sub / subsub
        int currentDepth = -1;
        for (String raw : markdown.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("# ")
                    || (line.startsWith("_") && line.endsWith("_"))) {
                continue; // doc title + italic meta lines are not outline structure
            }
            int level = headingLevel(line);
            if (level >= 0) {
                String title = line.substring(level + 3).trim(); // "## " etc.
                if (title.isEmpty()) {
                    title = "(untitled)";
                }
                counters[level]++;
                for (int deeper = level + 1; deeper < counters.length; deeper++) {
                    counters[deeper] = 0;
                }
                StringBuilder number = new StringBuilder();
                for (int part = 0; part <= level; part++) {
                    if (part > 0) {
                        number.append('.');
                    }
                    number.append(Math.max(1, counters[part]));
                }
                rows.add(new Row(number.toString(), title, level, false));
                currentDepth = level;
                continue;
            }
            if ((line.startsWith("- ") || line.startsWith("* ")) && currentDepth >= 0) {
                rows.add(new Row("", line.substring(2).trim(), currentDepth + 1, true));
            }
        }
        return rows;
    }

    /** 0 for {@code ## }, 1 for {@code ### }, 2 for {@code #### }; -1 otherwise. */
    private static int headingLevel(String line) {
        if (line.startsWith("## ") && !line.startsWith("###")) {
            return 0;
        }
        if (line.startsWith("### ") && !line.startsWith("####")) {
            return 1;
        }
        if (line.startsWith("#### ")) {
            return 2;
        }
        return -1;
    }
}
