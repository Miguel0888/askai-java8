package com.aresstack.askai.research.agent;

import java.util.Locale;

/**
 * The research agent's PLAYBOOK: the stable knowledge about its role, method and limits, phrased for the
 * USER. It backs the consultative dialog (greeting with an open question, paraphrase, focused follow-ups,
 * "anything missing?", proposing the approval) and the agent's ability to EXPLAIN itself on demand. Internal
 * command or phase identifiers never appear in these texts. This is also the stable-context half that a
 * future LLM binding receives as system knowledge — the seam stays, only the wording generator changes.
 */
public final class ResearchPlaybook {

    /** UI language of every agent utterance (default English; switchable in the Runtime tab). */
    public enum Language { ENGLISH, GERMAN }

    private static volatile Language language = Language.ENGLISH;

    public static void setLanguage(Language value) {
        language = value == null ? Language.ENGLISH : value;
    }

    /** Convenience for persisted codes ("de" → German, anything else → English default). */
    public static void setLanguage(String code) {
        setLanguage("de".equalsIgnoreCase(code) ? Language.GERMAN : Language.ENGLISH);
    }

    public static Language getLanguage() {
        return language;
    }

    private static boolean de() {
        return language == Language.GERMAN;
    }

    private ResearchPlaybook() {
    }

    /** First contact: friendly, takes initiative, ONE open question — never an approval. */
    public static String greeting() {
        if (de()) {
            return "Hallo! Ich unterstütze dich bei einer strukturierten Recherche: Wir klären zuerst, "
                    + "WAS du herausfinden willst, dann schlage ich dir eine Gliederung zur Freigabe "
                    + "vor, und danach recherchiere ich echte Webquellen und sammle die Belege für "
                    + "dich.\n\n"
                    + "Also: Was möchtest du herausfinden?";
        }
        return "Hi! I help you run a structured research: we first clarify WHAT you want to find out, "
                + "then I propose an outline for your approval, and after that I research real web "
                + "sources and collect the evidence for you.\n\n"
                + "So: what would you like to find out?";
    }

    /** Echo-based paraphrase + ONE focused follow-up (honest: mirrors, does not pretend deep analysis). */
    public static String paraphraseAndFocus(String question) {
        if (de()) {
            return "Verstanden — du möchtest recherchieren:\n\n> " + question + "\n\n"
                    + "Eine fokussierende Frage: Gibt es bestimmte Aspekte, die dir besonders wichtig "
                    + "sind (zum Beispiel Architektur, Updates, Sicherheit, Alternativen)? "
                    + "Du kannst auch einfach \"start\" sagen, dann arbeite ich mit dem, was wir haben.";
        }
        return "Got it — you want to research:\n\n> " + question + "\n\n"
                + "One focusing question: are there specific aspects that matter most to you "
                + "(for example architecture, updates, security, alternatives)? "
                + "You can also just say \"start\" and I will work with what we have.";
    }

    /** Scope summary + the explicit missing-anything check. */
    public static String summarizeAndCheck(String question, java.util.List<String> aspects) {
        StringBuilder sb = new StringBuilder(de()
                ? "So verstehe ich den Umfang bisher:\n\n"
                : "Here is my current understanding of the scope:\n\n");
        sb.append(de() ? "- Forschungsfrage: " : "- Research question: ").append(question).append('\n');
        for (String aspect : aspects) {
            sb.append(de() ? "- Schwerpunkt: " : "- Focus: ").append(aspect).append('\n');
        }
        sb.append(de()
                ? "\nFehlt noch etwas Wichtiges? Wenn alles vollständig ist, sage einfach \"nein\" "
                        + "(oder \"start\"), dann schlage ich die Gliederung vor."
                : "\nIs anything important missing? If it is complete, just say \"no\" (or \"start\") "
                        + "and I will propose the outline.");
        return sb.toString();
    }

    /** True when the user closes the scoping ("no, that's it" / "start" / "go ahead"). */
    public static boolean isConfirmation(String text) {
        String t = normalize(text);
        return t.equals("no") || t.equals("nein") || t.startsWith("no,") || t.startsWith("no ")
                || t.startsWith("nein,") || t.contains("nothing missing") || t.contains("passt")
                || t.contains("that's it") || t.contains("thats it") || isStartRequest(text);
    }

    /** True when the user wants to skip further questions entirely. */
    public static boolean isStartRequest(String text) {
        String t = normalize(text);
        return t.contains("start") || t.contains("go ahead") || t.contains("leg los")
                || t.contains("los geht") || t.equals("ja") || t.equals("yes");
    }

    /**
     * Explainability: a playbook answer for meta questions ("what can you do", "how does this work",
     * "why do you ask", "which phase", "what happens next"), or {@code null} for normal content.
     * Answers combine the stable playbook with the LIVE state description passed in.
     */
    public static String explain(String text, String phaseDescription) {
        String t = normalize(text);
        boolean asksWhat = t.contains("what can you") || t.contains("was kannst du")
                || t.contains("what do you do") || t.contains("was machst du")
                || t.contains("how does this work") || t.contains("wie funktioniert");
        if (asksWhat) {
            if (de()) {
                return "Ich helfe dir zuerst, die Forschungsfrage und ihren Umfang zu schärfen. Danach "
                        + "erstelle ich eine Gliederung, die du prüfen kannst. Nach deiner Freigabe "
                        + "durchsuche ich echte Webquellen, sammle und dedupliziere Belege und lege dir "
                        + "die Ergebnisse zur Prüfung vor. Später kann daraus ein strukturierter Bericht "
                        + "entstehen.\n\nAktuell: " + phaseDescription;
            }
            return "I first help you sharpen the research question and its scope. Then I create an "
                    + "outline you can review. After your approval I browse real web sources, collect "
                    + "and deduplicate evidence, and present the findings for your review. Later this "
                    + "can grow into a structured report.\n\nRight now: " + phaseDescription;
        }
        if (t.contains("why do you ask") || t.contains("warum fragst du")
                || t.contains("why so many questions") || t.contains("so viele fragen")) {
            if (de()) {
                return "Damit ich nicht am eigentlichen Ziel vorbeirecherchiere — ich frage nur nach "
                        + "Punkten, die Suchrichtung, Quellenwahl oder Ergebnisform wesentlich "
                        + "beeinflussen.";
            }
            return "So that I do not research past your actual goal — I only ask about points that "
                    + "change the search direction, the choice of sources or the form of the result.";
        }
        if (t.contains("which phase") || t.contains("welche phase") || t.contains("what phase")
                || t.contains("what happens next") || t.contains("was passiert als")) {
            return phaseDescription;
        }
        return null;
    }

    /** Human wording for the current situation (no internal identifiers). */
    public static String describePhase(String phaseId, String stateId, boolean hasQuestion) {
        if ("scoping".equals(phaseId) || !hasQuestion) {
            return de()
                    ? "Wir klären gerade, was du herausfinden möchtest. Als Nächstes fasse ich den "
                            + "Umfang zusammen und schlage dann eine Gliederung zur Freigabe vor."
                    : "We are clarifying what you want to find out. Next: I summarize the scope, then "
                            + "propose an outline for your approval.";
        }
        if ("outline".equals(phaseId)) {
            return de()
                    ? "Die Gliederung wartet auf deine Freigabe. Nach deiner Freigabe starte ich die "
                            + "Webrecherche automatisch."
                    : "The outline is waiting for your approval. After you approve it, I start the web "
                            + "research automatically.";
        }
        if ("research".equals(phaseId)) {
            return de()
                    ? "Ich recherchiere gerade Webquellen zu deiner Frage. Sobald die Belege "
                            + "ausreichen, bitte ich dich um eine Prüfung."
                    : "I am researching web sources for your question. When the evidence is sufficient, "
                            + "I will ask you to review it.";
        }
        if ("evidence".equals(phaseId)) {
            return de()
                    ? "Die gesammelten Belege warten auf deine Prüfung."
                    : "The collected evidence is waiting for your review.";
        }
        return (de() ? "Aktueller Schritt: " : "Current step: ") + phaseId + " (" + stateId + ").";
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
