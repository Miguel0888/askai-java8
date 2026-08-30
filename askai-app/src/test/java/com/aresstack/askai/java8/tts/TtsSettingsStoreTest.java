package com.aresstack.askai.java8.tts;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/** The speech-output settings file round-trips and degrades to the Windows default. */
public class TtsSettingsStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void missingFileYieldsTheWindowsDefault() {
        TtsSettingsStore store = new TtsSettingsStore(
                temp.getRoot().toPath().resolve("nope.properties"));
        TextToSpeechSettings settings = store.load();
        assertEquals(TextToSpeechSettings.Engine.WINDOWS, settings.getEngine());
        assertEquals("", settings.getVoiceId());
        assertEquals(TextToSpeechSettings.DEFAULT_STARTUP_TIMEOUT_SECONDS,
                settings.getStartupTimeoutSeconds());
        assertEquals(TextToSpeechSettings.DEFAULT_NETWORK_TIMEOUT_SECONDS,
                settings.getNetworkTimeoutSeconds());
    }

    @Test
    public void selectionRoundTrips() throws Exception {
        TtsSettingsStore store = new TtsSettingsStore(
                temp.getRoot().toPath().resolve("tts.properties"));
        store.save(TextToSpeechSettings.defaults()
                .withEngine(TextToSpeechSettings.Engine.PIPER)
                .withVoiceId("de_DE-thorsten-high"));
        TextToSpeechSettings loaded = store.load();
        assertEquals(TextToSpeechSettings.Engine.PIPER, loaded.getEngine());
        assertEquals("de_DE-thorsten-high", loaded.getVoiceId());
    }

    @Test
    public void anUnknownEngineFallsBackToWindows() {
        assertEquals(TextToSpeechSettings.Engine.WINDOWS,
                TextToSpeechSettings.parseEngine("KOKORO"));
        assertEquals(TextToSpeechSettings.Engine.PIPER,
                TextToSpeechSettings.parseEngine(" PIPER "));
    }
}
