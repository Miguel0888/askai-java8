package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser;
import com.aresstack.askai.research.domain.scope.ScopeSweepOutcome;

/**
 * The typed end of ONE full scope check (sweep → advice → chooser): the sweep outcome plus — when
 * and only when the sweep was READY — the chooser's typed choice. The derived {@link Kind} is the
 * presentation switch; nothing here collapses a failed stage into "nothing found": an untrusted
 * sweep, a stale scope, a broken chooser and an honest NONE stay four different facts.
 */
public final class ScopeCheckReport {

    /** What actually happened, for presentation — each gate stays its own fact. */
    public enum Kind {
        /** The chooser picked one offered question — say it as the agent. */
        ASKED,
        /** Trusted sweep, honest NONE (model or deterministic) — never "scope complete". */
        NOTHING_TO_ASK,
        /** The sweep itself did not reach READY (its outcome status says why). */
        SWEEP_NOT_READY,
        /** READY, but the scope moved before the advice could be used — advice discarded. */
        STALE_BEFORE_ADVICE,
        /** READY, but the chooser call failed typed (its result status says why). */
        CHOICE_FAILED
    }

    private final ScopeSweepOutcome outcome;
    /** Present exactly when the sweep was READY and the advice still applied. */
    private final ScopeAdviceChooser.ChoiceResult choice;
    private final boolean staleBeforeAdvice;

    private ScopeCheckReport(ScopeSweepOutcome outcome, ScopeAdviceChooser.ChoiceResult choice,
                             boolean staleBeforeAdvice) {
        if (outcome == null) {
            throw new IllegalArgumentException("a check report always carries the sweep outcome");
        }
        this.outcome = outcome;
        this.choice = choice;
        this.staleBeforeAdvice = staleBeforeAdvice;
    }

    public static ScopeCheckReport sweepNotReady(ScopeSweepOutcome outcome) {
        return new ScopeCheckReport(outcome, null, false);
    }

    public static ScopeCheckReport staleBeforeAdvice(ScopeSweepOutcome outcome) {
        return new ScopeCheckReport(outcome, null, true);
    }

    public static ScopeCheckReport withChoice(ScopeSweepOutcome outcome,
                                              ScopeAdviceChooser.ChoiceResult choice) {
        if (choice == null) {
            throw new IllegalArgumentException("withChoice carries the chooser result");
        }
        return new ScopeCheckReport(outcome, choice, false);
    }

    public Kind getKind() {
        if (!outcome.isReady()) {
            return Kind.SWEEP_NOT_READY;
        }
        if (staleBeforeAdvice) {
            return Kind.STALE_BEFORE_ADVICE;
        }
        if (choice == null || !choice.isOk()) {
            return Kind.CHOICE_FAILED;
        }
        return choice.getDecision().getDecision()
                == ScopeAdviceChooser.AdviceDecision.Decision.ASK
                ? Kind.ASKED : Kind.NOTHING_TO_ASK;
    }

    public ScopeSweepOutcome getOutcome() {
        return outcome;
    }

    /** The chooser result — null when the sweep never reached it. */
    public ScopeAdviceChooser.ChoiceResult getChoice() {
        return choice;
    }
}
