# Research state machine (hierarchical OO)

The research lifecycle is a nested object-oriented state pattern — **no external state-machine library**, no
central switch. Free (phase, runState) combinations are no longer constructible.

```
ResearchSession (driven via the ResearchStateMachine port)
└── ResearchPhaseState        (a phase + its current inner state)
    └── PhaseState            (running / waiting / approval gate / interruption / terminal)
```

## Phases and sub-states (matrix)

| Phase | Base sub-states | Phase commands → target |
|-------|-----------------|--------------------------|
| SCOPING | new, running | `START`: new→running · `SUBMIT_SCOPE`: running→OUTLINE/running |
| OUTLINE | running, waiting_approval | `PROPOSE_OUTLINE`: running→waiting_approval · `APPROVE_OUTLINE`→RESEARCH/waiting · `REQUEST_OUTLINE_CHANGES`→running |
| RESEARCH | waiting, running | `START_RESEARCH`: waiting→running · `REQUEST_EVIDENCE_REVIEW`→EVIDENCE/waiting_approval |
| EVIDENCE | waiting_approval | `APPROVE_EVIDENCE`→DRAFT/waiting · `REQUEST_REVISION`→RESEARCH/running |
| DRAFT | waiting, running | `START_DRAFTING`: waiting→running · `REQUEST_DRAFT_REVIEW`→REVIEW/waiting_approval |
| REVIEW | waiting_approval | `APPROVE_DRAFT`→FINALIZATION/running · `REQUEST_REVISION`→DRAFT/running |
| FINALIZATION | running, waiting_approval, completed | `REQUEST_FINAL_REVIEW`: running→waiting_approval · `APPROVE_FINAL`→completed |

`waiting_approval` is a human approval gate (carries `pendingApprovalId`, `requiresApproval() == true`); the
agent may reach it and request changes but never self-approves. `waiting` is a "ready to start the next step"
gate. Both map to the legacy `WAITING_FOR_USER`.

## Global / interruption commands

Intrinsic to the inner states, valid in any phase where allowed:

- `PAUSE` (from running) → `PausedState(continuation)`; `RESUME` → continuation.
- `BLOCK` (from running/waiting/approval) → `BlockedState(continuation)`; `UNBLOCK` → continuation.
- `FAIL` (non-terminal) → `FailedState(continuation)`; `RETRY` → continuation.
- `CANCEL` (non-terminal) → `cancelled` (terminal).

Interruptions remember the exact continuation, so e.g. *blocked from an approval gate → unblock returns to
the gate*. Terminal states (`completed`, `cancelled`) reject everything.

## Commands are one source of truth

`PhaseState.getAllowedCommands()` is the single source for slash-command gating, future UI buttons and the
State visualization. There is no separate enablement table in the UI.

## Revisions & events

- Accepted transition: revision exactly `+1`, one `SessionStateChanged` event (id, revision, timestamp).
- Rejection: no state/revision change; returned as a result, never an exception.
- Approval gates cannot be skipped.
- Late backend events are dropped by sequence number.

## Persistence (memento)

Only stable ids are persisted (never state objects):

```json
{ "phaseId": "research", "stateId": "paused", "continuationStateId": "running",
  "revision": 17, "pendingApprovalId": null }
```

`ResearchStateFactory.restore(memento)` rebuilds the object graph and **rejects** illegal combinations.
`snapshot(phase, revision)` produces the memento.

## Port compatibility

`DefaultResearchStateMachine` is a thin facade over the OO model behind the unchanged
`ResearchStateMachine` port (it maps the legacy `ResearchSessionState` pair to/from the OO state each
dispatch). The legacy pair cannot carry a precise continuation, so a reconstructed interruption continues
into the phase's working state; the native OO model (and the memento) keep the exact continuation.
