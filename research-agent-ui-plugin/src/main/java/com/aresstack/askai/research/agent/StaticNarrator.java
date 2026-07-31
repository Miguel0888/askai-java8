package com.aresstack.askai.research.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic {@link ResearchNarrator}: the playbook wording plus SEEDED variant rotation, so the agent
 * does not greet or prompt with the identical sentence every time. Variant 0 is exactly the playbook text
 * (the reference for tests and the fallback contract); rotation is a per-kind counter offset by the seed —
 * no randomness, fully reproducible. Facts and structure are identical across variants; only phrasing varies.
 */
public final class StaticNarrator implements ResearchNarrator {

    private final int seed;
    private final Map<String, Integer> uses = new HashMap<String, Integer>();

    public StaticNarrator() {
        this(0);
    }

    /** @param seed per-session offset into the variant rotation (0 → the playbook wording first). */
    public StaticNarrator(int seed) {
        this.seed = seed < 0 ? 0 : seed;
    }

    private static boolean de() {
        return ResearchPlaybook.getLanguage() == ResearchPlaybook.Language.GERMAN;
    }

    /** The rotating variant index for a message kind: seed + how often this kind was said already. */
    private int variant(String kind, int variantCount) {
        Integer used = uses.get(kind);
        int count = used == null ? 0 : used.intValue();
        uses.put(kind, Integer.valueOf(count + 1));
        return (seed + count) % variantCount;
    }

    @Override
    public String greeting() {
        switch (variant("greeting", 3)) {
            case 1:
                return de()
                        ? "Schön, dass du da bist! Gemeinsam bauen wir eine strukturierte Recherche auf: "
                                + "Erst schärfen wir deine Frage, dann bekommst du eine Gliederung zur "
                                + "Freigabe, und anschließend sammle ich echte Webquellen und Belege für "
                                + "dich.\n\nWomit fangen wir an — was möchtest du herausfinden?"
                        : "Great to see you! Together we build a structured research: first we sharpen "
                                + "your question, then you get an outline to approve, and after that I "
                                + "collect real web sources and evidence for you.\n\n"
                                + "Where do we start — what would you like to find out?";
            case 2:
                return de()
                        ? "Hallo! Lass uns deine Recherche Schritt für Schritt angehen: Frage klären, "
                                + "Gliederung freigeben, dann recherchiere ich Webquellen und sammle die "
                                + "Belege.\n\nErzähl mir: Was willst du herausfinden?"
                        : "Hello! Let's take your research step by step: clarify the question, approve "
                                + "the outline, then I research web sources and collect the evidence.\n\n"
                                + "Tell me: what do you want to find out?";
            default:
                return ResearchPlaybook.greeting();
        }
    }

    @Override
    public String paraphraseAndFocus(String question) {
        switch (variant("paraphrase", 3)) {
            case 1:
                return (de()
                        ? "Alles klar, darum soll es gehen:\n\n> " + question + "\n\n"
                                + "Bevor ich starte: Welche Aspekte sind dir besonders wichtig (etwa "
                                + "Architektur, Updates, Sicherheit, Alternativen)? Mit \"start\" lege "
                                + "ich direkt mit dem los, was wir haben."
                        : "Alright, this is what it's about:\n\n> " + question + "\n\n"
                                + "Before I start: which aspects matter most to you (say architecture, "
                                + "updates, security, alternatives)? Say \"start\" and I'll go with "
                                + "what we have.");
            case 2:
                return (de()
                        ? "Notiert — deine Forschungsfrage:\n\n> " + question + "\n\n"
                                + "Eine Rückfrage dazu: Soll ich bestimmte Schwerpunkte setzen (z. B. "
                                + "Architektur, Updates, Sicherheit, Alternativen)? Oder sag einfach "
                                + "\"start\", dann arbeite ich mit dem, was da ist."
                        : "Noted — your research question:\n\n> " + question + "\n\n"
                                + "One follow-up: should I set specific focus areas (e.g. architecture, "
                                + "updates, security, alternatives)? Or just say \"start\" and I'll "
                                + "work with what we have.");
            default:
                return ResearchPlaybook.paraphraseAndFocus(question);
        }
    }

    @Override
    public String summarizeAndCheck(String question, List<String> aspects) {
        if (variant("summarize", 2) == 1) {
            StringBuilder sb = new StringBuilder(de()
                    ? "Kurz zusammengefasst, wo wir stehen:\n\n"
                    : "In short, where we stand:\n\n");
            sb.append(de() ? "- Forschungsfrage: " : "- Research question: ").append(question).append('\n');
            for (String aspect : aspects) {
                sb.append(de() ? "- Schwerpunkt: " : "- Focus: ").append(aspect).append('\n');
            }
            sb.append(de()
                    ? "\nHabe ich etwas übersehen? Wenn nicht, sag einfach \"nein\" (oder \"start\") — "
                            + "dann schlage ich die Gliederung vor."
                    : "\nDid I miss anything? If not, just say \"no\" (or \"start\") and I will propose "
                            + "the outline.");
            return sb.toString();
        }
        return ResearchPlaybook.summarizeAndCheck(question, aspects);
    }

    @Override
    public String needQuestionFirst() {
        if (variant("needQuestion", 2) == 1) {
            return de()
                    ? "Ohne Frage keine Recherche — sag mir zuerst, was du herausfinden möchtest."
                    : "No research without a question — first tell me what you would like to find out.";
        }
        return de()
                ? "Ich brauche zuerst eine Forschungsfrage — was möchtest du herausfinden?"
                : "I need a research question first — what would you like to find out?";
    }

    @Override
    public String describePhase(String phaseId, String stateId, boolean hasQuestion) {
        return ResearchPlaybook.describePhase(phaseId, stateId, hasQuestion);
    }

    @Override
    public String explainOrNull(String userText, String phaseDescription) {
        return ResearchPlaybook.explain(userText, phaseDescription);
    }

    @Override
    public String refinePrompt() {
        switch (variant("refine", 3)) {
            case 1:
                return de()
                        ? "Klar — was soll ich anders angehen? Zusätzliche Aspekte, andere Suchbegriffe "
                                + "oder bestimmte Quellenarten: Sag es mir einfach."
                        : "Sure — what should I approach differently? Additional aspects, different "
                                + "search terms or particular types of sources: just tell me.";
            case 2:
                return de()
                        ? "Gern. In welche Richtung soll es gehen? Du kannst Aspekte ergänzen, "
                                + "Suchbegriffe ändern oder Quellenarten vorgeben."
                        : "Happy to. Which direction should it take? You can add aspects, change search "
                                + "terms or specify types of sources.";
            default:
                return ResearchPlaybook.refinePrompt();
        }
    }

    @Override
    public String pausedNotice() {
        if (variant("paused", 2) == 1) {
            return de()
                    ? "Alles angehalten. Sobald du wieder schreibst, geht es weiter."
                    : "Everything is on hold. As soon as you type again, we continue.";
        }
        return de()
                ? "Pausiert. Schreib einfach weiter, wenn es weitergehen soll."
                : "Paused. Just type again when you want to continue.";
    }

    @Override
    public String outcomeNarrative(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo outcome) {
        // Precision text: facts, stop reason and recommendation — no phrasing variants on purpose.
        return ResearchPlaybook.outcomeCard(outcome);
    }
}
