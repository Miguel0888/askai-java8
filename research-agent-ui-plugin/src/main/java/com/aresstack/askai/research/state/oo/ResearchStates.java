package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The concrete phase/state objects of the OO model. Each inner state owns exactly the commands it accepts:
 * forward (phase-progressing) commands come from the {@link ResearchStateGraph}; interruption/global commands
 * (pause/block/fail/cancel and resume/unblock/retry) are intrinsic to the specific state. There is no central
 * transition switch — a command with no matching rule in the current state is rejected.
 */
final class ResearchStates {

    private ResearchStates() {
    }

    // ------------------------------------------------------------------ phase

    static final class Phase implements ResearchPhaseState {
        private final String phaseId;
        private final PhaseState currentState;

        Phase(String phaseId, PhaseState currentState) {
            this.phaseId = phaseId;
            this.currentState = currentState;
        }

        public String getPhaseId() {
            return phaseId;
        }

        public PhaseState getCurrentState() {
            return currentState;
        }

        public Set<ResearchCommandType> getAllowedCommands() {
            return currentState.getAllowedCommands();
        }

        public OoTransition handle(ResearchStateContext context, ResearchCommand command) {
            return currentState.handle(context, this, command);
        }

        public ResearchPhaseState withState(PhaseState newState) {
            return new Phase(phaseId, newState);
        }
    }

    // ------------------------------------------------------------------ base

    abstract static class Base implements PhaseState {
        final String phaseId;
        private final String stateId;

        Base(String phaseId, String stateId) {
            this.phaseId = phaseId;
            this.stateId = stateId;
        }

        public String getStateId() {
            return stateId;
        }

        public boolean isTerminal() {
            return false;
        }

        public boolean requiresApproval() {
            return false;
        }

        public String getPendingApprovalId() {
            return null;
        }

        public String getContinuationStateId() {
            return null;
        }

        public Set<ResearchCommandType> getAllowedCommands() {
            Set<ResearchCommandType> commands = new LinkedHashSet<ResearchCommandType>(
                    ResearchStateGraph.forwardCommands(phaseId, stateId));
            commands.addAll(intrinsicCommands());
            return Collections.unmodifiableSet(commands);
        }

        public OoTransition handle(ResearchStateContext context, ResearchPhaseState phase,
                                   ResearchCommand command) {
            OoTransition forward = tryForward(context, phase, command);
            if (forward != null) {
                return forward;
            }
            return handleIntrinsic(context, phase, command);
        }

        abstract Set<ResearchCommandType> intrinsicCommands();

        abstract OoTransition handleIntrinsic(ResearchStateContext context, ResearchPhaseState phase,
                                              ResearchCommand command);

        static OoTransition tryForward(ResearchStateContext ctx, ResearchPhaseState phase,
                                       ResearchCommand command) {
            ResearchStateGraph.Edge edge = ResearchStateGraph.forward(
                    phase.getPhaseId(), phase.getCurrentState().getStateId(), command.getType());
            if (edge == null) {
                return null;
            }
            String approvalId = ResearchStateIds.WAITING_APPROVAL.equals(edge.targetStateId)
                    ? ctx.newApprovalId() : null;
            PhaseState target = ctx.getFactory().state(
                    edge.targetPhaseId, edge.targetStateId, null, approvalId);
            return OoTransition.accepted(ctx.getFactory().phase(edge.targetPhaseId, target));
        }

        static OoTransition cancel(ResearchStateContext ctx, ResearchPhaseState phase) {
            return OoTransition.accepted(phase.withState(
                    ctx.getFactory().state(phase.getPhaseId(), ResearchStateIds.CANCELLED, null, null)));
        }

        /**
         * Interrupt into paused/blocked/failed, remembering the current state as the continuation AND preserving
         * the pending approval id when interrupting an approval gate, so resuming restores the exact same gate.
         */
        static OoTransition interrupt(ResearchStateContext ctx, ResearchPhaseState phase, String interruptId) {
            PhaseState current = phase.getCurrentState();
            String continuation = current.getStateId();
            String approvalId = current.getPendingApprovalId(); // non-null only when interrupting an approval gate
            return OoTransition.accepted(phase.withState(
                    ctx.getFactory().state(phase.getPhaseId(), interruptId, continuation, approvalId)));
        }

        static OoTransition reject(ResearchCommand command, ResearchPhaseState phase) {
            return OoTransition.rejected(command.getType() + " is not allowed in "
                    + phase.getPhaseId() + "/" + phase.getCurrentState().getStateId() + ".");
        }
    }

    /**
     * Resume/unblock/retry back into the stored continuation state. When the continuation is an approval gate the
     * <em>original</em> approval id (carried on the interruption) is restored, not a freshly-generated one, so an
     * approval that was blocked/paused/failed and then continued is exactly the same gate.
     */
    private static OoTransition continueInto(ResearchStateContext ctx, ResearchPhaseState phase,
                                             String continuationStateId) {
        String approvalId = null;
        if (ResearchStateIds.WAITING_APPROVAL.equals(continuationStateId)) {
            String preserved = phase.getCurrentState().getPendingApprovalId();
            approvalId = preserved != null ? preserved : ctx.newApprovalId();
        }
        return OoTransition.accepted(phase.withState(
                ctx.getFactory().state(phase.getPhaseId(), continuationStateId, null, approvalId)));
    }

    private static Set<ResearchCommandType> set(ResearchCommandType... types) {
        Set<ResearchCommandType> s = new LinkedHashSet<ResearchCommandType>();
        Collections.addAll(s, types);
        return Collections.unmodifiableSet(s);
    }

    // ------------------------------------------------------------------ concrete states

    static final class NewState extends Base {
        NewState(String phaseId) {
            super(phaseId, ResearchStateIds.NEW);
        }

        Set<ResearchCommandType> intrinsicCommands() {
            return set(ResearchCommandType.FAIL, ResearchCommandType.CANCEL);
        }

        OoTransition handleIntrinsic(ResearchStateContext ctx, ResearchPhaseState phase, ResearchCommand c) {
            if (c.getType() == ResearchCommandType.CANCEL) {
                return cancel(ctx, phase);
            }
            if (c.getType() == ResearchCommandType.FAIL) {
                return interrupt(ctx, phase, ResearchStateIds.FAILED);
            }
            return reject(c, phase);
        }
    }

    static final class RunningState extends Base {
        RunningState(String phaseId) {
            super(phaseId, ResearchStateIds.RUNNING);
        }

        Set<ResearchCommandType> intrinsicCommands() {
            return set(ResearchCommandType.PAUSE, ResearchCommandType.BLOCK,
                    ResearchCommandType.FAIL, ResearchCommandType.CANCEL);
        }

        OoTransition handleIntrinsic(ResearchStateContext ctx, ResearchPhaseState phase, ResearchCommand c) {
            switch (c.getType()) {
                case PAUSE: return interrupt(ctx, phase, ResearchStateIds.PAUSED);
                case BLOCK: return interrupt(ctx, phase, ResearchStateIds.BLOCKED);
                case FAIL: return interrupt(ctx, phase, ResearchStateIds.FAILED);
                case CANCEL: return cancel(ctx, phase);
                default: return reject(c, phase);
            }
        }
    }

    /** A "ready to start the next step" gate (RESEARCH, DRAFT). Not an approval. */
    static final class WaitingState extends Base {
        WaitingState(String phaseId) {
            super(phaseId, ResearchStateIds.WAITING);
        }

        Set<ResearchCommandType> intrinsicCommands() {
            return set(ResearchCommandType.BLOCK, ResearchCommandType.FAIL, ResearchCommandType.CANCEL);
        }

        OoTransition handleIntrinsic(ResearchStateContext ctx, ResearchPhaseState phase, ResearchCommand c) {
            switch (c.getType()) {
                case BLOCK: return interrupt(ctx, phase, ResearchStateIds.BLOCKED);
                case FAIL: return interrupt(ctx, phase, ResearchStateIds.FAILED);
                case CANCEL: return cancel(ctx, phase);
                default: return reject(c, phase);
            }
        }
    }

    /** A human approval gate. The agent may reach it and request changes, but never self-approve. */
    static final class WaitingApprovalState extends Base {
        private final String pendingApprovalId;

        WaitingApprovalState(String phaseId, String pendingApprovalId) {
            super(phaseId, ResearchStateIds.WAITING_APPROVAL);
            this.pendingApprovalId = pendingApprovalId;
        }

        @Override
        public boolean requiresApproval() {
            return true;
        }

        @Override
        public String getPendingApprovalId() {
            return pendingApprovalId;
        }

        Set<ResearchCommandType> intrinsicCommands() {
            return set(ResearchCommandType.BLOCK, ResearchCommandType.FAIL, ResearchCommandType.CANCEL);
        }

        OoTransition handleIntrinsic(ResearchStateContext ctx, ResearchPhaseState phase, ResearchCommand c) {
            switch (c.getType()) {
                case BLOCK: return interrupt(ctx, phase, ResearchStateIds.BLOCKED);
                case FAIL: return interrupt(ctx, phase, ResearchStateIds.FAILED);
                case CANCEL: return cancel(ctx, phase);
                default: return reject(c, phase);
            }
        }
    }

    abstract static class Interruption extends Base implements InterruptingPhaseState {
        private final String continuationStateId;
        private final String pendingApprovalId; // preserved when the continuation is an approval gate

        Interruption(String phaseId, String stateId, String continuationStateId, String pendingApprovalId) {
            super(phaseId, stateId);
            this.continuationStateId = continuationStateId;
            this.pendingApprovalId = pendingApprovalId;
        }

        @Override
        public String getContinuationStateId() {
            return continuationStateId;
        }

        @Override
        public String getPendingApprovalId() {
            return pendingApprovalId;
        }

        public PhaseState getContinuationState() {
            return new FactoryHolder().factory.state(phaseId, continuationStateId, null, pendingApprovalId);
        }
    }

    static final class PausedState extends Interruption {
        PausedState(String phaseId, String continuationStateId, String pendingApprovalId) {
            super(phaseId, ResearchStateIds.PAUSED, continuationStateId, pendingApprovalId);
        }

        Set<ResearchCommandType> intrinsicCommands() {
            return set(ResearchCommandType.RESUME, ResearchCommandType.FAIL, ResearchCommandType.CANCEL);
        }

        OoTransition handleIntrinsic(ResearchStateContext ctx, ResearchPhaseState phase, ResearchCommand c) {
            switch (c.getType()) {
                case RESUME: return continueInto(ctx, phase, getContinuationStateId());
                case FAIL: return OoTransition.accepted(phase.withState(
                        ctx.getFactory().state(phase.getPhaseId(), ResearchStateIds.FAILED,
                                getContinuationStateId(), getPendingApprovalId())));
                case CANCEL: return cancel(ctx, phase);
                default: return reject(c, phase);
            }
        }
    }

    static final class BlockedState extends Interruption {
        BlockedState(String phaseId, String continuationStateId, String pendingApprovalId) {
            super(phaseId, ResearchStateIds.BLOCKED, continuationStateId, pendingApprovalId);
        }

        Set<ResearchCommandType> intrinsicCommands() {
            return set(ResearchCommandType.UNBLOCK, ResearchCommandType.FAIL, ResearchCommandType.CANCEL);
        }

        OoTransition handleIntrinsic(ResearchStateContext ctx, ResearchPhaseState phase, ResearchCommand c) {
            switch (c.getType()) {
                case UNBLOCK: return continueInto(ctx, phase, getContinuationStateId());
                case FAIL: return OoTransition.accepted(phase.withState(
                        ctx.getFactory().state(phase.getPhaseId(), ResearchStateIds.FAILED,
                                getContinuationStateId(), getPendingApprovalId())));
                case CANCEL: return cancel(ctx, phase);
                default: return reject(c, phase);
            }
        }
    }

    static final class FailedState extends Interruption {
        FailedState(String phaseId, String continuationStateId, String pendingApprovalId) {
            super(phaseId, ResearchStateIds.FAILED, continuationStateId, pendingApprovalId);
        }

        Set<ResearchCommandType> intrinsicCommands() {
            return set(ResearchCommandType.RETRY, ResearchCommandType.CANCEL);
        }

        OoTransition handleIntrinsic(ResearchStateContext ctx, ResearchPhaseState phase, ResearchCommand c) {
            switch (c.getType()) {
                case RETRY: return continueInto(ctx, phase, getContinuationStateId());
                case CANCEL: return cancel(ctx, phase);
                default: return reject(c, phase);
            }
        }
    }

    static final class TerminalState extends Base {
        TerminalState(String phaseId, String stateId) {
            super(phaseId, stateId);
        }

        @Override
        public boolean isTerminal() {
            return true;
        }

        Set<ResearchCommandType> intrinsicCommands() {
            return Collections.emptySet();
        }

        OoTransition handleIntrinsic(ResearchStateContext ctx, ResearchPhaseState phase, ResearchCommand c) {
            return reject(c, phase);
        }
    }

    /** Bridges {@link Interruption#getContinuationState()} to the singleton factory without a ctx. */
    private static final class FactoryHolder {
        private final ResearchStateFactory factory = ResearchStateFactory.getInstance();
    }
}
