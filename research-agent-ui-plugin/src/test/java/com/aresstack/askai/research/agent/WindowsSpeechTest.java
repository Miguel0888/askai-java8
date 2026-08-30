package com.aresstack.askai.research.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The read-aloud text flattener: the voice needs sentences, not typography. */
public class WindowsSpeechTest {

    @Test
    public void markdownFlattensToSpeakableText() {
        assertEquals("a heading keeps its TEXT (spoken); the blank line stays a PARAGRAPH break",
                "Renault\n\nRenault baut seit 1899 Autos.",
                WindowsSpeech.plainTextForSpeech("## Renault\n\n**Renault** baut seit *1899* Autos."));
        assertEquals("Siehe die Quelle für Details.",
                WindowsSpeech.plainTextForSpeech("Siehe [die Quelle](https://example.org) für Details."));
        assertEquals("soft single line breaks flatten to spaces", "Erstens Zweitens",
                WindowsSpeech.plainTextForSpeech("- Erstens\n- Zweitens"));
        assertEquals("inline code is spoken (backticks only dropped), fenced blocks are skipped",
                "Der Befehl run macht das.",
                WindowsSpeech.plainTextForSpeech("Der Befehl `run` ```\nint x = 1;\n``` macht das."));
        assertEquals("", WindowsSpeech.plainTextForSpeech(null));
        assertEquals("", WindowsSpeech.plainTextForSpeech("```\nonly code\n```"));
    }

    @Test
    public void paragraphStructureSurvivesTheFlattening() {
        assertEquals("Erster Absatz mit Text.\n\nZweiter Absatz hier.",
                WindowsSpeech.plainTextForSpeech(
                        "Erster Absatz\nmit Text.\n\n\nZweiter **Absatz** hier."));
    }
}
