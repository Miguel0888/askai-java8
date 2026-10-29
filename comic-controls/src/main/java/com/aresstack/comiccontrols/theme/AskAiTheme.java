package com.aresstack.comiccontrols.theme;

import java.awt.Color;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * THE semantic color token table of AskAI — the one place every surface takes its colors from.
 * Every token has a key of the form {@code group.role} ({@code comic.surface},
 * {@code research.DARK_BASE}, …) and is readable via {@link #color(String)}. The typed accessors
 * ({@link #comicPalette()}, {@link #buttonPlate()}, {@link #research(String)}, …) are views of the
 * same table.
 *
 * <p>Two built-in themes exist: {@link #classic()} (the historic values, pinned byte-identical)
 * and {@link #arcane()} (navy + gold, the "Arcane Knowledge" brand). The active theme is chosen
 * once per JVM through the start property {@value #PROPERTY} — a development/A-B switch, not a
 * user setting.</p>
 */
public final class AskAiTheme {

    /** JVM start property selecting the theme ({@code classic} | {@code arcane}). */
    public static final String PROPERTY = "askai.theme";

    public static final String CLASSIC = "classic";
    public static final String ARCANE = "arcane";

    /** The theme used when the property is missing or unknown. */
    static final String DEFAULT_THEME = ARCANE;

    /** Key prefix of the research group; the rest of the key is the ResearchUiPalette constant name. */
    static final String RESEARCH_PREFIX = "research.";

    private static volatile AskAiTheme active;

    private final String name;
    private final Map<String, Color> tokens;
    private final ComicPalette comicPalette;

    private AskAiTheme(String name, Map<String, Color> tokens) {
        this.name = name;
        this.tokens = Collections.unmodifiableMap(new LinkedHashMap<String, Color>(tokens));
        this.comicPalette = new ComicPalette(color("comic.surface"), color("comic.ink"),
                color("comic.navigationBlue"), color("comic.agentPetrol"),
                color("comic.accentYellow"), color("comic.accentOrange"), color("comic.accentRed"));
    }

    /** The historic AskAI look — every value identical to the pre-theme hardcoded colors. */
    public static AskAiTheme classic() {
        Map<String, Color> t = new LinkedHashMap<String, Color>();
        put(t, "comic.surface", 0xF4F4F4);
        put(t, "comic.ink", 0x252525);
        put(t, "comic.navigationBlue", 0x1676D2);
        put(t, "comic.agentPetrol", 0x15827A);
        put(t, "comic.accentYellow", 0xF2C94C);
        put(t, "comic.accentOrange", 0xE39A18);
        put(t, "comic.accentRed", 0xC94C4C);
        put(t, "comic.buttonPlate", 0xFFFFFF);
        put(t, "comic.warmFocus", 0xFFF8E1);
        put(t, "comic.fieldBg", 0xFFFFFF);
        put(t, "comic.fieldBgQuiet", 0xFAFAFA);
        put(t, "comic.fieldBorder", 0xC6C6C6);
        put(t, "comic.hintText", 0x888888);
        put(t, "comic.hintTextSoft", 0xAAAAAA);
        t.put("comic.overlayDim", new Color(0, 0, 0, 110));
        put(t, "comic.placeholderText", 0x6B6F76);

        put(t, "research.DARK_BASE", 0x0C1117);
        put(t, "research.DARK_SURFACE", 0x12181F);
        put(t, "research.WORKSPACE_SURFACE", 0x1B2028);
        put(t, "research.CHIP_SURFACE", 0x0E1218);
        put(t, "research.SECONDARY_SURFACE", 0x141A21);
        put(t, "research.SECONDARY_HOVER", 0x202832);
        put(t, "research.TEXT_PRIMARY", 0xE8EBF2);
        put(t, "research.TEXT_MUTED", 0xADB5C7);
        put(t, "research.TEXT_DARK", 0x171C29);
        put(t, "research.PURPLE_PRIMARY", 0x5E3BA3);
        put(t, "research.PURPLE_ACTION", 0x754DB8);
        put(t, "research.PURPLE_HOVER", 0x6943AD);
        put(t, "research.BORDER_DARK", 0x5E6370);
        put(t, "research.BORDER_WINDOW", 0x38404D);
        put(t, "research.ACCENT_BLUE", 0x2979FF);
        put(t, "research.DANGER_RED", 0xD32F2F);
        put(t, "research.LIGHT_CONTROL_BG", 0xFFFFFF);
        put(t, "research.LIGHT_CONTROL_BORDER", 0xC9CED6);
        put(t, "research.LIGHT_CONTROL_TEXT", 0x44484D);
        put(t, "research.LIGHT_TEXT_MUTED", 0x8A909B);
        put(t, "research.CHIP_HOVER_SURFACE", 0x151B23);
        put(t, "research.CHIP_HOVER_BORDER", 0x767D89);
        put(t, "research.CHIP_CLOSE_HOVER", 0x2A3039);
        put(t, "research.WORKSPACE_DIVIDER", 0x282E38);
        put(t, "research.SKY_TOP", 0xF5FAFE);
        put(t, "research.SKY_BAR_SURFACE", 0xBFDCF2);
        put(t, "research.SKY_CAPTION", 0x6E87A2);
        put(t, "research.CLOUD_SURFACE", 0xF7FBFF);
        put(t, "research.CLOUD_BORDER", 0xAFCDE8);
        put(t, "research.CLOUD_TEXT", 0x40556B);
        put(t, "research.CLOUD_HOVER_SURFACE", 0xEAF4FC);
        put(t, "research.CLOUD_HOVER_BORDER", 0x8FB8DE);
        put(t, "research.CLOUD_CLOSE", 0x72879A);
        return new AskAiTheme(CLASSIC, t);
    }

    /** "Arcane Knowledge": navy + gold, derived from the AskAI logo. Function reds stay untouched. */
    public static AskAiTheme arcane() {
        Map<String, Color> t = new LinkedHashMap<String, Color>(classic().tokens);
        put(t, "comic.surface", 0xF7F5F0);
        put(t, "comic.ink", 0x253048);
        put(t, "comic.navigationBlue", 0x315B91);
        put(t, "comic.agentPetrol", 0x287D79);
        put(t, "comic.accentYellow", 0xE6B650);
        put(t, "comic.accentOrange", 0xC99232);
        put(t, "comic.buttonPlate", 0xFFFDF8);
        put(t, "comic.warmFocus", 0xF8EFD8);
        put(t, "comic.fieldBg", 0xFFFDF8);
        put(t, "comic.fieldBgQuiet", 0xFAF7F0);
        put(t, "comic.fieldBorder", 0xCBC4B4);
        put(t, "comic.hintText", 0x8F897B);
        put(t, "comic.hintTextSoft", 0xA9A295);
        put(t, "comic.placeholderText", 0x7A7262);

        put(t, "research.DARK_BASE", 0x0B1220);
        put(t, "research.DARK_SURFACE", 0x111A28);
        put(t, "research.WORKSPACE_SURFACE", 0x192338);
        put(t, "research.CHIP_SURFACE", 0x0D1524);
        put(t, "research.SECONDARY_SURFACE", 0x141E30);
        put(t, "research.SECONDARY_HOVER", 0x1E2A44);
        put(t, "research.PURPLE_PRIMARY", 0xC08A2E);
        put(t, "research.PURPLE_ACTION", 0xDAA648);
        put(t, "research.PURPLE_HOVER", 0xCE9838);
        put(t, "research.BORDER_DARK", 0x645F55);
        put(t, "research.BORDER_WINDOW", 0x3A3F52);
        put(t, "research.ACCENT_BLUE", 0xDAA648);
        put(t, "research.LIGHT_CONTROL_BG", 0xFFFDF8);
        put(t, "research.LIGHT_CONTROL_BORDER", 0xCFC8B8);
        put(t, "research.LIGHT_CONTROL_TEXT", 0x474439);
        put(t, "research.LIGHT_TEXT_MUTED", 0x8E897C);
        put(t, "research.CHIP_HOVER_SURFACE", 0x161F33);
        put(t, "research.CHIP_HOVER_BORDER", 0x7D7664);
        put(t, "research.CHIP_CLOSE_HOVER", 0x2C3347);
        put(t, "research.WORKSPACE_DIVIDER", 0x2A3144);
        put(t, "research.SKY_TOP", 0xFAF6EC);
        put(t, "research.SKY_BAR_SURFACE", 0xE8D9B0);
        put(t, "research.SKY_CAPTION", 0x8A7A55);
        put(t, "research.CLOUD_SURFACE", 0xFDFAF2);
        put(t, "research.CLOUD_BORDER", 0xD9C690);
        put(t, "research.CLOUD_TEXT", 0x5C5238);
        put(t, "research.CLOUD_HOVER_SURFACE", 0xF7F0DD);
        put(t, "research.CLOUD_HOVER_BORDER", 0xC8B274);
        put(t, "research.CLOUD_CLOSE", 0x8A8064);
        return new AskAiTheme(ARCANE, t);
    }

    /**
     * Pure theme resolution: trims and lower-cases the value; {@code classic}/{@code arcane}
     * select that theme, anything else (missing, empty, unknown) yields the default theme.
     */
    public static AskAiTheme resolve(String value) {
        String key = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (CLASSIC.equals(key)) {
            return classic();
        }
        if (ARCANE.equals(key)) {
            return arcane();
        }
        return ARCANE.equals(DEFAULT_THEME) ? arcane() : classic();
    }

    /** The theme of this JVM: resolved once from {@value #PROPERTY}, then cached. */
    public static AskAiTheme active() {
        AskAiTheme theme = active;
        if (theme == null) {
            synchronized (AskAiTheme.class) {
                theme = active;
                if (theme == null) {
                    theme = resolve(System.getProperty(PROPERTY));
                    active = theme;
                }
            }
        }
        return theme;
    }

    public String name() {
        return name;
    }

    /** Any token by its full key ({@code group.role}); unknown keys are rejected. */
    public Color color(String key) {
        Color color = tokens.get(key);
        if (color == null) {
            throw new IllegalArgumentException("unknown theme token: " + key);
        }
        return color;
    }

    /** Whether the table has a token with this key. */
    public boolean hasToken(String key) {
        return tokens.containsKey(key);
    }

    /** All tokens (key → color), in table order. */
    public Map<String, Color> tokens() {
        return tokens;
    }

    public ComicPalette comicPalette() {
        return comicPalette;
    }

    /** Resting fill of comic button plates (and other lifted light chips). */
    public Color buttonPlate() {
        return color("comic.buttonPlate");
    }

    /** The warm cream focus surface of the amber family (find bar, search tag). */
    public Color warmFocus() {
        return color("comic.warmFocus");
    }

    /** Light input field background. */
    public Color fieldBg() {
        return color("comic.fieldBg");
    }

    /** The quieter field background of the find bar at rest. */
    public Color fieldBgQuiet() {
        return color("comic.fieldBgQuiet");
    }

    /** The one neutral field contour. */
    public Color fieldBorder() {
        return color("comic.fieldBorder");
    }

    /** Icon / hint grey. */
    public Color hintText() {
        return color("comic.hintText");
    }

    /** Softer placeholder / inactive grey. */
    public Color hintTextSoft() {
        return color("comic.hintTextSoft");
    }

    /** Translucent backdrop behind overlays. */
    public Color overlayDim() {
        return color("comic.overlayDim");
    }

    /** Muted but readable placeholder text on light plates (about 5:1). */
    public Color placeholderText() {
        return color("comic.placeholderText");
    }

    /** A research-side token, keyed by its {@link ResearchUiPalette} constant name. */
    public Color research(String constantName) {
        Color color = tokens.get(RESEARCH_PREFIX + constantName);
        if (color == null) {
            throw new IllegalArgumentException("unknown research token: " + constantName);
        }
        return color;
    }

    /** All research-side tokens (constant name → color), in table order. */
    public Map<String, Color> researchTokens() {
        Map<String, Color> research = new LinkedHashMap<String, Color>();
        for (Map.Entry<String, Color> e : tokens.entrySet()) {
            if (e.getKey().startsWith(RESEARCH_PREFIX)) {
                research.put(e.getKey().substring(RESEARCH_PREFIX.length()), e.getValue());
            }
        }
        return Collections.unmodifiableMap(research);
    }

    private static void put(Map<String, Color> tokens, String key, int rgb) {
        tokens.put(key, new Color(rgb));
    }
}
