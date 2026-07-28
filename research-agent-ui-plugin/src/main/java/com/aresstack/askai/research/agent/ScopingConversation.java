package com.aresstack.askai.research.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The consultative scoping dialog (open question → paraphrase + focused question → summary →
 * "anything missing?" → confirmation), deterministic and host-side: the STRUCTURE of a good consulting
 * conversation without pretending semantic understanding (paraphrases are honest mirrors of the user's own
 * words). A future LLM slots in behind the SAME seam ({@link #next}) to make the questions and paraphrases
 * genuinely adaptive; the flow, the state machine and the UI stay untouched.
 */
public final class ScopingConversation {

    /** One dialog step: what the agent says, and whether scoping is complete. */
    public static final class Reply {
        public final String text;
        public final boolean scopingComplete;

        Reply(String text, boolean scopingComplete) {
            this.text = text;
            this.scopingComplete = scopingComplete;
        }
    }

    private enum Stage { AWAITING_QUESTION, CLARIFYING, CONFIRMING, DONE }

    private Stage stage = Stage.AWAITING_QUESTION;
    private String question = "";
    private final List<String> aspects = new ArrayList<String>();

    public String getQuestion() {
        return question;
    }

    public List<String> getAspects() {
        return Collections.unmodifiableList(aspects);
    }

    public boolean isComplete() {
        return stage == Stage.DONE;
    }

    /** Advance the dialog with the user's message; returns the agent's reply. */
    public Reply next(String userText) {
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty()) {
            return new Reply(ResearchPlaybook.greeting(), false);
        }
        switch (stage) {
            case AWAITING_QUESTION:
                question = text;
                if (ResearchPlaybook.isStartRequest(text)) {
                    // "start" without a question is not researchable — keep asking, honestly.
                    question = "";
                    return new Reply("I need a research question first — what would you like to "
                            + "find out?", false);
                }
                stage = Stage.CLARIFYING;
                return new Reply(ResearchPlaybook.paraphraseAndFocus(question), false);
            case CLARIFYING:
                if (!ResearchPlaybook.isConfirmation(text)) {
                    aspects.add(text);
                }
                stage = Stage.CONFIRMING;
                if (ResearchPlaybook.isStartRequest(text)) {
                    stage = Stage.DONE;
                    return new Reply(null, true); // the caller proposes the outline now
                }
                return new Reply(ResearchPlaybook.summarizeAndCheck(question, aspects), false);
            case CONFIRMING:
                if (ResearchPlaybook.isConfirmation(text)) {
                    stage = Stage.DONE;
                    return new Reply(null, true);
                }
                aspects.add(text); // the user added something — work it in and re-check
                return new Reply(ResearchPlaybook.summarizeAndCheck(question, aspects), false);
            default:
                return new Reply(null, true);
        }
    }

    /** The outline derived from the CONFIRMED scope (question + collected aspects). */
    public String buildOutlineMarkdown() {
        StringBuilder sb = new StringBuilder("# Outline — ").append(question).append("\n\n");
        sb.append("1. Background\n");
        int index = 2;
        for (String aspect : aspects) {
            sb.append(index++).append(". ").append(aspect).append('\n');
        }
        sb.append(index++).append(". Evidence from web research\n");
        sb.append(index).append(". Conclusions\n");
        return sb.toString();
    }

    /** The concept document for the confirmed scope. */
    public String buildConceptMarkdown() {
        StringBuilder sb = new StringBuilder("# Concept\n\nResearch question:\n\n> ")
                .append(question).append('\n');
        if (!aspects.isEmpty()) {
            sb.append("\nConfirmed focus:\n");
            for (String aspect : aspects) {
                sb.append("- ").append(aspect).append('\n');
            }
        }
        return sb.toString();
    }
}
