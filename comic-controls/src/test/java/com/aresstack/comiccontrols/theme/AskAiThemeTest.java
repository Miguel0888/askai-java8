package com.aresstack.comiccontrols.theme;

import org.junit.Test;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * Pins the theme token table: the values are taken from the spec tables
 * (docs/theme-system-spec-v2.md, section 3; classic = the historic code values) as test data —
 * never copied from the implementation.
 */
public class AskAiThemeTest {

    @Test
    public void resolveSelectsByTrimmedCaseInsensitiveNameAndFallsBackToTheDefault() {
        assertEquals("classic", AskAiTheme.resolve("classic").name());
        assertEquals("arcane", AskAiTheme.resolve("arcane").name());
        assertEquals("arcane", AskAiTheme.resolve(" ARCANE ").name());
        assertEquals("arcane", AskAiTheme.resolve(null).name());
        assertEquals("arcane", AskAiTheme.resolve("garbage").name());
        assertEquals("arcane", AskAiTheme.resolve("").name());
    }

    @Test
    public void defaultThemeIsArcane() {
        assertEquals("arcane", AskAiTheme.DEFAULT_THEME);
    }

    @Test
    public void setActiveIsTheOneSource() {
        AskAiTheme before = AskAiTheme.active();
        try {
            AskAiTheme classic = AskAiTheme.classic();
            AskAiTheme.setActive(classic);
            assertSame(classic, AskAiTheme.active());
            AskAiTheme arcane = AskAiTheme.arcane();
            AskAiTheme.setActive(arcane);
            assertSame(arcane, AskAiTheme.active());
        } finally {
            AskAiTheme.setActive(before);
        }
        assertNotNull(AskAiTheme.active());
    }

    @Test
    public void changeListenersFireOnlyOnFireChanged() {
        final int[] calls = {0};
        Runnable listener = new Runnable() {
            @Override
            public void run() {
                calls[0]++;
            }
        };
        AskAiTheme before = AskAiTheme.active();
        AskAiTheme.addChangeListener(listener);
        AskAiTheme.addChangeListener(listener); // duplicate ignored
        try {
            AskAiTheme.setActive(AskAiTheme.classic());
            assertEquals("setActive is silent", 0, calls[0]);
            AskAiTheme.fireChanged();
            assertEquals(1, calls[0]);
        } finally {
            AskAiTheme.removeChangeListener(listener);
            AskAiTheme.setActive(before);
        }
        AskAiTheme.fireChanged();
        assertEquals("removed", 1, calls[0]);
    }

    @Test
    public void classicTokensArePinned() {
        Map<String, Integer> expected = classicTable();
        AskAiTheme t = AskAiTheme.classic();
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            assertEquals(e.getKey(), new Color(e.getValue()), t.color(e.getKey()));
        }
        assertEquals(new Color(0, 0, 0, 110), t.overlayDim());
    }

    @Test
    public void classicSkyIsSingleToned() {
        AskAiTheme t = AskAiTheme.classic();
        assertEquals(t.research("SKY_TOP"), t.research("SKY_MID"));
    }

    @Test
    public void arcaneV2TokensArePinned() {
        Map<String, Integer> v2 = new LinkedHashMap<String, Integer>();
        v2.put("comic.surface", 0xEFE4CF);
        v2.put("comic.ink", 0x24304A);
        v2.put("comic.navigationBlue", 0x2D4F86);
        v2.put("comic.agentPetrol", 0x2A6F6B);
        v2.put("comic.accentYellow", 0xE9B949);
        v2.put("comic.accentOrange", 0xD08A2C);
        v2.put("comic.accentRed", 0xC94C4C);
        v2.put("comic.buttonPlate", 0xFBF6EA);
        v2.put("comic.warmFocus", 0xFFF3D6);
        v2.put("comic.fieldBg", 0xFFFCF4);
        v2.put("comic.fieldBgQuiet", 0xF8F1E2);
        v2.put("comic.fieldBorder", 0xCDBE9E);
        v2.put("comic.hintText", 0x857A63);
        v2.put("comic.hintTextSoft", 0xA89C82);
        v2.put("research.SKY_TOP", 0xE4572E);
        v2.put("research.SKY_MID", 0xF4A259);
        v2.put("research.SKY_BAR_SURFACE", 0xF3B07A);
        v2.put("research.SKY_CAPTION", 0x7A3B22);
        v2.put("research.CLOUD_SURFACE", 0xFFF6EC);
        v2.put("research.CLOUD_BORDER", 0xEDB48A);
        v2.put("research.CLOUD_TEXT", 0x5A2E1C);
        v2.put("research.CLOUD_HOVER_SURFACE", 0xFFEBD6);
        v2.put("research.CLOUD_HOVER_BORDER", 0xE08F5C);
        v2.put("research.CLOUD_CLOSE", 0x9A5A3C);
        v2.put("research.LIGHT_CONTROL_BG", 0xFFFCF4);
        v2.put("research.LIGHT_CONTROL_BORDER", 0xCDBE9E);
        // dark side kept from v1
        v2.put("research.DARK_BASE", 0x0B1220);
        v2.put("research.PURPLE_PRIMARY", 0xC08A2E);
        v2.put("research.ACCENT_BLUE", 0xDAA648);
        v2.put("bubble.transcriptBackground", 0xF6EEDD);
        v2.put("bubble.userBackground", 0x24406E);
        v2.put("bubble.userForeground", 0xFFF8EA);
        v2.put("bubble.assistantBackground", 0x2A6F6B);
        v2.put("bubble.assistantForeground", 0xFFF8EA);
        v2.put("bubble.activityBackground", 0xE9B949);
        v2.put("bubble.activityForeground", 0x24304A);
        v2.put("bubble.activityAccent", 0xB9771F);
        v2.put("bubble.failureAccent", 0xC94C4C);
        v2.put("bubble.infoForeground", 0x7A7262);
        AskAiTheme t = AskAiTheme.arcane();
        for (Map.Entry<String, Integer> e : v2.entrySet()) {
            assertEquals(e.getKey(), new Color(e.getValue()), t.color(e.getKey()));
        }
        assertEquals(new Color(11, 18, 32, 120), t.overlayDim());
    }

    @Test
    public void bothThemesHaveTheSameKeysAndKeepFunctionColors() {
        AskAiTheme classic = AskAiTheme.classic();
        AskAiTheme arcane = AskAiTheme.arcane();
        assertEquals(classic.tokens().keySet(), arcane.tokens().keySet());
        for (String key : new String[]{"comic.accentRed", "research.DANGER_RED", "research.TEXT_PRIMARY",
                "research.TEXT_MUTED", "research.TEXT_DARK", "bubble.failureAccent"}) {
            assertEquals(key, classic.color(key), arcane.color(key));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownTokenIsRejected() {
        AskAiTheme.classic().color("comic.nope");
    }

    /** The historic values (v1 pins + bubble group = BubblePalette.windowsPhoneInspired()). */
    static Map<String, Integer> classicTable() {
        Map<String, Integer> e = new LinkedHashMap<String, Integer>();
        e.put("comic.surface", 0xF4F4F4);
        e.put("comic.ink", 0x252525);
        e.put("comic.navigationBlue", 0x1676D2);
        e.put("comic.agentPetrol", 0x15827A);
        e.put("comic.accentYellow", 0xF2C94C);
        e.put("comic.accentOrange", 0xE39A18);
        e.put("comic.accentRed", 0xC94C4C);
        e.put("comic.buttonPlate", 0xFFFFFF);
        e.put("comic.warmFocus", 0xFFF8E1);
        e.put("comic.fieldBg", 0xFFFFFF);
        e.put("comic.fieldBgQuiet", 0xFAFAFA);
        e.put("comic.fieldBorder", 0xC6C6C6);
        e.put("comic.hintText", 0x888888);
        e.put("comic.hintTextSoft", 0xAAAAAA);
        e.put("comic.placeholderText", 0x6B6F76);
        e.put("research.DARK_BASE", 0x0C1117);
        e.put("research.DARK_SURFACE", 0x12181F);
        e.put("research.WORKSPACE_SURFACE", 0x1B2028);
        e.put("research.CHIP_SURFACE", 0x0E1218);
        e.put("research.SECONDARY_SURFACE", 0x141A21);
        e.put("research.SECONDARY_HOVER", 0x202832);
        e.put("research.TEXT_PRIMARY", 0xE8EBF2);
        e.put("research.TEXT_MUTED", 0xADB5C7);
        e.put("research.TEXT_DARK", 0x171C29);
        e.put("research.PURPLE_PRIMARY", 0x5E3BA3);
        e.put("research.PURPLE_ACTION", 0x754DB8);
        e.put("research.PURPLE_HOVER", 0x6943AD);
        e.put("research.BORDER_DARK", 0x5E6370);
        e.put("research.BORDER_WINDOW", 0x38404D);
        e.put("research.ACCENT_BLUE", 0x2979FF);
        e.put("research.DANGER_RED", 0xD32F2F);
        e.put("research.LIGHT_CONTROL_BG", 0xFFFFFF);
        e.put("research.LIGHT_CONTROL_BORDER", 0xC9CED6);
        e.put("research.LIGHT_CONTROL_TEXT", 0x44484D);
        e.put("research.LIGHT_TEXT_MUTED", 0x8A909B);
        e.put("research.CHIP_HOVER_SURFACE", 0x151B23);
        e.put("research.CHIP_HOVER_BORDER", 0x767D89);
        e.put("research.CHIP_CLOSE_HOVER", 0x2A3039);
        e.put("research.WORKSPACE_DIVIDER", 0x282E38);
        e.put("research.SKY_TOP", 0xF5FAFE);
        e.put("research.SKY_BAR_SURFACE", 0xBFDCF2);
        e.put("research.SKY_CAPTION", 0x6E87A2);
        e.put("research.CLOUD_SURFACE", 0xF7FBFF);
        e.put("research.CLOUD_BORDER", 0xAFCDE8);
        e.put("research.CLOUD_TEXT", 0x40556B);
        e.put("research.CLOUD_HOVER_SURFACE", 0xEAF4FC);
        e.put("research.CLOUD_HOVER_BORDER", 0x8FB8DE);
        e.put("research.CLOUD_CLOSE", 0x72879A);
        e.put("research.SKY_MID", 0xF5FAFE);
        e.put("bubble.transcriptBackground", 0xF4F4F4);
        e.put("bubble.userBackground", 0x1676D2);
        e.put("bubble.userForeground", 0xFFFFFF);
        e.put("bubble.assistantBackground", 0x15827A);
        e.put("bubble.assistantForeground", 0xFFFFFF);
        e.put("bubble.activityBackground", 0xF2C94C);
        e.put("bubble.activityForeground", 0x252525);
        e.put("bubble.activityAccent", 0xE39A18);
        e.put("bubble.failureAccent", 0xC94C4C);
        e.put("bubble.infoForeground", 0x6B6F76);
        return e;
    }
}
