# Research MVP — architecture, acceptance and merge gate

## The chain

```text
AskAI host (Java 8, Swing)
  ResearchRuntimeGenerationSwitch        prepare-then-publish generations
    ProductiveResearchBackendFactory     per-session composition (atomic, rollback)
      ProductiveResearchSessionResources one owner per resource
        OO research state machine        the ONLY transition authority
        ResearchControlEndpoint          research.<session>.g<gen> on SolonMcpServerRuntime
        BrowserBridgeEndpoint            browser.<session>.g<gen> (capture_id convention)
        BrowserMcpSidecarProcess         Java-21 process: playwright4j → GraalJS driver → Chrome/Edge
        AcpResearchSessionBackend        ResearchSessionBackend port (pure adapter)
          research-agent-runtime         external Java-8 process over ACP/stdio
            ResearchLoop                 deterministic autonomous loop (36A, backend-agnostic)
              SolonToolInvoker ×2        real MCP clients → bridge + research endpoints
```

Sources persist via `CaptureStore → SourceAcceptanceService → FileResearchSourceRepository`
(+ in-memory search index, MCP-P006). PHASE_READY is an event; the host dispatches
`REQUEST_EVIDENCE_REVIEW` to enter waiting_approval.

## Runtime prerequisites (nothing is downloaded automatically)

| Requirement                          | Used for                          |
|--------------------------------------|-----------------------------------|
| Java 8 (host JRE)                    | AskAI, agent process              |
| Java 21 launcher (configured path)   | browser sidecar                   |
| `research-agent-runtime-*-all.jar`   | external agent (`researchAgentJar`) |
| `browser-mcp-sidecar-*.jar` + `lib/` | sidecar (`sidecarJar`, thin + Class-Path) |
| locally installed Chrome or Edge     | Playwright channel browser        |
| search provider URL (`{query}`)      | `web_search` (optional)           |

Temporary artifacts: `%TEMP%/playwright4j-driver` (driver package assets, extracted by playwright4j).
Shutdown order per session: agent process → research endpoint → browser bridge → sidecar client →
sidecar process (which ends the GraalJS driver child and Chromium). All closes are idempotent.

## What is proven automatically (all green, `./gradlew build --rerun-tasks`)

- 36A: autonomous loop over real MCP endpoints (deterministic browser), full budget/stop matrix.
- 36B: real Chrome via playwright4j renders JavaScript-only content (live test, environment-gated).
- 36C: the UNCHANGED loop + invoker against the live sidecar process and the productive
  Commit-37 acceptance path.
- 38: the productive HOST wiring end to end (`ProductiveResearchMvpEndToEndTest`): generation
  publish → real agent + sidecar + Chrome + two JS hosts → SUFFICIENT_EVIDENCE, ≥3 JS captures,
  ≥2 persisted sources on 2 hosts, findings only on committed sources, exactly one PHASE_READY with
  the state unchanged until the host reacts, ordered teardown, failed switch keeps the old
  generation usable, successful switch locks it.

## Enabling the productive mode in AskAI (Commit 40)

Open the Research workspace → artifact tab **Runtime**:

1. Set *Backend mode* to `Productive (ACP + browser sidecar)` (the default `Fake` is the labelled
   clickdummy/development mode).
2. Fill in the paths: Java 8 launcher + `research-agent-runtime-*-all.jar`, Java 21 launcher +
   `browser-mcp-sidecar-*.jar` (with its `lib/` directory next to it), browser channel, optionally a
   search URL containing `{query}`.
3. **Check requirements** validates every item individually off the EDT and briefly starts the sidecar
   to obtain its real readiness status (READY / INCOMPATIBLE_DRIVER / DRIVER_BUNDLE_NOT_FOUND /
   BROWSER_NOT_INSTALLED / BROWSER_START_FAILED).
4. **Save** persists through the typed `ResearchRuntimeSettings` model; saving an unusable productive
   configuration is rejected with the concrete problem list.
5. The NEXT research session uses the configured backend. A productive start failure is a visible
   error — there is no silent fallback to the fake backend. (Driving phase transitions from the chat
   UI is the follow-up MCP-P008.)

## Manual acceptance checklist

Environment: Windows, Java 8 + Java 21 installed, Chrome installed, jars built.

1. `./gradlew build` is green.
2. `./gradlew :research-agent-runtime:researchAgentJar :browser-mcp-server-java21:sidecarJar` built both artifacts.
3. Start the sidecar manually (port/token of your choice); STDERR shows `playwright readiness: READY: channel=chrome …`.
4. STDERR shows `ready on 127.0.0.1:<port> backend=PLAYWRIGHT_SIDECAR`; the token appears nowhere in the output.
5. Rename the Chrome folder (or use a machine without Chrome): readiness reports `BROWSER_NOT_INSTALLED`, tools return that status, the process stays up. Restore Chrome.
6. `web_open` on a JavaScript-rendered page returns the rendered text (not the empty static HTML).
7. `web_open` on `file:///…` and on `http://169.254.169.254/` is rejected with a readable reason.
8. `web_links` → `web_follow` → `web_back` navigate correctly; link ids reset per page.
9. Kill the sidecar: no Chromium/GraalDriverMain processes remain (task manager).
10. Start AskAI; the Research workspace opens with the deterministic FAKE backend (clickdummy) — visibly, as documented in MCP-P007.
11. Marketplace: importing an MCP server entry creates a configuration but starts NO process.
12. Run `ProductiveResearchMvpEndToEndTest` (or the programmatic wiring): the agent process appears, the sidecar appears, Chrome launches headless.
13. During the run, `research_status` reflects the host state; the offered tool set changes with the phase (tools/list_changed).
14. `source_accept` outside RESEARCH/running is rejected with "Not allowed in the current state".
15. The run stops with `RESEARCH_RUN_STOPPED: SUFFICIENT_EVIDENCE …` and exactly one `PHASE_READY` line.
16. `<projectDir>/sources` contains the persisted source records; origins cover both test hosts.
17. The findings artifact references only existing source ids.
18. The host command REQUEST_EVIDENCE_REVIEW moves the session to waiting_approval; the agent never did.
19. Closing the session ends agent + sidecar processes and invalidates both endpoint URLs (client calls fail).
20. A second close is a no-op (no exception, no log spam).
21. A generation switch with a broken config fails with the validation list; the active generation still creates sessions.
22. A successful switch locks the old generation (`retired`) for new sessions.
23. Search the transcript/logs of the whole run for the endpoint tokens: zero hits.

## Merge gate

**NOT MERGE READY** (as mandated: no merge to `main` within this work order).

Open problem ids and why they do not block the MVP scope:

| Id       | Status        | Restriction                                                                    |
|----------|---------------|--------------------------------------------------------------------------------|
| MCP-P006 | DEFERRED/LOW  | Search index is the in-memory adapter; sources themselves are file-persisted and the index is rebuildable — Lucene is an additive adapter behind the same port. |
| MCP-P007 | RESOLVED (40) | The productive mode is selectable, validated and startable from AskAI (Runtime settings view + typed settings + strict factory switch). |
| MCP-P008 | OPEN/MEDIUM   | Chat commands do not yet drive the productive host state machine (programmatic dispatch works and is E2E-proven); phase transitions from the chat UI land in a follow-up slice. |
| RA-P001  | WORKAROUND    | PF4J refresh quirk documented in plugin-lifecycle docs; hardened stop/unload path in place. |
| RA-P002  | WORKAROUND    | ACP runtime integration works. Restart restoration remains incomplete until RA-P002 is resolved (memento store wiring). |

Everything else on the MVP list (MCP-P001…P005) is RESOLVED or an accepted documented workaround
(MCP-P001: the Java-21 sidecar itself). Merge decision stays with the maintainer after review of
this branch; the technical precondition list above is complete and honest.
