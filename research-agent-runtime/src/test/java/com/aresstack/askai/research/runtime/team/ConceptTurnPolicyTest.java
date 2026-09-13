package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The machine-side intent separation (safety-slice gate 2), pinned with the LIVE sentences that
 * broke the gate: the compound rewrite that became partial adds, and the delete order that
 * silently became a scope exclusion.
 */
public class ConceptTurnPolicyTest {

    @Test
    public void theGatesLiveSentencesClassifyExactlyAsRuled() {
        assertEquals(ConceptTurnPolicy.Mode.DELETE_READ_ONLY, ConceptTurnPolicy.modeFor(
                "Lösche den kompletten Buch-Hauptzweig „FreeRTOS Grundlagen“."));
        assertEquals(ConceptTurnPolicy.Mode.RESTRUCTURE_READ_ONLY, ConceptTurnPolicy.modeFor(
                "Überarbeite den gesamten Konzeptzweig und ersetze seine bisherigen "
                        + "Unterkarten durch „Tasks“, „Synchronisation“ und „Debugging“."));
        assertEquals("the exclusion wording stays a FULL turn — the facade must keep working",
                ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("PlatformIO möchte ich nicht behandeln."));
    }

    @Test
    public void deleteWinsOverRestructureAndPlainBuildingStaysFull() {
        assertEquals("delete is the stricter mode when both match",
                ConceptTurnPolicy.Mode.DELETE_READ_ONLY,
                ConceptTurnPolicy.modeFor("Ersetze nichts, lösche den Zweig einfach."));
        assertEquals(ConceptTurnPolicy.Mode.DELETE_READ_ONLY,
                ConceptTurnPolicy.modeFor("Entferne die Karte Toolchain aus dem Konzept."));
        assertEquals(ConceptTurnPolicy.Mode.DELETE_READ_ONLY,
                ConceptTurnPolicy.modeFor("Please remove the ESP-IDF card."));
        assertEquals("a bare 'entferne X' stays conversational — the exclusion drill owns it",
                ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Entferne ESP-IDF."));
        assertEquals(ConceptTurnPolicy.Mode.RESTRUCTURE_READ_ONLY,
                ConceptTurnPolicy.modeFor("Verschiebe Toolchain unter Grundlagen."));
        assertEquals(ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Füge unter FreeRTOS bitte Tasks hinzu."));
        assertEquals(ConceptTurnPolicy.Mode.FULL,
                ConceptTurnPolicy.modeFor("Schreibe ein Buch über FreeRTOS auf dem ESP32."));
        assertEquals(ConceptTurnPolicy.Mode.FULL, ConceptTurnPolicy.modeFor(null));
    }
}
