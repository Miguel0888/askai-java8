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

    // ------------------------------------------------------------------ run progress card (one per run)

    /** Title of the single in-place progress card. */
    public static String progressTitle() {
        return de() ? "Webrecherche läuft" : "Web research in progress";
    }

    /** The search the agent is running right now — the query is the user's own request, so it is shown. */
    public static String progressSearchLine(String query) {
        return (de() ? "Suche im Web nach:\n" : "Searching the web for:\n") + "„" + query + "“";
    }

    /** The real target website the browser is on right now (final host + page title, never raw URLs). */
    public static String progressPageLine(String host, String pageTitle) {
        String head = de() ? "Gerade geöffnet:\n" : "Currently open:\n";
        return head + host + (pageTitle == null || pageTitle.isEmpty() ? "" : "\n" + pageTitle);
    }

    /** Heading of the compact visible activity history (the last few processed websites). */
    public static String recentPagesTitle() {
        return de() ? "Zuletzt:" : "Recently:";
    }

    /** One history entry for a page recorded as a source. */
    public static String historyAccepted(String host, String pageTitle) {
        return "✓ " + host + (pageTitle == null || pageTitle.isEmpty() ? "" : " — " + pageTitle);
    }

    /** One history entry for a page checked and found not relevant. */
    public static String historySkipped(String host) {
        return "– " + host + (de() ? " — nicht relevant" : " — not relevant");
    }

    /** Explains the headless switch: transparency comes from the chat, not from a visible browser window. */
    public static String headlessHint() {
        return de()
                ? "Headless: Das Browserfenster bleibt verborgen. Die besuchten Websites werden "
                        + "während der Recherche im Chat angezeigt."
                : "Headless: the browser window stays hidden. The websites visited are shown in the "
                        + "chat while the research runs.";
    }

    /** The card's live counters + a readable current activity (never enum names or raw URLs). */
    public static String progressLine(int pages, int sources, int hosts, String activityToken) {
        String counters = de()
                ? pages + " Seiten geprüft · " + sources + " Quellen aufgenommen · " + hosts
                        + (hosts == 1 ? " Website" : " Websites")
                : pages + " pages checked · " + sources + " sources recorded · " + hosts
                        + (hosts == 1 ? " website" : " websites");
        return counters + "\n" + activityLabel(activityToken);
    }

    private static String activityLabel(String token) {
        String t = token == null ? "" : token;
        if ("SEARCHING".equals(t)) {
            return de() ? "Suche läuft …" : "Searching …";
        }
        if ("READING_PAGE".equals(t) || "OPENING_PAGE".equals(t)) {
            return de() ? "Seite wird gelesen …" : "Reading the page …";
        }
        if ("SOURCE_ACCEPTED".equals(t) || "RECORDING_SOURCE".equals(t)) {
            return de() ? "Als Quelle aufgenommen" : "Recorded as a source";
        }
        if ("PAGE_SKIPPED".equals(t)) {
            return de() ? "Geprüft – nicht relevant" : "Checked – not relevant";
        }
        return de() ? "Arbeite …" : "Working …";
    }

    /** Summary shown when the progress card completes. */
    public static String runFinishedSummary(int pages, int sources, int hosts) {
        return de()
                ? "Recherche-Durchlauf beendet — " + pages + " Seiten, " + sources + " Quellen, "
                        + hosts + (hosts == 1 ? " Website" : " Websites")
                : "Research pass finished — " + pages + " pages, " + sources + " sources, "
                        + hosts + (hosts == 1 ? " website" : " websites");
    }

    // ------------------------------------------------------------------ run outcome card (result + decision)

    /**
     * The user-facing result card for a terminal run outcome: what was achieved, why the run ended, what is
     * still missing and what the agent recommends — plain language, no stop-reason enum names, no internal
     * ids, no raw URLs. The matching actions are chosen by the session.
     */
    public static String outcomeCard(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        String stop = o.getStopReason();
        boolean sufficient = o.isEvidenceSufficient();
        StringBuilder sb = new StringBuilder();
        if ("SUFFICIENT_EVIDENCE".equals(stop) || ("SOURCE_BUDGET_EXHAUSTED".equals(stop) && sufficient)) {
            sb.append(de()
                    ? "**Die Evidenzsammlung ist abgeschlossen.**\n\nIch habe " + achieved(o)
                            + " Die Mindestanforderungen an Quellen und Quellenvielfalt sind erfüllt. "
                            + "Ich empfehle, die Belege jetzt zu prüfen."
                    : "**The evidence collection is complete.**\n\nI recorded " + achieved(o)
                            + " The minimum requirements for sources and source diversity are met. "
                            + "I recommend reviewing the evidence now.");
            return sb.toString();
        }
        if ("USER_CANCELLED".equals(stop)) {
            return de()
                    ? "**Die Recherche wurde pausiert.**\n\nBisher habe ich " + achieved(o)
                            + " Du kannst jederzeit fortsetzen oder die Recherche beenden."
                    : "**The research was paused.**\n\nSo far I recorded " + achieved(o)
                            + " You can continue at any time or end the research.";
        }
        if ("MCP_UNAVAILABLE".equals(stop)) {
            return de()
                    ? "**Die Recherche wurde durch ein technisches Problem unterbrochen.**\n\n"
                            + "Die Browser- oder Recherche-Werkzeuge waren nicht erreichbar. Bisher habe ich "
                            + achieved(o) + " Ich empfehle, es erneut zu versuchen; falls das Problem "
                            + "bleibt, hilft ein Blick in die Runtime-Konfiguration."
                    : "**The research was interrupted by a technical problem.**\n\n"
                            + "The browser or research tools were unreachable. So far I recorded "
                            + achieved(o) + " I recommend trying again; if the problem persists, "
                            + "check the runtime configuration.";
        }
        if ("ERROR_BUDGET_EXHAUSTED".equals(stop)) {
            return de()
                    ? "**Die Recherche wurde nach mehreren Fehlversuchen angehalten.**\n\nBisher habe ich "
                            + achieved(o) + " Ich empfehle, es erneut zu versuchen."
                    : "**The research stopped after several consecutive errors.**\n\nSo far I recorded "
                            + achieved(o) + " I recommend trying again.";
        }
        if ("NO_RELEVANT_PATHS".equals(stop) && !sufficient) {
            return de()
                    ? "**Ich habe keine weiteren passenden Seiten zu deiner Frage gefunden.**\n\n"
                            + "Bisher habe ich " + achieved(o) + " " + missing(o)
                            + " Ich empfehle, den Suchauftrag zu präzisieren oder andere Suchbegriffe "
                            + "zu wählen."
                    : "**I found no further pages matching your question.**\n\n"
                            + "So far I recorded " + achieved(o) + " " + missing(o)
                            + " I recommend refining the research scope or trying different search terms.";
        }
        // Budget exhausted (tool/page/time) or other recoverable stops with open requirements.
        sb.append(de()
                ? "**Die Recherche ist noch nicht belastbar abgeschlossen.**\n\nIch habe " + achieved(o)
                        + " " + missing(o) + " Mein Budget für diesen Durchlauf ist aufgebraucht. "
                        + recommendation(o)
                : "**The research is not reliably complete yet.**\n\nI recorded " + achieved(o)
                        + " " + missing(o) + " My budget for this pass is used up. "
                        + recommendation(o));
        return sb.toString();
    }

    private static String achieved(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        int s = o.getAcceptedSources();
        int h = o.getDistinctHosts();
        return de()
                ? s + (s == 1 ? " relevante Quelle" : " relevante Quellen") + " von " + h
                        + (h == 1 ? " Website" : " verschiedenen Websites") + " aufgenommen."
                : s + (s == 1 ? " relevant source" : " relevant sources") + " from " + h
                        + (h == 1 ? " website" : " different websites") + ".";
    }

    private static String missing(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        if ("INSUFFICIENT_SOURCES".equals(o.getLimitation())) {
            return de()
                    ? "Für ein belastbares Ergebnis fehlen noch Quellen (mindestens "
                            + o.getMinimumSources() + " nötig)."
                    : "More sources are needed for a reliable result (at least "
                            + o.getMinimumSources() + " required).";
        }
        if ("INSUFFICIENT_HOST_DIVERSITY".equals(o.getLimitation())) {
            return de()
                    ? "Die Quellenvielfalt reicht noch nicht: Es fehlen unabhängige Websites "
                            + "(mindestens " + o.getMinimumDistinctHosts() + " verschiedene nötig)."
                    : "Source diversity is not sufficient yet: independent websites are missing "
                            + "(at least " + o.getMinimumDistinctHosts() + " different ones required).";
        }
        return de() ? "Die Mindestanforderungen sind erfüllt." : "The minimum requirements are met.";
    }

    private static String recommendation(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        String action = o.getRecommendedAction();
        if ("CONTINUE_RESEARCH".equals(action)) {
            return de()
                    ? "Ich empfehle, gezielt nach weiteren unabhängigen Websites zu suchen."
                    : "I recommend searching specifically for additional independent websites.";
        }
        if ("REFINE_RESEARCH_SCOPE".equals(action)) {
            return de()
                    ? "Ich empfehle, den Suchauftrag zu präzisieren."
                    : "I recommend refining the research scope.";
        }
        if ("REVIEW_EVIDENCE".equals(action)) {
            return de()
                    ? "Ich empfehle, die vorhandenen Belege zu prüfen."
                    : "I recommend reviewing the collected evidence.";
        }
        return de() ? "Du entscheidest, wie es weitergeht." : "You decide how to proceed.";
    }

    /** Localized labels for the typed result-card actions (ids are stable, labels are language-bound). */
    public static String actionLabel(String actionId) {
        if ("continue".equals(actionId)) {
            return de() ? "Weiterrecherchieren" : "Continue research";
        }
        if ("sources".equals(actionId)) {
            return de() ? "Quellen ansehen" : "View sources";
        }
        if ("refine".equals(actionId)) {
            return de() ? "Suchauftrag ergänzen" : "Refine scope";
        }
        if ("limit".equals(actionId)) {
            return de() ? "Mit Einschränkung fortfahren" : "Continue with limitation";
        }
        if ("end".equals(actionId)) {
            return de() ? "Recherche beenden" : "End research";
        }
        if ("review".equals(actionId)) {
            return de() ? "Belege prüfen" : "Review evidence";
        }
        if ("retry".equals(actionId)) {
            return de() ? "Erneut versuchen" : "Try again";
        }
        if ("config".equals(actionId)) {
            return de() ? "Konfiguration öffnen" : "Open configuration";
        }
        if ("resume".equals(actionId)) {
            return de() ? "Fortsetzen" : "Resume";
        }
        if ("approve".equals(actionId)) {
            return de() ? "Freigeben" : "Approve";
        }
        if ("changes".equals(actionId)) {
            return de() ? "Änderungen anfordern" : "Request changes";
        }
        return actionId;
    }

    /** Visible confirmation once the user chose to proceed despite an unmet evidence requirement. */
    public static String limitationRecorded(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        return de()
                ? "Einschränkung festgehalten: " + missing(o) + " Der aktuelle Stand wird NICHT als "
                        + "uneingeschränkt ausreichend behandelt."
                : "Limitation recorded: " + missing(o) + " The current state is NOT treated as "
                        + "unconditionally sufficient.";
    }

    /** The focused follow-up when the user chose to refine the research scope. */
    public static String refinePrompt() {
        return de()
                ? "Gern — welche Richtung soll ich ergänzen oder ändern? Nenne z. B. zusätzliche "
                        + "Aspekte, andere Suchbegriffe oder Quellenarten."
                : "Sure — which direction should I add or change? For example additional aspects, "
                        + "different search terms or types of sources.";
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
