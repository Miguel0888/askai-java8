package com.aresstack.comiccontrols.theme;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Guardrail 6.7: every text/surface pair of both themes reaches WCAG 4.5:1. */
public class ThemeContrastTest {

    private static final double WCAG_AA = 4.5;

    private static final String[][] PAIRS = {
            {"comic.ink", "comic.surface"},
            {"comic.ink", "comic.fieldBg"},
            {"bubble.userForeground", "bubble.userBackground"},
            {"bubble.assistantForeground", "bubble.assistantBackground"},
            {"bubble.activityForeground", "bubble.activityBackground"},
            {"research.CLOUD_TEXT", "research.CLOUD_SURFACE"},
            {"research.TEXT_PRIMARY", "research.DARK_BASE"},
    };

    @Test
    public void classicReadable() {
        assertReadable(AskAiTheme.classic());
    }

    @Test
    public void arcaneReadable() {
        assertReadable(AskAiTheme.arcane());
    }

    private static void assertReadable(AskAiTheme theme) {
        for (String[] pair : PAIRS) {
            double ratio = ColorContrast.ratio(theme.color(pair[0]), theme.color(pair[1]));
            assertTrue(theme.name() + ": " + pair[0] + " on " + pair[1] + " = " + ratio, ratio >= WCAG_AA);
        }
    }
}
