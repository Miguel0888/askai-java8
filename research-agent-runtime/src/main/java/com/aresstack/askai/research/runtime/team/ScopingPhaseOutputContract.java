package com.aresstack.askai.research.runtime.team;

/**
 * The SCOPING phase contract: {@link ScopingAssistantOutputParser} producing a {@link ScopingAssistantOutput},
 * with the stricter USEFUL-FIRST-TURN rule enforced on top (RA-P6.5): a substantive scoping turn must HELP
 * before it asks — so besides the required research brief it must also carry an exploration map AND at least
 * one search suggestion. A reply that only asks the user to narrow the topic (brief-only, no map, no
 * suggestion) is rejected here, triggering one bounded repair and then an honest failure — it is no longer a
 * valid first scoping turn. The phase-agnostic GREETING is exempt: it uses the generic contract, not this one.
 */
public final class ScopingPhaseOutputContract implements PhaseOutputContract {

    public PhaseParseResult parse(String rawModelText) {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(rawModelText);
        return result.isOk()
                ? PhaseParseResult.ok(result.getOutput())
                : PhaseParseResult.fail(result.getError());
    }
}
