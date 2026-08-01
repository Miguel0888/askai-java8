package com.aresstack.askai.research.runtime.team;

/** The SCOPING phase contract: {@link ScopingAssistantOutputParser} producing a {@link ScopingAssistantOutput}. */
public final class ScopingPhaseOutputContract implements PhaseOutputContract {

    public PhaseParseResult parse(String rawModelText) {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(rawModelText);
        return result.isOk()
                ? PhaseParseResult.ok(result.getOutput())
                : PhaseParseResult.fail(result.getError());
    }
}
