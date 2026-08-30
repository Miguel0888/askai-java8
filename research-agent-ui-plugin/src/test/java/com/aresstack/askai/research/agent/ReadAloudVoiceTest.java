package com.aresstack.askai.research.agent;

import com.aresstack.askai.agent.model.speech.SpeechSynthesisPort;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The voice choice PER LANGUAGE: the language's model voice when the host port has one, the
 * culture-matched Windows fallback otherwise — including the failure case, so read-aloud never
 * goes silent because of a model problem.
 */
public class ReadAloudVoiceTest {

    private final List<String> log = new ArrayList<String>();

    private final ReadAloudVoice.Fallback fallback = new ReadAloudVoice.Fallback() {
        public void speak(String markdown, String languageCode) {
            log.add("windows[" + languageCode + "]:" + markdown);
        }

        public void stop() {
            log.add("windows-stop");
        }
    };

    /** A port with a model voice for exactly ONE language. */
    private SpeechSynthesisPort portFor(final String activeLanguage, final boolean succeeds) {
        return new SpeechSynthesisPort() {
            public boolean isModelVoiceActive(String languageCode) {
                return activeLanguage.equals(languageCode);
            }

            public String describeActiveVoice(String languageCode) {
                return activeLanguage.equals(languageCode) ? "Thorsten" : "";
            }

            public boolean speak(String plainText, String languageCode) {
                log.add("model[" + languageCode + "]:" + plainText);
                return succeeds;
            }

            public void stop() {
                log.add("model-stop");
            }
        };
    }

    @Test
    public void withoutAPortTheWindowsDefaultSpeaksInTheAnswersLanguage() {
        ReadAloudVoice voice = new ReadAloudVoice(fallback);
        voice.speak("**Hallo**", "de");
        assertEquals(java.util.Arrays.asList("windows[de]:**Hallo**"), log);
    }

    @Test
    public void aPresentPortOwnsTheWholeUtterance() {
        // The HOST orchestrates now (segmentation, model voice OR Windows voice per segment):
        // a present port always gets the call, with the flattened plain text and the language.
        ReadAloudVoice voice = new ReadAloudVoice(fallback);
        voice.setModelVoice(portFor("de", true));
        voice.speak("**Deutsche** Antwort", "de");
        assertEquals(java.util.Arrays.asList("windows-stop",
                "model[de]:" + WindowsSpeech.plainTextForSpeech("**Deutsche** Antwort")), log);
    }

    @Test
    public void aFailingPortFallsBackToWindowsSoNothingGoesSilent() {
        ReadAloudVoice voice = new ReadAloudVoice(fallback);
        voice.setModelVoice(portFor("de", false)); // port produced NO audio at all
        voice.speak("Antwort", "en");
        assertEquals(3, log.size());
        assertEquals("model[en]:Antwort", log.get(1));
        assertEquals("windows[en]:Antwort", log.get(2));
    }

    @Test
    public void stopStopsBothVoices() {
        ReadAloudVoice voice = new ReadAloudVoice(fallback);
        voice.setModelVoice(portFor("de", true));
        voice.stop();
        assertEquals(java.util.Arrays.asList("model-stop", "windows-stop"), log);
    }

    @Test
    public void windowsCultureMappingIsAFixedSafeList() {
        assertEquals("de-DE", WindowsSpeech.cultureFor("de"));
        assertEquals("en-US", WindowsSpeech.cultureFor("en"));
        assertNull("unknown codes keep the default voice", WindowsSpeech.cultureFor("fr"));
        assertNull(WindowsSpeech.cultureFor(null));
    }
}
