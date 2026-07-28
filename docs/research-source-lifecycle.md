# Research source lifecycle (VISITED → CANDIDATE → ACCEPTED)

Implemented in `research-agent-ui-plugin` (`research.capture`, Commit 37) and wired productively by
the Commit-38 host factory.

## Separation

- **VISITED** — every successful navigation creates a `VisitedCapture` in the bounded, per-session
  `CaptureStore` (canonical URL, title, cleaned text, SHA-256 content hash, timestamp). Captures are
  temporary working material: bounded (oldest evicted), never silently persisted, never indexed.
- **CANDIDATE** — assessment metadata (`relevance`, `sourceType`, note) attached to a capture via
  `CaptureStore.assess`. Still not a source.
- **ACCEPTED** — `SourceAcceptanceService.accept(captureId)` is the ONLY path to a persistent
  `ResearchSourceRecord`. Atomic (record fully built → committed → THEN indexed) and idempotent
  (re-accepting returns ALREADY_ACCEPTED with the same id).

## Dedup rules (canonical URL + content hash)

- same URL + same hash → identical → `ALREADY_ACCEPTED` (no second source);
- different URL + same hash → content duplicate → accepted but flagged `duplicate=true`
  (the agent loop never repeats the same claim for it);
- same URL + different hash → new page revision → a fresh source.

Canonicalization strips fragments, `utm_*`/`ref`/`fbclid`/`gclid` parameters, default ports and
trailing slashes; scheme/host are lowercased.

## capture_id convention

The convention lives in the HOST (`BrowserBridgeEndpoint`), not in the agent: every page-returning
`web_*` result is recorded as a capture and the model-facing line becomes
`URL: <url> title="<title>" capture_id=<id>` + cleaned text. `source_accept(capture_id)` resolves it
through the productive service and returns the compact result line — never HTML or full text.

## Extraction and index boundary

- `DocumentExtractor` — port + HTML/Markdown/Text extractors + chain (pure Java; jsoup only in the
  static browser backend; no Tika).
- `ResearchSearchIndex` — port (`index`/`remove`/`rebuild`); ONLY accepted sources are indexed; an
  index failure marks the result `index=STALE` but never loses the source; `rebuild()` proves the
  index is a derived view of the repository. Productive adapter today: the in-memory implementation
  (the Lucene adapter remains open as **MCP-P006**, deferred/low). No Lucene types anywhere in
  UI/ports.
- Persistent repository: `FileResearchSourceRepository` under `<projectDir>/sources`.

## Verification

Capture/acceptance/dedup/index tests in the plugin, the 36A/36C loop tests (contract strings) and
the 38 E2E (file-persisted sources from two hosts, findings only on committed sources).
