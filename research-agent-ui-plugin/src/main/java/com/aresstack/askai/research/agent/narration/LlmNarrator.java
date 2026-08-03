package com.aresstack.askai.research.agent.narration;

import com.aresstack.askai.agent.model.inference.AgentInferencePort;
import com.aresstack.askai.research.agent.ResearchLanguage;
import com.aresstack.askai.research.agent.ResearchLanguageProvider;
import com.aresstack.askai.research.agent.SessionResearchLanguage;

import java.util.Map;

/**
 * The LLM-backed {@link AsyncNarrator}: renders the {@link NarrationPayload} (data-to-text — never
 * "paraphrase this string") through the host's {@link AgentInferencePort}. It only PHRASES; the facts,
 * the validation and the fallback stay with the coordinator/validator. Thinking deltas stream into the
 * thought bubble; cancel aborts the generation and frees the local model.
 */
public final class LlmNarrator implements AsyncNarrator {

    private final AgentInferencePort port;
    private final ResearchLanguageProvider language;

    /** English-default convenience (tests without a session language). */
    public LlmNarrator(AgentInferencePort port) {
        this(port, null);
    }

    /** @param language the SESSION's live language — read per narration, so a live switch applies. */
    public LlmNarrator(AgentInferencePort port, ResearchLanguageProvider language) {
        this.port = port;
        this.language = language == null
                ? new SessionResearchLanguage(ResearchLanguage.ENGLISH)
                : language;
    }

    @Override
    public NarrationHandle narrate(NarrationRequest request, final Callback callback) {
        AgentInferencePort.InferenceRequest inference = new AgentInferencePort.InferenceRequest(
                systemPrompt(), userPrompt(request));
        final AgentInferencePort.Cancellable cancellable = port.generate(inference,
                new AgentInferencePort.Listener() {
                    public void onThinkingDelta(String delta) {
                        callback.onThinking(delta);
                    }

                    public void onCompleted(String fullText) {
                        callback.onNarration(fullText == null ? "" : fullText.trim());
                    }

                    public void onFailed(String reason) {
                        callback.onFailure(reason);
                    }
                });
        return new NarrationHandle() {
            public void cancel() {
                cancellable.cancel();
            }
        };
    }

    private boolean de() {
        return language.currentLanguage() == ResearchLanguage.GERMAN;
    }

    /** Persona + the non-negotiable rules; stable per session language. */
    String systemPrompt() {
        if (de()) {
            return "Du bist der Recherche-Begleiter in einer Fachanwendung und führst den User durch "
                    + "eine strukturierte Recherche. Du sprichst Deutsch, per Du, warm und knapp — wie "
                    + "ein kompetenter Kollege, nicht wie ein Formular.\n\n"
                    + "Nicht verhandelbare Regeln:\n"
                    + "1. Jede Angabe unter PFLICHT muss inhaltlich unverändert vorkommen; Zahlen und "
                    + "Zitate aus DATEN übernimmst du wörtlich.\n"
                    + "2. Du erfindest nichts (keine Quellen, Zahlen oder Zusagen) und versprichst nie, "
                    + "etwas zu löschen oder rückgängig zu machen.\n"
                    + "3. Interne Bezeichner (Phasen-, Status- oder Kommandonamen) nennst du nie.\n"
                    + "4. Genau EIN Anliegen pro Nachricht; steht eine ENTSCHEIDUNG an, ist sie der "
                    + "letzte Satz — als direkte Frage.\n"
                    + "5. Halte die maximale Satzzahl ein; kein Smalltalk ohne Bezug zur Recherche.\n"
                    + "6. Wiederhole die Einstiege unter ZULETZT GESAGT nicht.\n"
                    + "Antworte NUR mit dem Nachrichtentext, ohne Anführungszeichen oder Erklärungen.";
        }
        return "You are the research companion in a professional tool, guiding the user through a "
                + "structured research. You speak warmly and concisely — like a capable colleague, not "
                + "a form.\n\n"
                + "Non-negotiable rules:\n"
                + "1. Everything under MUST CONVEY appears with its meaning unchanged; numbers and "
                + "quotes under DATA appear verbatim.\n"
                + "2. Invent nothing (no sources, numbers or commitments) and never promise to delete "
                + "or undo anything.\n"
                + "3. Never mention internal identifiers (phase, state or command names).\n"
                + "4. Exactly ONE concern per message; if a DECISION is pending, it is the last "
                + "sentence — as a direct question.\n"
                + "5. Respect the sentence budget; no small talk unrelated to the research.\n"
                + "6. Do not repeat the openings listed under RECENTLY SAID.\n"
                + "Answer with ONLY the message text, no quotes, no explanations.";
    }

    /** The order: payload rendered as labelled blocks; without payload, the fallback is the content. */
    String userPrompt(NarrationRequest request) {
        StringBuilder sb = new StringBuilder();
        NarrationPayload payload = request.getPayload();
        boolean german = de();
        if (payload != null) {
            sb.append(german ? "SITUATION: " : "SITUATION: ").append(payload.getSituation()).append('\n');
            if (!payload.getMustConvey().isEmpty()) {
                sb.append(german ? "PFLICHT:\n" : "MUST CONVEY:\n");
                for (String fact : payload.getMustConvey()) {
                    sb.append("- ").append(fact).append('\n');
                }
            }
            if (!payload.getData().isEmpty()) {
                sb.append(german ? "DATEN (wörtlich):\n" : "DATA (verbatim):\n");
                for (Map.Entry<String, String> entry : payload.getData().entrySet()) {
                    sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue())
                            .append('\n');
                }
            }
            if (payload.getExpectedDecision() != null) {
                sb.append(german ? "ENTSCHEIDUNG: " : "DECISION: ")
                        .append(payload.getExpectedDecision()).append('\n');
            }
            sb.append(german ? "MAXIMALE SÄTZE: " : "MAX SENTENCES: ")
                    .append(payload.getMaxSentences()).append('\n');
            if (!payload.getRecentUtterances().isEmpty()) {
                sb.append(german ? "ZULETZT GESAGT:\n" : "RECENTLY SAID:\n");
                for (String utterance : payload.getRecentUtterances()) {
                    sb.append("- ").append(shorten(utterance)).append('\n');
                }
            }
        }
        sb.append(german
                ? "REFERENZTEXT (gleicher Inhalt, deine Worte):\n"
                : "REFERENCE WORDING (same content, your words):\n")
                .append(request.getFallbackText()).append('\n');
        if (request.getRetryHint() != null) {
            sb.append(german
                    ? "DEIN VORHERIGER VERSUCH WAR UNGÜLTIG — behebe genau das: "
                    : "YOUR PREVIOUS ATTEMPT WAS INVALID — fix exactly this: ")
                    .append(request.getRetryHint()).append('\n');
        }
        return sb.toString();
    }

    private static String shorten(String utterance) {
        String oneLine = utterance == null ? "" : utterance.replace('\n', ' ');
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "…";
    }
}
