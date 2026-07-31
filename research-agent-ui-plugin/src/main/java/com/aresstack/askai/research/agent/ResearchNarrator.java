package com.aresstack.askai.research.agent;

/**
 * The narration seam: every CONVERSATIONAL milestone text the agent says to the user goes through this
 * interface. {@link StaticNarrator} phrases them deterministically from the {@link ResearchPlaybook};
 * an LLM-backed narrator can rephrase them warmly later — the callers and the state machine never notice.
 *
 * <p>Deliberately NOT on this interface: machinery and precision texts (progress lines, action labels,
 * CAPTCHA notices, {@code limitationRecorded}) — they stay on the playbook, exact wording is their point.</p>
 */
public interface ResearchNarrator {

    /** First contact: friendly, takes initiative, ONE open question — never an approval. */
    String greeting();

    /** Echo-based paraphrase of the user's question + ONE focused follow-up. */
    String paraphraseAndFocus(String question);

    /** Scope summary + the explicit missing-anything check. */
    String summarizeAndCheck(String question, java.util.List<String> aspects);

    /** The honest push-back when the user says "start" without a researchable question. */
    String needQuestionFirst();

    /** Human wording for the current situation (no internal identifiers). */
    String describePhase(String phaseId, String stateId, boolean hasQuestion);

    /** A playbook answer for meta questions ("what can you do"), or {@code null} for normal content. */
    String explainOrNull(String userText, String phaseDescription);

    /** The focused follow-up when the user chose to refine the research scope. */
    String refinePrompt();

    /** Visible confirmation after a pause. */
    String pausedNotice();

    /** The prose of the run outcome card (facts and recommendations; actions are typed separately). */
    String outcomeNarrative(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo outcome);
}
