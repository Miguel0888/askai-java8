# Research artifacts

Artifacts appear in the shared, collapsible artifact area (opened via `/open <artifact>` or the reveal hook;
closed, AskAI looks like the normal chat).

## Markdown artifacts

`document.md` — the ONE canonical working document (issue #32): the DRAFT and FINALIZATION phases both work
on it via `document_read`/`document_save`. It uses the host's default Markdown editor
(`HostMarkdownArtifactView`) over the generic `AgentArtifactStore`:

- `read(id) → ArtifactContent(markdown, revision)`
- `replace(id, expectedRevision, markdown) → ArtifactWriteResult` (UPDATED / CONFLICT / error)

Revisions + optimistic locking prevent lost updates between the user's editor and (future) agent tools.
Content lives in the store, not in UI state. Type id: `"markdown"`.

Legacy note (issue #32): earlier versions kept one Markdown artifact per processing stage — `concept.md`,
`research-notes.md`, `findings.md`, `draft.md`, `final.md`. These are no longer part of the catalog, get no
tabs and are never written by the active workflow; files in old project directories stay untouched. The
ResearchBrief is the canonical scoping artifact (no concept document beside it).

## Derived projections

- **Visualisierung** (`research.visualization`): Mermaid diagram derived from the brief; generated only via
  the explicit button (issue #29), persisted under `visualization/`.
- **Inhaltsverzeichnis** (`research.outline`, `outline.md` artifact slot): the outline projection of the
  knowledge corpus; rebuilt only via the explicit button (issue #29), with a stale marker when inputs changed.

## Structured artifacts

- **Sources** (`research.sources`): a writing manager (`ResearchSourcesView`) over `ResearchSourceRepository`
  (`find`/`get`/`update`). Fields: sourceId, title, origin, url, sourceType, capturedAt, author,
  linkedSectionIds, comment, relevance, reliability, status, snapshotReference, checksum, revision.
  Statuses: NEW/REVIEWED/ACCEPTED/EXCLUDED/DUPLICATE/SUPERSEDED (exclusion is a status, not a delete).
  Reliability: UNKNOWN/LOW/MEDIUM/HIGH/PRIMARY_SOURCE. All writes go through `update(id, expectedRevision,
  SourceUpdate)`; conflicts reload without overwriting; section links that are no longer in the outline are
  shown as orphans, never auto-removed. No Lucene types in the port or UI.
- **State** (`research.state`): the read-only state-machine visualization (`ResearchStateView`) over a
  domain-derived `ResearchStateSnapshot`. See `docs/research-state-machine.md`.

## Persistence (project store)

On-disk layout (`ResearchProjectStore`), atomic writes, UTF-8, revisions, checksums, restart restore:

```
project/artifacts/<id>.md (+ <id>.md.meta)   sources/<sourceId>.properties   state/research-session.json
```

`FileArtifactStore`, `FileResearchSourceRepository` and `SessionStateFileStore` implement the same ports as
the in-memory adapters. Multiple projects are isolated by root. A Lucene index, if added later, is a derived
view rebuildable from these files. See RA-P001/RA-P002 in `problems.md` for the live-session wiring status.
