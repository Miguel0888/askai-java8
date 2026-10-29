package com.aresstack.comiccontrols.theme;

import java.awt.Color;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * THE semantic color token table of AskAI — the one place every surface takes its colors from.
 * It carries the existing structures instead of replacing them: {@link #comicPalette()} yields the
 * {@link ComicPalette} of the bright comic side, the additional comic tokens cover the controls'
 * former hardcoded greys, and {@link #research(String)} feeds the {@link ResearchUiPalette}
 * constants of the dark research side (keyed by exactly those constant names).
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

    private static volatile AskAiTheme active;

    private final String name;
    private final ComicPalette comicPalette;
    private final Color buttonPlate;
    private final Color warmFocus;
    private final Color fieldBg;
    private final Color fieldBgQuiet;
    private final Color fieldBorder;
    private final Color hintText;
    private final Color hintTextSoft;
    private final Color overlayDim;
    private final Map<String, Color> research;

    private AskAiTheme(String name, ComicPalette comicPalette,
                       Color buttonPlate, Color warmFocus, Color fieldBg, Color fieldBgQuiet,
                       Color fieldBorder, Color hintText, Color hintTextSoft, Color overlayDim,
                       Map<String, Color> research) {
        this.name = name;
        this.comicPalette = comicPalette;
        this.buttonPlate = buttonPlate;
        this.warmFocus = warmFocus;
        this.fieldBg = fieldBg;
        this.fieldBgQuiet = fieldBgQuiet;
        this.fieldBorder = fieldBorder;
        this.hintText = hintText;
        this.hintTextSoft = hintTextSoft;
        this.overlayDim = overlayDim;
        this.research = Collections.unmodifiableMap(new LinkedHashMap<String, Color>(research));
    }

    /** The historic AskAI look — every value identical to the pre-theme hardcoded colors. */
    public static AskAiTheme classic() {
        Map<String, Color> r = new LinkedHashMap<String, Color>();
        r.put("DARK_BASE", c(0x0C1117));
        r.put("DARK_SURFACE", c(0x12181F));
        r.put("WORKSPACE_SURFACE", c(0x1B2028));
        r.put("CHIP_SURFACE", c(0x0E1218));
        r.put("SECONDARY_SURFACE", c(0x141A21));
        r.put("SECONDARY_HOVER", c(0x202832));
        r.put("TEXT_PRIMARY", c(0xE8EBF2));
        r.put("TEXT_MUTED", c(0xADB5C7));
        r.put("TEXT_DARK", c(0x171C29));
        r.put("PURPLE_PRIMARY", c(0x5E3BA3));
        r.put("PURPLE_ACTION", c(0x754DB8));
        r.put("PURPLE_HOVER", c(0x6943AD));
        r.put("BORDER_DARK", c(0x5E6370));
        r.put("BORDER_WINDOW", c(0x38404D));
        r.put("ACCENT_BLUE", c(0x2979FF));
        r.put("DANGER_RED", c(0xD32F2F));
        r.put("LIGHT_CONTROL_BG", c(0xFFFFFF));
        r.put("LIGHT_CONTROL_BORDER", c(0xC9CED6));
        r.put("LIGHT_CONTROL_TEXT", c(0x44484D));
        r.put("LIGHT_TEXT_MUTED", c(0x8A909B));
        r.put("CHIP_HOVER_SURFACE", c(0x151B23));
        r.put("CHIP_HOVER_BORDER", c(0x767D89));
        r.put("CHIP_CLOSE_HOVER", c(0x2A3039));
        r.put("WORKSPACE_DIVIDER", c(0x282E38));
        r.put("SKY_TOP", c(0xF5FAFE));
        r.put("SKY_BAR_SURFACE", c(0xBFDCF2));
        r.put("SKY_CAPTION", c(0x6E87A2));
        r.put("CLOUD_SURFACE", c(0xF7FBFF));
        r.put("CLOUD_BORDER", c(0xAFCDE8));
        r.put("CLOUD_TEXT", c(0x40556B));
        r.put("CLOUD_HOVER_SURFACE", c(0xEAF4FC));
        r.put("CLOUD_HOVER_BORDER", c(0x8FB8DE));
        r.put("CLOUD_CLOSE", c(0x72879A));
        return new AskAiTheme(CLASSIC,
                new ComicPalette(c(0xF4F4F4), c(0x252525), c(0x1676D2), c(0x15827A),
                        c(0xF2C94C), c(0xE39A18), c(0xC94C4C)),
                c(0xFFFFFF), c(0xFFF8E1), c(0xFFFFFF), c(0xFAFAFA),
                c(0xC6C6C6), c(0x888888), c(0xAAAAAA), new Color(0, 0, 0, 110),
                r);
    }

    /** "Arcane Knowledge": navy + gold, derived from the AskAI logo. Function reds stay untouched. */
    public static AskAiTheme arcane() {
        Map<String, Color> r = new LinkedHashMap<String, Color>(classic().research);
        r.put("DARK_BASE", c(0x0B1220));
        r.put("DARK_SURFACE", c(0x111A28));
        r.put("WORKSPACE_SURFACE", c(0x192338));
        r.put("CHIP_SURFACE", c(0x0D1524));
        r.put("SECONDARY_SURFACE", c(0x141E30));
        r.put("SECONDARY_HOVER", c(0x1E2A44));
        r.put("PURPLE_PRIMARY", c(0xC08A2E));
        r.put("PURPLE_ACTION", c(0xDAA648));
        r.put("PURPLE_HOVER", c(0xCE9838));
        r.put("BORDER_DARK", c(0x645F55));
        r.put("BORDER_WINDOW", c(0x3A3F52));
        r.put("ACCENT_BLUE", c(0xDAA648));
        r.put("LIGHT_CONTROL_BG", c(0xFFFDF8));
        r.put("LIGHT_CONTROL_BORDER", c(0xCFC8B8));
        r.put("LIGHT_CONTROL_TEXT", c(0x474439));
        r.put("LIGHT_TEXT_MUTED", c(0x8E897C));
        r.put("CHIP_HOVER_SURFACE", c(0x161F33));
        r.put("CHIP_HOVER_BORDER", c(0x7D7664));
        r.put("CHIP_CLOSE_HOVER", c(0x2C3347));
        r.put("WORKSPACE_DIVIDER", c(0x2A3144));
        r.put("SKY_TOP", c(0xFAF6EC));
        r.put("SKY_BAR_SURFACE", c(0xE8D9B0));
        r.put("SKY_CAPTION", c(0x8A7A55));
        r.put("CLOUD_SURFACE", c(0xFDFAF2));
        r.put("CLOUD_BORDER", c(0xD9C690));
        r.put("CLOUD_TEXT", c(0x5C5238));
        r.put("CLOUD_HOVER_SURFACE", c(0xF7F0DD));
        r.put("CLOUD_HOVER_BORDER", c(0xC8B274));
        r.put("CLOUD_CLOSE", c(0x8A8064));
        return new AskAiTheme(ARCANE,
                new ComicPalette(c(0xF7F5F0), c(0x253048), c(0x315B91), c(0x287D79),
                        c(0xE6B650), c(0xC99232), c(0xC94C4C)),
                c(0xFFFDF8), c(0xF8EFD8), c(0xFFFDF8), c(0xFAF7F0),
                c(0xCBC4B4), c(0x8F897B), c(0xA9A295), new Color(0, 0, 0, 110),
                r);
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

    public ComicPalette comicPalette() {
        return comicPalette;
    }

    /** Resting fill of comic button plates (and other lifted light chips). */
    public Color buttonPlate() {
        return buttonPlate;
    }

    /** The warm cream focus surface of the amber family (find bar, search tag). */
    public Color warmFocus() {
        return warmFocus;
    }

    /** Light input field background. */
    public Color fieldBg() {
        return fieldBg;
    }

    /** The quieter field background of the find bar at rest. */
    public Color fieldBgQuiet() {
        return fieldBgQuiet;
    }

    /** The one neutral field contour. */
    public Color fieldBorder() {
        return fieldBorder;
    }

    /** Icon / hint grey. */
    public Color hintText() {
        return hintText;
    }

    /** Softer placeholder / inactive grey. */
    public Color hintTextSoft() {
        return hintTextSoft;
    }

    /** Translucent backdrop behind overlays. */
    public Color overlayDim() {
        return overlayDim;
    }

    /** A research-side token, keyed by its {@link ResearchUiPalette} constant name. */
    public Color research(String constantName) {
        Color color = research.get(constantName);
        if (color == null) {
            throw new IllegalArgumentException("unknown research token: " + constantName);
        }
        return color;
    }

    /** All research-side tokens (constant name → color), in table order. */
    public Map<String, Color> researchTokens() {
        return research;
    }

    private static Color c(int rgb) {
        return new Color(rgb);
    }
}
