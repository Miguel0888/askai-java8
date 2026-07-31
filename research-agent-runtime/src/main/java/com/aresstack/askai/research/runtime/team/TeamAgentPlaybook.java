package com.aresstack.askai.research.runtime.team;

import java.util.List;

/**
 * The STABLE system knowledge the main model receives as the research TeamAgent — its role, method, limits and
 * the strict JSON output contract. This is the runtime-side realization of what the host-side
 * {@code ResearchPlaybook} always described as "the stable-context half a future LLM binding receives as system
 * knowledge": here the binding is real, so this text is the model's system prompt, not a canned reply. It never
 * contains the model's answers — only the rules the model must follow.
 */
public final class TeamAgentPlaybook {

    private TeamAgentPlaybook() {
    }

    /** The system prompt: role, method, output contract and hard rules. Language-neutral instructions. */
    public static String systemPrompt() {
        return "You are the research TeamAgent inside AskAI. You guide a user through a STRUCTURED web "
                + "research in three stages: (1) clarify WHAT they want to find out (one focused question at "
                + "a time, paraphrase what you understood), (2) propose a short outline and ask for their "
                + "approval, (3) after approval, drive real web research by proposing search queries.\n\n"
                + "You do NOT run the research yourself and you do NOT own the workflow state. A host state "
                + "machine is the only authority. Each turn you are told the current phase, run-state and the "
                + "EXACT set of commands that are currently allowed. You may propose at most one of those "
                + "commands; never invent a command name or a state, and never claim a step happened.\n\n"
                + "ALWAYS answer with a SINGLE JSON object and nothing else, matching this schema:\n"
                + "{\n"
                + "  \"assistantMessage\": string,            // required: what to say to the user, plain "
                + "language, no internal ids\n"
                + "  \"proposedCommand\": string|null,        // optional: one command from the allowed list\n"
                + "  \"scope\": { \"question\": string, \"aspects\": string[] } | null,  // your current "
                + "understanding of the research scope\n"
                + "  \"approval\": { \"requested\": boolean, \"subject\": string } | null,  // set requested "
                + "when you ask the user to approve (e.g. the outline)\n"
                + "  \"searchQueries\": string[]              // optional: only once research is approved, "
                + "varied queries for the next search\n"
                + "}\n"
                + "Keep assistantMessage warm and concise. Ask only questions that change the search "
                + "direction, the choice of sources or the result form. Do not fabricate sources or results.";
    }

    /**
     * The per-turn context (not persisted in history): the live host state, the scope the HOST has confirmed
     * so far, and — kept strictly separate — the scope the MODEL has last proposed but that nobody has
     * confirmed yet. Only the host (via the user + its state pattern) may promote a proposal to "confirmed":
     * the model is told what it proposed so it can build on it, but it must never treat its own proposal as
     * settled.
     */
    public static String stateContext(TeamAgentStateView state,
                                      String confirmedQuestion, List<String> confirmedAspects,
                                      String proposedQuestion, List<String> proposedAspects) {
        StringBuilder sb = new StringBuilder("Current research state — phase: ")
                .append(state.getPhaseId().isEmpty() ? "(unknown)" : state.getPhaseId())
                .append(", run-state: ")
                .append(state.getStateId().isEmpty() ? "(unknown)" : state.getStateId())
                .append(".\nAllowed commands right now: ").append(state.allowedCommandsLine()).append(".\n");
        appendQuestion(sb, "Confirmed research question (host-approved): ", confirmedQuestion);
        appendAspects(sb, "Confirmed focus areas (host-approved): ", confirmedAspects);
        appendQuestion(sb, "Proposed research question (awaiting host/user confirmation): ", proposedQuestion);
        appendAspects(sb, "Proposed focus areas (awaiting host/user confirmation): ", proposedAspects);
        return sb.toString();
    }

    private static void appendQuestion(StringBuilder sb, String label, String question) {
        if (question != null && !question.trim().isEmpty()) {
            sb.append(label).append(question).append('\n');
        }
    }

    private static void appendAspects(StringBuilder sb, String label, List<String> aspects) {
        if (aspects != null && !aspects.isEmpty()) {
            sb.append(label);
            for (int i = 0; i < aspects.size(); i++) {
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(aspects.get(i));
            }
            sb.append('\n');
        }
    }

    /** The bootstrap instruction that elicits the opening greeting + first scoping question. */
    public static String greetingInstruction() {
        return "The research session has just started and the user has not written anything yet. Greet the "
                + "user warmly, briefly explain that you will first clarify the research question, then "
                + "propose an outline for approval, then research real web sources — and ask your ONE opening "
                + "question about what they want to find out. Respond with the JSON object only.";
    }

    /** The single bounded-repair nudge sent when the previous answer could not be parsed. */
    public static String repairNudge() {
        return "Your previous answer could not be parsed. Respond again with ONE valid JSON object matching "
                + "the schema exactly — no prose, no code fences, nothing outside the object.";
    }

    /**
     * The single bounded-repair nudge sent when the model proposed a command the host does not allow in the
     * current state. It names the illegal command and the exact legal set, and forbids claiming any action
     * happened — so a rejected command never leaves a misleading "I started it" message behind.
     */
    public static String illegalCommandNudge(String proposedCommand, TeamAgentStateView state) {
        return "You proposed the command '" + proposedCommand + "', but the host does NOT allow it in the "
                + "current state. The only commands allowed right now are: " + state.allowedCommandsLine()
                + ". Respond again with ONE valid JSON object that either proposes one of those allowed "
                + "commands or proposes no command at all, and does NOT claim any action has already "
                + "happened.";
    }
}
