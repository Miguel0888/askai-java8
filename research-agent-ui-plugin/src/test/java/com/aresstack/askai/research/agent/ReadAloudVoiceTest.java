package com.aresstack.askai.research.agent;

import com.aresstack.askai.agent.model.speech.SpeechSynthesisPort;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The voice choice: model voice when the host port is active, Windows fallback otherwise —
 * including the failure case, so read-aloud never goes silent because of a model problem.
 */
public class ReadAloudVoiceTest {

    private final List<String> log = new ArrayList<String>();

    private final ReadAloudVoice.Fallback fallback = new ReadAloudVoice.Fallback() {
        public void speak(String markdown) {
            log.add("windows:" + markdown);
        }

        public void stop() {
            log.add("windows-stop");
        }
    };

    private SpeechSynthesisPort port(final boolean active, final boolean succeeds) {
        return new SpeechSynthesisPort() {
            public boolean isModelVoiceActive() {
                return active;
            }

            public String describeActiveVoice() {
                return active ? "Thorsten" : "";
            }

            public boolean speak(String plainText) {
                log.add("model:" + plainText);
                return succeeds;
            }

            public void stop() {
                log.add("model-stop");
            }
        };
    }

    @Test
    public void withoutAPortTheWindowsDefaultSpeaks() {
        ReadAloudVoice voice = new ReadAloudVoice(fallback);
        voice.speak("**Hallo**");
        assertEquals(java.util.Arrays.asList("windows:**Hallo**"), log);
    }

    @Test
    public void anInactivePortAlsoFallsBackToWindows() {
        ReadAloudVoice voice = new ReadAloudVoice(fallback);
        voice.setModelVoice(port(false, true));
        voice.speak("Text");
        assertEquals(java.util.Arrays.asList("windows:Text"), log);
    }

    @Test
    public void anActiveModelVoiceSpeaksPlainTextAndSilencesTheFallbackFirst() {
        ReadAloudVoice voice = new ReadAloudVoice(fallback);
        voice.setModelVoice(port(true, true));
        voice.speak("**Fett** und `Code`");
        assertEquals(java.util.Arrays.asList("windows-stop",
                "model:" + WindowsSpeech.plainTextForSpeech("**Fett** und `Code`")), log);
    }

    @Test
    public void aFailingModelVoiceFallsBackToWindowsSoNothingGoesSilent() {
        ReadAloudVoice voice = new ReadAloudVoice(fallback);
        voice.setModelVoice(port(true, false));
        voice.speak("Antwort");
        assertEquals(3, log.size());
        assertEquals("windows:Antwort", log.get(2));
    }

    @Test
    public void stopStopsBothVoices() {
        ReadAloudVoice voice = new ReadAloudVoice(fallback);
        voice.setModelVoice(port(true, true));
        voice.stop();
        assertEquals(java.util.Arrays.asList("model-stop", "windows-stop"), log);
    }
}
