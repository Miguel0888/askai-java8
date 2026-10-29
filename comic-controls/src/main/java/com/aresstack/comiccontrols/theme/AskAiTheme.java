package com.aresstack.comiccontrols.theme;

import java.awt.Color;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * THE semantic color token table of AskAI — the one place every surface takes its colors from.
 * Every token has a key of the form {@code group.role} ({@code comic.surface},
 * {@code research.DARK_BASE}, …) and is readable via {@link #color(String)}. The typed accessors
 * ({@link #comicPalette()}, {@link #buttonPlate()}, {@link #research(String)}, …) are views of the
 * same table.
 *
 * <p>Two built-in themes exist: {@link #classic()} (the historic values, pinned byte-identical)
 * and {@link #arcane()} (parchment, navy and gold: the "Arcane Knowledge" brand). The active theme
 * can be switched at runtime ({@link #setActive(AskAiTheme)}); consumers holding {@link ThemeColor}s
 * follow automatically.</p>
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
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<Runnable>();

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
        put(t, "research.SKY_MID", 0xF5FAFE); // == SKY_TOP: the classic sky is a single tone

        // research plugin role tokens (T7a): lowerCamel roles, classic = the former literal
        put(t, "research.conceptCardMutedOutline", 0xBBBBBB);
        put(t, "research.conceptCardMutedText", 0x999999);
        put(t, "research.documentAccent", 0xB05218);
        put(t, "research.phasePendingStripe", 0xD8D8D8);
        put(t, "research.actionTagBackground", 0xEB5757);
        put(t, "research.settingsErrorText", 0x961E1E);
        put(t, "research.settingsSuccessText", 0x1E6E1E);
        put(t, "research.settingsRowDivider", 0xDCDCDC);
        put(t, "research.settingsHintText", 0x6E6E6E);
        put(t, "research.fieldPlaceholderText", 0x808080);
        put(t, "research.engineListSelectionBackground", 0xB8CFE5);
        put(t, "research.engineListSelectionForeground", 0x000000);
        put(t, "research.flagIconFrame", 0x000000);
        put(t, "research.flagGermanyTopStripe", 0x000000);
        put(t, "research.flagGermanyMiddleStripe", 0xDD0000);
        put(t, "research.flagGermanyBottomStripe", 0xFFCE00);
        put(t, "research.flagUnionField", 0x012169);
        put(t, "research.flagUnionCross", 0xFFFFFF);
        put(t, "research.flagUnionCrossCenter", 0xC8102E);

        // bubble group: exactly BubblePalette.windowsPhoneInspired()
        put(t, "bubble.transcriptBackground", 0xF4F4F4);
        put(t, "bubble.userBackground", 0x1676D2);
        put(t, "bubble.userForeground", 0xFFFFFF);
        put(t, "bubble.assistantBackground", 0x15827A);
        put(t, "bubble.assistantForeground", 0xFFFFFF);
        put(t, "bubble.activityBackground", 0xF2C94C);
        put(t, "bubble.activityForeground", 0x252525);
        put(t, "bubble.activityAccent", 0xE39A18);
        put(t, "bubble.failureAccent", 0xC94C4C);
        put(t, "bubble.infoForeground", 0x6B6F76);
        return new AskAiTheme(CLASSIC, t);
    }

    /**
     * "Arcane Knowledge" v2: parchment instead of grey, navy as ink, gold as accent, a sunset sky; the
     * function reds stay untouched.
     */
    public static AskAiTheme arcane() {
        Map<String, Color> t = new LinkedHashMap<String, Color>(classic().tokens);
        put(t, "comic.surface", 0xEFE4CF);
        put(t, "comic.ink", 0x24304A);
        put(t, "comic.navigationBlue", 0x2D4F86);
        put(t, "comic.agentPetrol", 0x2A6F6B);
        put(t, "comic.accentYellow", 0xE9B949);
        put(t, "comic.accentOrange", 0xD08A2C);
        put(t, "comic.buttonPlate", 0xFBF6EA);
        put(t, "comic.warmFocus", 0xFFF3D6);
        put(t, "comic.fieldBg", 0xFFFCF4);
        put(t, "comic.fieldBgQuiet", 0xF8F1E2);
        put(t, "comic.fieldBorder", 0xCDBE9E);
        put(t, "comic.hintText", 0x857A63);
        put(t, "comic.hintTextSoft", 0xA89C82);
        t.put("comic.overlayDim", new Color(11, 18, 32, 120));
        put(t, "comic.placeholderText", 0x7A7262);

        // dark side + gold accents: the v1 arcane values
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
        put(t, "research.LIGHT_CONTROL_TEXT", 0x474439);
        put(t, "research.LIGHT_TEXT_MUTED", 0x8E897C);
        put(t, "research.CHIP_HOVER_SURFACE", 0x161F33);
        put(t, "research.CHIP_HOVER_BORDER", 0x7D7664);
        put(t, "research.CHIP_CLOSE_HOVER", 0x2C3347);
        put(t, "research.WORKSPACE_DIVIDER", 0x2A3144);
        // light controls + the sunset sky: v2
        put(t, "research.SKY_TOP", 0xE4572E);
        put(t, "research.SKY_MID", 0xF4A259);
        put(t, "research.SKY_BAR_SURFACE", 0xF3B07A);
        put(t, "research.SKY_CAPTION", 0x7A3B22);
        put(t, "research.CLOUD_SURFACE", 0xFFF6EC);
        put(t, "research.CLOUD_BORDER", 0xEDB48A);
        put(t, "research.CLOUD_TEXT", 0x5A2E1C);
        put(t, "research.CLOUD_HOVER_SURFACE", 0xFFEBD6);
        put(t, "research.CLOUD_HOVER_BORDER", 0xE08F5C);
        put(t, "research.CLOUD_CLOSE", 0x9A5A3C);
        put(t, "research.LIGHT_CONTROL_BG", 0xFFFCF4);
        put(t, "research.LIGHT_CONTROL_BORDER", 0xCDBE9E);

        // research plugin role tokens (T7a): arcane value copied from the family representative
        put(t, "research.conceptCardMutedOutline", 0xCDBE9E); // grey line: comic.fieldBorder
        put(t, "research.conceptCardMutedText", 0xA89C82); // light grey text: comic.hintTextSoft
        put(t, "research.documentAccent", 0xD08A2C); // orange: comic.accentOrange
        put(t, "research.phasePendingStripe", 0xCDBE9E); // grey line: comic.fieldBorder
        // research.actionTagBackground, research.settingsErrorText: red function colors stay classic
        put(t, "research.settingsSuccessText", 0x2A6F6B); // green: comic.agentPetrol
        put(t, "research.settingsRowDivider", 0xCDBE9E); // grey line: comic.fieldBorder
        put(t, "research.settingsHintText", 0x857A63); // mid-grey hint: comic.hintText
        put(t, "research.fieldPlaceholderText", 0x857A63); // mid-grey hint: comic.hintText
        put(t, "research.engineListSelectionBackground", 0x2D4F86); // blue: comic.navigationBlue
        put(t, "research.engineListSelectionForeground", 0x24304A); // black text: comic.ink
        put(t, "research.flagIconFrame", 0x24304A); // black line: comic.ink
        // research.flag* stripes: national flag colors are identity, not theme — they stay classic

        put(t, "bubble.transcriptBackground", 0xF6EEDD);
        put(t, "bubble.userBackground", 0x24406E);
        put(t, "bubble.userForeground", 0xFFF8EA);
        put(t, "bubble.assistantBackground", 0x2A6F6B);
        put(t, "bubble.assistantForeground", 0xFFF8EA);
        put(t, "bubble.activityBackground", 0xE9B949);
        put(t, "bubble.activityForeground", 0x24304A);
        put(t, "bubble.activityAccent", 0xB9771F);
        put(t, "bubble.failureAccent", 0xC94C4C);
        put(t, "bubble.infoForeground", 0x7A7262);
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

    /**
     * The active theme: the one last set via {@link #setActive(AskAiTheme)}; before that, resolved once
     * from {@value #PROPERTY} (missing: the default).
     */
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

    /**
     * Makes {@code theme} the active one (any number of times, on the EDT). Every {@link ThemeColor}
     * reads the new values from its next paint on. Deliberately SILENT: the host's switch gate
     * (re-install the UIManager defaults, refresh the windows) calls {@link #fireChanged()} afterwards.
     */
    public static void setActive(AskAiTheme theme) {
        if (theme == null) {
            throw new IllegalArgumentException("theme must not be null");
        }
        synchronized (AskAiTheme.class) {
            active = theme;
        }
    }

    /** Registers a listener for theme switches (see {@link #fireChanged()}); duplicates are ignored. */
    public static void addChangeListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeChangeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** Notifies every change listener once; called by the host after a switch is fully applied. */
    public static void fireChanged() {
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
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

    /** A bubble token by role ({@code transcriptBackground}, {@code userBackground}, ...). */
    public Color bubble(String role) {
        return color("bubble." + role);
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

    /**
     * All {@link ResearchUiPalette} tokens (constant name → color), in table order: the research keys whose
     * role is an UPPER_SNAKE constant name. Role tokens of the research plugin ({@code research.someRole},
     * lowerCamel) are regular tokens without a palette constant.
     */
    public Map<String, Color> researchTokens() {
        Map<String, Color> research = new LinkedHashMap<String, Color>();
        for (Map.Entry<String, Color> e : tokens.entrySet()) {
            if (e.getKey().startsWith(RESEARCH_PREFIX)) {
                String name = e.getKey().substring(RESEARCH_PREFIX.length());
                if (name.matches("[A-Z][A-Z0-9_]*")) {
                    research.put(name, e.getValue());
                }
            }
        }
        return Collections.unmodifiableMap(research);
    }

    private static void put(Map<String, Color> tokens, String key, int rgb) {
        tokens.put(key, new Color(rgb));
    }
}
