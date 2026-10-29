package com.aresstack.comiccontrols.theme;

import org.junit.Test;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Pins the theme token table: the classic values are taken from the spec table
 * (docs/theme-system-spec.md, section 3) as test data — never copied from the implementation.
 */
public class AskAiThemeTest {

    @Test
    public void resolveSelectsByTrimmedCaseInsensitiveNameAndFallsBackToTheDefault() {
        assertEquals("classic", AskAiTheme.resolve("classic").name());
        assertEquals("arcane", AskAiTheme.resolve("arcane").name());
        assertEquals("arcane", AskAiTheme.resolve(" ARCANE ").name());
        assertEquals(AskAiTheme.DEFAULT_THEME, AskAiTheme.resolve(null).name());
        assertEquals(AskAiTheme.DEFAULT_THEME, AskAiTheme.resolve("garbage").name());
        assertEquals(AskAiTheme.DEFAULT_THEME, AskAiTheme.resolve("").name());
    }

    @Test
    public void defaultThemeIsArcane() {
        assertEquals("arcane", AskAiTheme.DEFAULT_THEME);
        assertEquals("arcane", AskAiTheme.resolve(null).name());
    }

    @Test
    public void classicStaysReachableByPropertyValueWithIdenticalTokens() {
        AskAiTheme viaProperty = AskAiTheme.resolve("classic");
        AskAiTheme classic = AskAiTheme.classic();
        assertEquals(classic.researchTokens(), viaProperty.researchTokens());
        assertEquals(classic.comicPalette().getSurface(), viaProperty.comicPalette().getSurface());
        assertEquals(classic.comicPalette().getInk(), viaProperty.comicPalette().getInk());
        assertEquals(classic.comicPalette().getAccentYellow(), viaProperty.comicPalette().getAccentYellow());
        assertEquals(classic.buttonPlate(), viaProperty.buttonPlate());
        assertEquals(classic.fieldBorder(), viaProperty.fieldBorder());
    }

    @Test
    public void activeIsCachedAndMatchesTheProperty() {
        AskAiTheme first = AskAiTheme.active();
        assertNotNull(first);
        assertEquals(first, AskAiTheme.active());
        assertEquals(AskAiTheme.resolve(System.getProperty(AskAiTheme.PROPERTY)).name(), first.name());
    }

    @Test
    public void classicComicTokensArePinned() {
        AskAiTheme t = AskAiTheme.classic();
        ComicPalette p = t.comicPalette();
        assertRgb(0xF4F4F4, p.getSurface());
        assertRgb(0x252525, p.getInk());
        assertRgb(0x1676D2, p.getNavigationBlue());
        assertRgb(0x15827A, p.getAgentPetrol());
        assertRgb(0xF2C94C, p.getAccentYellow());
        assertRgb(0xE39A18, p.getAccentOrange());
        assertRgb(0xC94C4C, p.getAccentRed());
        assertRgb(0xFFFFFF, t.buttonPlate());
        assertRgb(0xFFF8E1, t.warmFocus());
        assertRgb(0xFFFFFF, t.fieldBg());
        assertRgb(0xFAFAFA, t.fieldBgQuiet());
        assertRgb(0xC6C6C6, t.fieldBorder());
        assertRgb(0x888888, t.hintText());
        assertRgb(0xAAAAAA, t.hintTextSoft());
        assertEquals(new Color(0, 0, 0, 110), t.overlayDim());
    }

    @Test
    public void classicResearchTokensArePinned() {
        Map<String, Integer> expected = new LinkedHashMap<String, Integer>();
        expected.put("DARK_BASE", 0x0C1117);
        expected.put("DARK_SURFACE", 0x12181F);
        expected.put("WORKSPACE_SURFACE", 0x1B2028);
        expected.put("CHIP_SURFACE", 0x0E1218);
        expected.put("SECONDARY_SURFACE", 0x141A21);
        expected.put("SECONDARY_HOVER", 0x202832);
        expected.put("TEXT_PRIMARY", 0xE8EBF2);
        expected.put("TEXT_MUTED", 0xADB5C7);
        expected.put("TEXT_DARK", 0x171C29);
        expected.put("PURPLE_PRIMARY", 0x5E3BA3);
        expected.put("PURPLE_ACTION", 0x754DB8);
        expected.put("PURPLE_HOVER", 0x6943AD);
        expected.put("BORDER_DARK", 0x5E6370);
        expected.put("BORDER_WINDOW", 0x38404D);
        expected.put("ACCENT_BLUE", 0x2979FF);
        expected.put("DANGER_RED", 0xD32F2F);
        expected.put("LIGHT_CONTROL_BG", 0xFFFFFF);
        expected.put("LIGHT_CONTROL_BORDER", 0xC9CED6);
        expected.put("LIGHT_CONTROL_TEXT", 0x44484D);
        expected.put("LIGHT_TEXT_MUTED", 0x8A909B);
        expected.put("CHIP_HOVER_SURFACE", 0x151B23);
        expected.put("CHIP_HOVER_BORDER", 0x767D89);
        expected.put("CHIP_CLOSE_HOVER", 0x2A3039);
        expected.put("WORKSPACE_DIVIDER", 0x282E38);
        expected.put("SKY_TOP", 0xF5FAFE);
        expected.put("SKY_BAR_SURFACE", 0xBFDCF2);
        expected.put("SKY_CAPTION", 0x6E87A2);
        expected.put("CLOUD_SURFACE", 0xF7FBFF);
        expected.put("CLOUD_BORDER", 0xAFCDE8);
        expected.put("CLOUD_TEXT", 0x40556B);
        expected.put("CLOUD_HOVER_SURFACE", 0xEAF4FC);
        expected.put("CLOUD_HOVER_BORDER", 0x8FB8DE);
        expected.put("CLOUD_CLOSE", 0x72879A);

        AskAiTheme t = AskAiTheme.classic();
        assertEquals(expected.keySet(), t.researchTokens().keySet());
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            assertRgb(e.getKey(), e.getValue(), t.research(e.getKey()));
        }
    }

    @Test
    public void arcaneKeepsTheFunctionColorsAndTexts() {
        AskAiTheme classic = AskAiTheme.classic();
        AskAiTheme arcane = AskAiTheme.arcane();
        assertEquals(classic.comicPalette().getAccentRed(), arcane.comicPalette().getAccentRed());
        assertEquals(classic.researchTokens().keySet(), arcane.researchTokens().keySet());
        for (String name : new String[]{"DANGER_RED", "TEXT_PRIMARY", "TEXT_MUTED", "TEXT_DARK"}) {
            assertEquals(name, classic.research(name), arcane.research(name));
        }
    }

    @Test
    public void arcaneUsesTheGoldNavigation() {
        assertRgb(0xDAA648, AskAiTheme.arcane().research("ACCENT_BLUE"));
        assertRgb(0x253048, AskAiTheme.arcane().comicPalette().getInk());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownResearchTokenIsRejected() {
        AskAiTheme.classic().research("NOPE");
    }

    private static void assertRgb(int rgb, Color actual) {
        assertRgb("", rgb, actual);
    }

    private static void assertRgb(String label, int rgb, Color actual) {
        assertEquals(label, new Color(rgb), actual);
    }
}
