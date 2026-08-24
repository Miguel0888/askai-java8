package com.aresstack.askai.research.runtime.scope;

import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeCalibrationProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationRequest;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationResult;
import com.aresstack.askai.research.runtime.scope.MainModelScopeProbeGenerator.GeneratorSettings;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The productive generator: exactly ONE main-model call, typed failures survive 1:1, malformed
 * output is INVALID_RESPONSE (no repair loop, no synthetic fallback), controls only ever attach to
 * negotiated IN/OUT posts the request actually contains, duplicates are handled deterministically,
 * and a missing control is left missing — the calibration's coverage rule owns that verdict.
 */
public class MainModelScopeProbeGeneratorTest {

    /** Scripted fake: returns one canned result, records every call. */
    private static final class ScriptedChat implements MainModelChat {
        private final MainModelChatResult result;
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();

        ScriptedChat(MainModelChatResult result) {
            this.result = result;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens) {
            calls.add(messages);
            return result;
        }

        @Override
        public String modelName() {
            return "scripted";
        }
    }

    private static ProbeGenerationRequest request() {
        return new ProbeGenerationRequest(
                "Welche Wearables sind für den Arbeitsschutz auf Baustellen relevant?",
                Arrays.asList("Arbeitsschutz"), Arrays.asList("Baustelle"),
                Arrays.asList("Sensorhelme"),
                Arrays.asList(
                        new ScopeAnchor("anchor-helme", "f1", "Schutzhelme mit Sensorik",
                                ScopeAnchor.Membership.IN),
                        new ScopeAnchor("anchor-fitness", "f2", "Consumer-Fitness-Armbänder",
                                ScopeAnchor.Membership.OUT),
                        new ScopeAnchor("anchor-guess", "f3", "Hypothetische Drohnenwartung",
                                ScopeAnchor.Membership.PROVISIONAL)),
                3);
    }

    private static MainModelScopeProbeGenerator generator(ScriptedChat chat) {
        return new MainModelScopeProbeGenerator(chat, new GeneratorSettings(0.7d, 2048, 2));
    }

    private static String okAnswer() {
        return "{\"broadProbes\":["
                + "{\"id\":\"p1\",\"text\":\"Exoskelette für Lagerarbeiter\"},"
                + "{\"id\":\"p2\",\"text\":\"Alleinarbeiterschutz\"}],"
                + "\"calibrationProbes\":["
                + "{\"id\":\"c1\",\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm mit Stoßsensor\"},"
                + "{\"id\":\"c2\",\"parentAnchorId\":\"anchor-fitness\",\"text\":\"Pulsuhr fürs Joggen\"}]}";
    }

    @Test
    public void exactlyOneModelCallAndTheProvisionalPostNeverReachesThePrompt() {
        ScriptedChat chat = new ScriptedChat(MainModelChatResult.ok(okAnswer()));
        ProbeGenerationResult result = generator(chat).generate(request());

        assertEquals("one generation = ONE model call", 1, chat.calls.size());
        assertTrue(result.isOk());
        String prompt = chat.calls.get(0).get(1).getContent();
        assertTrue(prompt.contains("anchor-helme"));
        assertTrue(prompt.contains("anchor-fitness"));
        assertFalse("an unconfirmed hypothesis gets NO controls — it is not even offered",
                prompt.contains("anchor-guess"));
    }

    @Test
    public void typedModelFailuresSurviveUntouched() {
        ScriptedChat timeout = new ScriptedChat(MainModelChatResult.failure(
                MainModelChatResult.Status.TIMEOUT, "read timed out"));
        ProbeGenerationResult result = generator(timeout).generate(request());
        assertEquals(ProbeGenerationResult.Status.TIMEOUT, result.getStatus());
        assertEquals("read timed out", result.getMessage());
        assertEquals("no retry, no repair call", 1, timeout.calls.size());

        ScriptedChat unavailable = new ScriptedChat(MainModelChatResult.failure(
                MainModelChatResult.Status.PROVIDER_FAILURE, "no main-model descriptor"));
        assertEquals(ProbeGenerationResult.Status.PROVIDER_FAILURE,
                generator(unavailable).generate(request()).getStatus());
    }

    @Test
    public void malformedAnswersAreInvalidResponseNeverAnEmptySweep() {
        assertEquals(ProbeGenerationResult.Status.INVALID_RESPONSE,
                generator(new ScriptedChat(MainModelChatResult.ok("Gerne! Hier sind Ideen: ...")))
                        .generate(request()).getStatus());
        assertEquals("valid JSON of the wrong shape is just as broken",
                ProbeGenerationResult.Status.INVALID_RESPONSE,
                generator(new ScriptedChat(MainModelChatResult.ok("{\"probes\":[]}")))
                        .generate(request()).getStatus());
        assertEquals("zero usable material must not masquerade as a clean 'nothing found'",
                ProbeGenerationResult.Status.INVALID_RESPONSE,
                generator(new ScriptedChat(MainModelChatResult.ok(
                        "{\"broadProbes\":[],\"calibrationProbes\":[]}")))
                        .generate(request()).getStatus());
    }

    @Test
    public void aMarkdownFencedAnswerIsUnwrappedLocallyNotReAsked() {
        ScriptedChat chat = new ScriptedChat(MainModelChatResult.ok(
                "```json\n" + okAnswer() + "\n```"));
        ProbeGenerationResult result = generator(chat).generate(request());
        assertTrue(result.isOk());
        assertEquals(1, chat.calls.size());
    }

    @Test
    public void controlsForUnknownOrProvisionalPostsAreDroppedAndDiagnosed() {
        String answer = "{\"broadProbes\":[{\"id\":\"p1\",\"text\":\"Exoskelette\"}],"
                + "\"calibrationProbes\":["
                + "{\"id\":\"c1\",\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm mit Sensor\"},"
                + "{\"id\":\"c2\",\"parentAnchorId\":\"anchor-erfunden\",\"text\":\"frei erfunden\"},"
                + "{\"id\":\"c3\",\"parentAnchorId\":\"anchor-guess\",\"text\":\"Drohneninspektion\"}]}";
        ProbeGenerationResult result =
                generator(new ScriptedChat(MainModelChatResult.ok(answer))).generate(request());

        assertTrue(result.isOk());
        List<ScopeCalibrationProbe> controls = result.getGeneration().getCalibrationProbes();
        assertEquals("only the control with a real negotiated parent survives", 1, controls.size());
        assertEquals("anchor-helme", controls.get(0).getParentAnchorId());
        assertTrue("the invented referent is diagnosed, never silently re-bound",
                result.getMessage().contains("anchor-erfunden"));
        assertTrue("the hypothesis control is diagnosed too",
                result.getMessage().contains("anchor-guess"));
    }

    @Test
    public void duplicatesAndOverflowAreHandledDeterministically() {
        String answer = "{\"broadProbes\":["
                + "{\"id\":\"p1\",\"text\":\"Exoskelette für Lagerarbeiter\"},"
                + "{\"id\":\"p1\",\"text\":\"etwas völlig anderes\"},"
                + "{\"id\":\"p2\",\"text\":\"  EXOSKELETTE   für Lagerarbeiter \"},"
                + "{\"id\":\"p3\",\"text\":\"Alleinarbeiterschutz\"},"
                + "{\"id\":\"p4\",\"text\":\"Gaswarnwesten\"},"
                + "{\"id\":\"p5\",\"text\":\"über dem Limit\"}],"
                + "\"calibrationProbes\":["
                + "{\"id\":\"c1\",\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm eins\"},"
                + "{\"id\":\"c2\",\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm zwei\"},"
                + "{\"id\":\"c3\",\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm drei\"}]}";
        ProbeGenerationResult result =
                generator(new ScriptedChat(MainModelChatResult.ok(answer))).generate(request());

        assertTrue(result.isOk());
        List<ScopeProbe> broad = result.getGeneration().getBroadProbes();
        assertEquals("first wins; id-dupe, text-dupe and over-target drop", 3, broad.size());
        assertEquals("p1", broad.get(0).getProbeId());
        assertEquals("p3", broad.get(1).getProbeId());
        assertEquals("p4", broad.get(2).getProbeId());
        assertEquals("per-anchor control cap respected",
                2, result.getGeneration().getCalibrationProbes().size());
        assertTrue(result.getMessage().contains("p2"));
        assertTrue(result.getMessage().contains("p5"));
        assertTrue(result.getMessage().contains("c3"));
    }

    /** A missing control stays missing — WEAK coverage is the calibrator's honest verdict. */
    @Test
    public void aMissingControlIsNeverFabricated() {
        String answer = "{\"broadProbes\":[{\"id\":\"p1\",\"text\":\"Exoskelette\"}],"
                + "\"calibrationProbes\":["
                + "{\"id\":\"c1\",\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm mit Sensor\"}]}";
        ProbeGenerationResult result =
                generator(new ScriptedChat(MainModelChatResult.ok(answer))).generate(request());

        assertTrue("the generation stands", result.isOk());
        List<ScopeCalibrationProbe> controls = result.getGeneration().getCalibrationProbes();
        assertEquals(1, controls.size());
        for (ScopeCalibrationProbe control : controls) {
            assertFalse("nothing was invented for the uncovered anchor-fitness",
                    control.getParentAnchorId().equals("anchor-fitness"));
        }
    }
}
