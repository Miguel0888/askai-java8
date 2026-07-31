# Implementation Plan: LLM Narrator (soft user guidance)

Goal: keep the deterministic state machine (phases, approval gates, typed commands) exactly as it is and
put a **replaceable narration layer** on top of it. The layer translates hard state milestones into warm,
varying conversation. An LLM formulates the text; a deterministic validator guards the content; on any
failure (timeout, validation, model absent) the flow falls back to the static playbook wording with an
identical visible lifecycle. The user never sees which path produced a message.

Design principles (agreed):

- **Color/act semantics stay:** agent color = the agent *says* something (static or LLM — irrelevant),
  yellow = tool machinery, thought bubble = ephemeral thinking. Never color by generation mechanism.
- **Action cards stay deterministic and instant.** Narration is additive and asynchronous; an expert can
  act before the narration arrives (fast track). Buttons/labels (`actionLabel`) are never LLM text.
- **Facts are delivered as data, not as text to paraphrase** (data-to-text). Mandatory content is
  validated; numbers/quotes/commitments must survive verbatim.
- **Local models are a serial bottleneck resource:** every narration call is cancellable
  (`Task.cancel()`-style) *and* guarded by a generation counter (stale results are dropped).
- Deterministic-first, like the rest of the plugin: every layer is proven with fakes before the real
  model is plugged in (`FakeResearchSessionBackend` precedent).

Out of scope here (follow-up phases, see Outlook): NL intent mapping onto typed commands
(constrained tool calling with `getAllowedCommands()` + approval nonce), speculative pre-generation.

---

## Step 0 — Extract the narrator seam (pure refactor, no behavior change)

**New:** `research-agent-ui-plugin/.../agent/ResearchNarrator.java` (interface) and
`StaticNarrator.java` (first implementation, delegates to `ResearchPlaybook`).

The interface covers only the **narratable milestone texts** — the conversational moments:

- `greeting()`
- `paraphraseAndFocus(question)`
- `summarizeAndCheck(question, aspects)`
- `describePhase(phaseId, stateId, hasQuestion)`
- `explainOrNull(userText, phaseDescription)`
- `refinePrompt()`
- `outcomeNarrative(ResearchRunOutcomeInfo)` (the prose of the outcome card)

**Deliberately NOT on the interface** (stay static on `ResearchPlaybook`, they are machinery/precision
texts): `progressTitle/Line/SearchLine/PageLine`, history lines, `actionLabel`, `attentionRequired/
Resolved`, `limitationRecorded`, `headlessHint`, `isConfirmation/isStartRequest`, language handling.

**Call-site migration** (all currently static calls):

- `ResearchAgentSession` (`ResearchAgentSession.java:137, 229–231, 342, 490, 868, 901, 912` and the
  outcome-card prose at `:734`) — receives a `ResearchNarrator` via constructor.
- `ScopingConversation` (`ScopingConversation.java:49, 61, 71, 78`) — narrator constructor parameter;
  the `isConfirmation`/`isStartRequest` checks keep calling `ResearchPlaybook` directly.
- `FakeResearchSessionBackend.java:54` — takes the narrator (or keeps playbook; fake-only).
- Wiring in `ResearchAgentSessionFactory` (which already does `ResearchPlaybook.setLanguage`, `:48`).

**Warmth quick win (ships with Step 0):** `StaticNarrator` holds 3–4 variants per narratable text and
rotates them **seeded per session** (simple counter, no `Random`) so tests stay deterministic. Existing
tests that assert exact greeting text (`ResearchRuntimeModeTest.java:236, 241`,
`ResearchRunCardsTest`) switch to asserting "one of the variant set" or pin variant 0 via a test seed.

**Done when:** all tests green, no visible behavior change except variant rotation.

## Step 1 — Async narration lifecycle + stale guard (proven with a fake, no LLM)

**New:** `NarrationCoordinator` (owned by `ResearchAgentSession`), plus:

- `NarrationRequest` — activityId, `NarrationPayload` (Step 2; initially just the static text),
  fallback text, static thinking-bubble title.
- `NarrationHandle` — `cancel()`, idempotent (mirrors `PromptHandle`/`OllamaService.Task`).
- `AsyncNarrator` interface: `NarrationHandle narrate(NarrationRequest, NarrationCallback)`.

**Visible lifecycle (single code path for LLM, fallback and fake):**

1. State transition happens immediately and deterministically; if the milestone shows an action card,
   the card renders **now** (facts + buttons, unchanged).
2. Same UI tick: `sink.startThinking(activityId, <static rotating title>)` (the thought bubble; the
   session already routes thinking events at `ResearchAgentSession.java:948–954`).
3. Result arrives (off-thread) → marshal via `UiExecutor` → **generation check** → `finishThinking` +
   `appendAssistantMessage` (exactly once; never replace already-shown text).
4. Card consumed / state changed / session closed before the result:
   `handle.cancel()` → bump generation counter → `finishThinking(activityId, short static summary)`
   so no bubble is left "thinking" forever. Late callbacks hit the bumped generation and are dropped
   before reaching the sink.

**Stale guard:** a single `int narrationGeneration` in the session, incremented on every accepted
state-changing dispatch and on card consumption; every callback carries the generation it was created
with (the `PromptDispatcher` exactly-one-terminal pattern, scoped down).

**Timeout:** via the existing `ResearchScheduler` port (`RealResearchScheduler` in production),
budget ~6 s; timeout behaves exactly like a validation failure → fallback text, same lifecycle.

**Test double:** `DelayingFakeNarrator` (configurable delay/never-answers/answers-after-cancel).
Tests to write (all deterministic, using the fake scheduler):

- answer arrives normally → bubble closes, one assistant message;
- user clicks the card before the answer → cancel called, bubble closes silently, late answer dropped;
- timeout → fallback text posted, lifecycle identical;
- session closed mid-narration → nothing reaches the sink;
- two milestones back-to-back → second narration never receives the first one's answer.

## Step 2 — Payload, deterministic validator, fallback

**New:** `NarrationPayload`, `NarrationValidator` (pure functions, no I/O).

Payload (built by the session from state + playbook — the playbook stays the source of truth):

- `situation` (human wording of the state, from `describePhase`),
- `mustConvey` (list of mandatory facts as short sentences, e.g. "After approval the web research
  starts automatically"),
- `data` (numbers/quotes: source counts, the user's question verbatim),
- `expectedDecision` (nullable, e.g. "approve the outline or request changes"),
- `maxSentences`, `language`,
- `recentUtterances` (last 2–3 narrator outputs, ring buffer in the session).

Validator rules (each is a cheap `String`/regex check, Java 8 friendly):

1. every number and quoted string from `data` appears verbatim;
2. sentence count ≤ `maxSentences`;
3. if `expectedDecision` is set, the message ends with a question;
4. forbidden vocabulary absent: internal phase/state ids (`ResearchStateIds` values), command names,
   "delete/löschen"-style commitments;
5. non-empty, right language (cheap heuristic; skip if unreliable).

Policy: **one retry** with the violation appended to the prompt, then fallback. The validator and the
retry/fallback decision live in the coordinator, not in the LLM adapter — they are the invariant that
prompt iterations are tested against.

Tests: crafted bad outputs (missing number, too long, no closing question, leaked state id) must each
fall back; good output passes unchanged.

## Step 3 — The real `LlmNarrator`

**Architecture decision needed first:** `OllamaService` (streaming + `Task.cancel()`,
`DefaultOllamaService.java:249–272`) lives in `askai-app`; the plugin must not depend on the app.
Introduce a narrow port in `agent-workspace-api` (next to `UiExecutor`), e.g.

```java
public interface AgentInferencePort {
    Cancellable generate(InferenceRequest request, InferenceListener listener); // streaming
}
```

implemented in `agent-plugin-host`/`askai-app` by delegating to the existing `OllamaService`
(reusing its executor, daemon threads and `Future.cancel(true)` interruption), exposed to the plugin
via `AgentHostContext`. This keeps Java 8, adds no new dependency, and reuses the proven cancel path.
(Alternative — new `solon-ai` client in the plugin — rejected: second HTTP/LLM stack for no gain.)

`LlmNarrator implements AsyncNarrator`:

- System prompt (stable per session): persona, the non-negotiable rules (mandatory facts verbatim,
  invent nothing, never promise deletion, no internal terms, ONE concern per message, decision = last
  sentence, sentence cap, do not repeat `recentUtterances` openings). Rendered in the session language.
- Per-call user message: the `NarrationPayload` rendered as a compact JSON-like block.
- Temperature ~0.6; variation comes from context + `recentUtterances`, not from heat.
- Model thinking (if the configured model streams it): forward trimmed deltas to
  `updateThinking(activityId, …)` — the bubble first shows the static title, then live thinking.
  Never forward raw text unfiltered beyond a length cap.
- Cancellation: `handle.cancel()` → `Cancellable.cancel()` (interrupts the HTTP call, frees the local
  GPU/CPU for reranker/research) *plus* the Step 1 generation guard for correctness.

**Settings:** a toggle in `ResearchRuntimeSettingsPanel` ("AI-phrased guidance" on/off; default off
until burned in). Off or model unreachable → factory wires `StaticNarrator` alone; the rest of the
system cannot tell the difference.

Tests: adapter-level with a scripted `AgentInferencePort` fake (streamed chunks, thinking deltas,
abort); one manual smoke against local Ollama.

## Step 4 — Documentation + rollout

- Extend `docs/research-agent.md` (section "Narration layer"): the lifecycle diagram
  (state transition → card → thought bubble → message/fallback), the color/act rule, the settings
  toggle.
- Default the toggle to ON once the validator fallback rate is acceptably low in daily use.

---

## Commit sequence (one PR per step is fine)

1. `refactor(research-narrator): extract ResearchNarrator seam with StaticNarrator`
2. `feat(research-narrator): seeded wording variants in StaticNarrator`
3. `feat(research-narrator): async narration lifecycle with stale-guard and thinking bubble`
4. `feat(research-narrator): narration payload and deterministic validator with fallback`
5. `feat(agent-host): AgentInferencePort backed by OllamaService`
6. `feat(research-narrator): LlmNarrator behind runtime settings toggle`
7. `docs(research-agent): narration layer`

## Risks / open points

- **Existing text assertions** pin exact playbook strings; Step 0 must touch those tests
  (variant-set assertion or pinned seed) — budgeted, not incidental.
- **`ResearchPlaybook.setLanguage` is global/static**; the narrator reads the language per call.
  Fine for now (single session), noted as tech debt if multi-session ever happens.
- **Thinking-forwarding** depends on the configured model actually emitting thinking; the lifecycle
  must look correct with zero thinking deltas (static title until finish) — covered by Step 1 tests.
- **Validator strictness vs. fallback rate:** start strict; every relaxation is a conscious decision
  with a test.

## Outlook (separate plans)

- **NLU / soft intent:** map free-text user replies onto the *currently allowed* typed commands
  (constrained tool calling over `PhaseState.getAllowedCommands()`, `pendingApprovalId` as mandatory
  nonce for approve). Slash commands remain the explicit expert path.
- **Speculative pre-generation** for predictable transitions (outcome known before the card renders);
  needs the Step 1 stale guard, nothing else new.
