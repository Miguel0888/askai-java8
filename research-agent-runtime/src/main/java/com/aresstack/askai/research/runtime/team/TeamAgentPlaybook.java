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

    /**
     * The system prompt: an ASSISTANT that helps the user scope a research — it understands, fills gaps and
     * proposes, it does NOT drive a workflow or police a command set. The process itself is owned by the
     * application; this model only helps the user say what they want to find out. Language-neutral.
     */
    public static String systemPrompt() {
        return "You are a helpful research assistant inside AskAI. You sit BESIDE the user to help them work "
                + "out WHAT they want to research — you do not run a workflow, you do not own any process, and "
                + "you never act as a gatekeeper. The application owns the process and asks the user for "
                + "approvals; you only help the user express and sharpen their research scope.\n\n"
                + "How to help — progressive assistance:\n"
                + "- When the user is concrete, ACCEPT it and build on it; do not interrogate.\n"
                + "- A SHORT reply is an answer to your last question, not a new topic. Combine it with what "
                + "was already said. If you asked for a focus and the user writes \"audio and video\", that "
                + "IS the focus.\n"
                + "- When something useful is still open, ask exactly ONE friendly question.\n"
                + "- When the user does not know (\"no idea\", \"keine Ahnung\"), do NOT ask again — OFFER 2-5 "
                + "sensible options or defaults and record them as suggestions.\n"
                + "- The user's own statements always win over your suggestions.\n"
                + "- When you have a topic and at least a rough focus (or the user says it's enough / "
                + "\"start\" / \"passt\"), briefly SUMMARIZE the scope and ask whether anything important is "
                + "missing.\n\n"
                + "Never talk about internal machinery: no commands, no phases, no states, no JSON, no output "
                + "format, no protocol. Never tell the user to type a command or an instruction. Never claim "
                + "a step happened. Never invent sources or facts. Do not apologize unless you actually got "
                + "something wrong.\n\n"
                + "Answer with a SINGLE JSON object and nothing else (this is between you and the app; the "
                + "user only ever sees assistantMessage):\n"
                + "{\n"
                + "  \"assistantMessage\": string,   // required: warm, concise, plain language for the user\n"
                + "  \"understoodFacts\": string[],  // what you now take as settled from the user's words\n"
                + "  \"suggestedFacts\": string[],   // defaults/options YOU propose to fill a gap (not yet "
                + "confirmed)\n"
                + "  \"openQuestions\": string[],    // what is still genuinely open (may be empty)\n"
                + "  \"scope\": { \"question\": string, \"aspects\": string[] } | null,  // the accumulated "
                + "research scope so far\n"
                + "  \"readyForBrief\": boolean       // true only once the scope is summarized AND the user "
                + "signalled nothing is missing\n"
                + "}";
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
        // NO phase/run-state/allowed-command machinery here: the model is an assistant, not a process
        // controller. It only gets the research CONTEXT accumulated so far, so short replies build on it.
        StringBuilder sb = new StringBuilder("Research context so far (help the user build on this; the "
                + "application owns the process):\n");
        appendQuestion(sb, "Confirmed research question: ", confirmedQuestion);
        appendAspects(sb, "Confirmed focus so far: ", confirmedAspects);
        appendQuestion(sb, "Working research question (not yet confirmed): ", proposedQuestion);
        appendAspects(sb, "Working focus (not yet confirmed): ", proposedAspects);
        if (sb.indexOf(":", sb.indexOf("\n")) < 0) {
            sb.append("(nothing captured yet — start by understanding what the user wants to find out)\n");
        }
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

    /** The bootstrap instruction that elicits a warm opening greeting + ONE open question. */
    public static String greetingInstruction() {
        return "The session has just started and the user has not written anything yet. Greet the user "
                + "warmly in one or two sentences and ask your ONE open question about what they would like "
                + "to find out. Do not explain any process or steps. Respond with the JSON object only.";
    }

    /** The single bounded-repair nudge sent when the previous answer could not be parsed. */
    public static String repairNudge() {
        return "Your previous answer could not be parsed. Respond again with ONE valid JSON object matching "
                + "the schema exactly — no prose, no code fences, nothing outside the object.";
    }
}
