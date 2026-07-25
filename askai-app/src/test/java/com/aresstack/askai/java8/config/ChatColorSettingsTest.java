package com.aresstack.askai.java8.config;

import org.junit.Test;

import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** Chat color value object: hex round-trip, defaults, and immutable with-copies. */
public class ChatColorSettingsTest {

    @Test
    public void hexRoundTripIgnoresAlphaAndAcceptsHash() {
        assertEquals("1676D2", ChatColorSettings.toHex(new Color(0x1676D2)));
        assertEquals(new Color(0x1676D2), ChatColorSettings.parseHex("1676D2", Color.BLACK));
        assertEquals(new Color(0x1676D2), ChatColorSettings.parseHex("#1676D2", Color.BLACK));
    }

    @Test
    public void invalidHexFallsBackToTheDefault() {
        assertSame(Color.RED, ChatColorSettings.parseHex(null, Color.RED));
        assertSame(Color.RED, ChatColorSettings.parseHex("nothex", Color.RED));
        assertSame(Color.RED, ChatColorSettings.parseHex("", Color.RED));
    }

    @Test
    public void defaultsMatchTheShippedPalette() {
        ChatColorSettings defaults = ChatColorSettings.defaults();
        assertEquals(new Color(0xF4F4F4), defaults.getTranscriptBackground());
        assertEquals(new Color(0x1676D2), defaults.getUserBackground());
        assertEquals(Color.WHITE, defaults.getUserForeground());
        assertEquals(new Color(0x15827A), defaults.getAssistantBackground());
        assertEquals(Color.WHITE, defaults.getAssistantForeground());
    }

    @Test
    public void withCopiesChangeOnlyTheTargetedColor() {
        ChatColorSettings changed = ChatColorSettings.defaults().withUserBackground(Color.BLACK);
        assertEquals(Color.BLACK, changed.getUserBackground());
        // Everything else is preserved.
        assertEquals(new Color(0x15827A), changed.getAssistantBackground());
        assertEquals(new Color(0xF4F4F4), changed.getTranscriptBackground());
    }

    @Test
    public void nullColorsFallBackToDefaults() {
        ChatColorSettings settings = new ChatColorSettings(null, null, null, null, null);
        assertEquals(ChatColorSettings.defaults().getUserBackground(), settings.getUserBackground());
        assertEquals(ChatColorSettings.defaults().getTranscriptBackground(), settings.getTranscriptBackground());
    }
}
