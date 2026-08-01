package com.aresstack.askai.research.runtime.team;

/**
 * A phase's structured-output semantics: how the raw model text for THIS phase is parsed and validated into a
 * {@link PhaseAssistantOutput}. Selected together with the system prompt by the phase profile (RA-P6 §6), so
 * "active phase → prompt AND parser" is one decision at one point — never a scattered {@code if("scoping")}.
 */
public interface PhaseOutputContract {

    /** Parse+validate one raw model answer for this phase into a typed output, or a typed failure reason. */
    PhaseParseResult parse(String rawModelText);
}
