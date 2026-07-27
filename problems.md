# Audio-DSP — known problems and remaining work

This file records blockers and honestly-scoped gaps found while implementing Slices 6–13D of the
Audio-DSP roadmap. Only buildable states are committed; anything not fully deliverable is documented here
with slice, affected function, concrete blocker, tried approaches, impact and possible later solution.

## Slice 6 — Noise Profiler / Adaptive Noise Suppression: on-disk NoiseProfile persistence deferred

- **Slice:** 6
- **Affected function:** "Speicherung und erneute Verwendung gelernter Noise Profiles" (persist a learned
  `NoiseProfile` on disk and reference it by `noiseProfileId` across runs) + editor UI for that.
- **Blocker / decision:** The core requirement — *Noise Profiler learns a noise model → Adaptive Noise
  Suppression consumes it* — is implemented fully via the in-run `AudioProcessingContext` metadata channel
  (typed `setNoiseProfile`/`getNoiseProfile`). A learned profile flows Profiler → Suppressor (mode
  "Use a learned noise profile") within one pipeline execution, exactly like the VAD speech track. What is
  deferred is only the *persistent artifact*: writing a `NoiseProfile` to a file, giving it an id, and
  resolving `noiseProfileId` at process time from a repository, plus the editor picker for it.
- **Why deferred:** A persisted `NoiseProfile` repository + id-resolution at block-process time + editor UI
  is a self-contained sub-feature that does not block any later slice; the transient flow already satisfies
  the fresh-per-run reproducibility rule (adaptive state is never persisted as a profile parameter).
- **Impact:** Learned noise models are per-run only; they are not yet saved/reused across app restarts.
  Automatic and Learn-from-silence suppression are fully functional and need no stored artifact.
- **Remaining work:** `NoiseProfile` (de)serialization (Gson), a file repository under the AskAI config
  dir, a resolver placed into the context before processing, and an editor field to pick a stored profile.
- **Possible later solution:** Mirror the JSON profile-transfer infrastructure (versioned envelope, atomic
  write) for `NoiseProfile`/`RoomProfile`/`MicrophoneArrayProfile` in one artifact-persistence slice.

## Slice 9D — Streaming WPE runs inside the batch pipeline, not a true real-time stream

- **Slice:** 9D
- **Affected function:** Streaming-adaptive WPE dereverberation.
- **Status:** Implemented and functional. The STREAMING mode uses a sliding, bounded-history window
  (~64 frames) with a short look-ahead (~8 frames) and a continuously carried, EMA-smoothed prediction
  filter, so it is causal-with-look-ahead and never needs the whole signal. It measurably reduces the late
  reverberant tail (verified by test), but intentionally more gently than the offline pass because it only
  sees bounded history.
- **Honest limitation:** The whole DSP pipeline is still a batch adapter — a block receives one complete
  `AudioBuffer` per run. STREAMING here means the *algorithm's* causality/state model, not a frame-by-frame
  real-time transport through the block. A genuine real-time streaming transport (push frames in, pull
  frames out with a fixed latency) would require a streaming pipeline runner, which is out of scope of the
  current buffer-in/buffer-out architecture and is not required by any later slice.
- **Possible later solution:** Add a streaming pipeline runner that feeds frames through stateful processors
  incrementally; the WPE STREAMING core already keeps bounded state and would slot into it.

## Slice 11 — MicrophoneArrayProfile persistence by id / editor picker deferred

- **Slice:** 11
- **Affected function:** Persisting a `MicrophoneArrayProfile` on disk and referencing it by
  `microphoneArrayProfileId`, plus an editor picker for it.
- **Status:** The `MicrophoneArrayProfile` type and geometry validation exist and the Delay-and-Sum
  Beamformer is fully functional — it takes the microphone positions inline (a `micPositionsMm` text
  parameter, "x,y,z" per mic in millimetres). With a valid geometry it aligns + sums toward a target
  direction; without one it refuses to run (validator ERROR) and never invents positions, exactly as
  required.
- **Deferred:** A persisted array-profile repository + id resolution + editor picker (same shape as the
  deferred `NoiseProfile`/`RoomProfile` persistence). Not required by any later slice.
- **Possible later solution:** One shared artifact-persistence slice for
  `NoiseProfile`/`RoomProfile`/`MicrophoneArrayProfile` (versioned JSON envelope, atomic write, id
  resolver placed into the processing context before a run).

## Slice 13A — RNNoise native runtime not bundled

- **Slice:** 13A
- **Affected function:** Running RNNoise as a Speech Enhancer backend.
- **Blocker:** RNNoise is a native (C) library and needs a JNI binding + the native `.dll/.so/.dylib` at
  runtime. Neither the native library nor a Java 8 JNI binding is bundled in this repository (and the core
  fat JAR must stay free of native binaries). There is no vetted, license-clean, Java-8-compatible RNNoise
  binding available to embed here without shipping platform binaries.
- **What is implemented:** The full backend abstraction (`SpeechEnhancementBackend` SPI +
  `SpeechEnhancementBackends` registry with ServiceLoader discovery), an always-available pure-Java backend
  (adaptive spectral suppression) that makes the Speech Enhancer block functional, and an `RnnoiseSpeechEnhancer`
  adapter that probes a runtime system property (`askai.audio.rnnoise.runtime`) and reports `NOT_INSTALLED`.
  When the backend is unavailable the block passes audio through unchanged and the validator shows the status;
  the core never depends on the native backend.
- **Impact:** No live RNNoise processing in this environment. Everything around it (selection, availability,
  graceful-missing, editability) works.
- **Possible later solution:** Add a separate optional Gradle module `audio-dsp-rnnoise` that ships the JNI
  binding + downloads/loads the native library on explicit user opt-in (like the OpenAL add-on) and registers
  a real backend through the ServiceLoader. The core needs no change.

## Slice 13B — DeepFilterNet runtime + model not bundled

- **Slice:** 13B
- **Affected function:** Running DeepFilterNet as a Speech Enhancer backend.
- **Blocker:** DeepFilterNet is a neural model requiring a runtime (native/ONNX) and a model file; neither is
  bundled, and there is no license-clean Java-8 embedding to ship in-core without platform binaries/models.
- **What is implemented:** `DeepFilterNetSpeechEnhancer` adapter registered in the backend registry; probes
  runtime (`askai.audio.deepfilternet.runtime`) + model (`askai.audio.deepfilternet.model`) properties and
  reports NOT_INSTALLED / MISSING_MODEL / INVALID_SAMPLE_RATE (DeepFilterNet is 48 kHz). The Speech Enhancer
  block passes audio through when it is unavailable; the profile stays editable.
- **Impact:** No live DeepFilterNet processing here. Selection/availability/graceful-missing all work.
- **Possible later solution:** Optional Gradle module `audio-dsp-deepfilter` bundling an ONNX-runtime binding
  + model loader, registered via ServiceLoader on explicit user opt-in.

## Slice 13D — Voice Isolation is an approximation; true speaker isolation needs a model

- **Slice:** 13D
- **Affected function:** Isolating a target/dominant voice from other voices and music.
- **What is implemented:** A `Voice Isolation` block with two backends. The pure-Java center backend is a
  real but limited stereo approximation (emphasize centred speech, reduce laterally-panned voices/music via
  mid/side) — genuinely useful for suitable stereo but not true source separation. A neural backend is
  offered but reports not-installed and passes audio through. Mono input passes through (the center approach
  needs stereo). The validator warns about the limitation and the missing neural backend.
- **Honest limitation:** True target-speaker isolation / voice enrollment needs a neural model (and
  enrollment data), which is not bundled and not implementable in pure Java in-core. Target Speaker
  Enrollment / Voice Enrollment are therefore not implemented (not faked), as the roadmap allows.
- **Possible later solution:** An optional neural voice-isolation module (same ServiceLoader/optional-module
  pattern as the other model backends), plus a speaker-enrollment artifact if/when a model supports it.


---

# Research Agent restarbeiten (Commits 14–20) — problem log

Fortlaufende IDs `RA-Pnnn`. Status: OPEN | WORKAROUND | DEFERRED | RESOLVED.

## RA-P001 — Produktive Quellen-/Projektpersistenz noch nicht angebunden

**Erkannt in:** Commit 15 (structured source management)
**Status:** DEFERRED
**Schweregrad:** MEDIUM
**Betroffene Module:** research-agent-ui-plugin (sources, agent)

### Erwartung
Quellen (und Artefakte) werden dauerhaft in einem lokalen Projekt-Store gehalten und überstehen
einen Neustart; optional ein Lucene-Index als abgeleitete, neu aufbaubare Sicht.

### Beobachtung
Aktuell existiert nur eine deterministische In-Memory-Implementierung
(`InMemoryResearchSourceRepository`); es gibt keine belastbare Projekt-Store-/Lucene-Infrastruktur
im Repository, an die adaptiert werden könnte.

### Analyse
Der Port `ResearchSourceRepository` ist bewusst frei von Lucene-/Swing-Typen und damit adapterfähig.
Die produktive Persistenz ist als eigener Slice (Commit 19) vorgesehen.

### Gewähltes Zwischenverhalten
Port + strukturierte UI + In-Memory-Adapter vollständig geliefert und getestet. Kein spekulativer
Persistenz-Unterbau.

### Auswirkung
Quellen-/Artefaktänderungen sind pro Session flüchtig (kein Neustart-Persist), bis Commit 19 den
Datei-/Index-Store liefert.

### Spätere Entscheidung
Commit 19: Projekt-Store (Markdown-Dateien + sources + state json, atomic write, Revisionen, Checksums);
Lucene nur hinter Adapter, aus dem Projekt-Store rebuildbar.

## RA-P002 — File-backed project store implemented and tested, not yet wired into the live session

**Erkannt in:** Commit 19 (persist research sources and artifact metadata)
**Status:** WORKAROUND
**Schweregrad:** MEDIUM
**Betroffene Module:** research-agent-ui-plugin (store, agent)

### Erwartung
Die laufende Research-Session schreibt Artefakte/Quellen/State in einen Projekt-Store und stellt
sie nach einem Neustart wieder her.

### Beobachtung
Ein vollständiger, getesteter Datei-Store existiert (`ResearchProjectStore`: `FileArtifactStore`,
`FileResearchSourceRepository`, `SessionStateFileStore`) mit atomarem Write (temp+rename), UTF-8,
Revisionen, Checksums, Restart-Restore, Korruptions-Isolation und Projekt-Isolation. Die
`ResearchAgentSession` nutzt jedoch weiterhin die In-Memory-Adapter.

### Analyse
Die deterministischen Session-Tests (und der `FakeHost`, dessen `getWorkspaceDirectory` denselben
Temp-Pfad liefert) erwarten frisches, kollisionsfreies In-Memory-Verhalten pro Lauf. Ein Umschalten
auf den Datei-Store erfordert eine per-Session/Projekt eindeutige Wurzel und Seed-if-empty-Logik, um
diese Tests stabil zu halten.

### Gewähltes Zwischenverhalten
Store-Rahmen vollständig implementiert und über `ResearchProjectStoreTest` (Restart-Restore inkl.)
abgesichert. Live-Session bleibt In-Memory (RA-P001).

### Auswirkung
Artefakt-/Quellen-/State-Änderungen der laufenden Session sind weiterhin flüchtig; die
Persistenz-Bausteine sind aber vorhanden und produktiv anschließbar.

### Spätere Entscheidung
`ResearchAgentSession` an `ResearchProjectStore` verdrahten (Wurzel = host workspace dir + projectId,
seed-if-empty, Memento bei jedem State-Change speichern, bei `activate()` wiederherstellen). Danach
optionaler Lucene-Index als aus dem Store rebuildbare, abgeleitete Sicht.

### Stand nach Commit 35b
ACP runtime integration works (echter Prozess-Round-trip inkl. Research-MCP-Readiness). **Restart
restoration remains incomplete until RA-P002 is resolved** — 35b liefert bewusst nur fresh sessions;
`session/load`/Memento-Restore folgt mit der Store-Verdrahtung.

---

# Research Agent — Endaudit-Korrekturen (Commits 21–29) — problem log

Fortlaufende IDs `RA-Pnnn`. Status: OPEN | WORKAROUND | PARTIALLY_RESOLVED | DEFERRED | RESOLVED.
Diese IDs adressieren die beim Endaudit nach Commit 20 gefundenen Architektur- und
Datenkonsistenzlücken. Reihenfolge und Zielinvarianten siehe Gesamtauftrag.

## RA-P003 — Plugin refresh leaks previous runtime generation

**Erkannt in:** Audit nach Commit 20
**Status:** RESOLVED (Commit 21 + Korrekturcommit 21b)
**Schweregrad:** HIGH
**Betroffene Module:** agent-plugin-host, askai-app

### Erwartung
Genau eine aktive PF4J-Runtime-Generation. Ein Refresh vergisst nie einen alten `AskAiPluginManager`;
die alte Generation wird erst nach nachweislich geschlossenen Sessions gestoppt und entladen; jeder
Teilfehler ist sichtbar und wird erneut versucht.

### Beobachtung
`WorkspacePluginService.discover()` überschrieb `pluginManager` mit einem neuen Manager ohne Stop/Unload
des vorherigen (Commit 21 behob das grundsätzlich). Ein Nachaudit fand jedoch verbleibende
Lifecycle-Lücken: (a) ein nach `managerFactory.create()` teilweise geladener Manager wurde bei einem
`loadPlugins()`-Fehler nicht bereinigt; (b) Session-Close und Stop-/Unload-Fehler wurden per
Best-Effort verschluckt, sodass die alte Generation trotz fehlgeschlagenem Session-Close entladen werden
konnte; (c) Close/Stop/Unload liefen teils auf dem EDT.

### Ursache
Best-Effort-Exception-Schlucken an sicherheitskritischen Stellen; kein strukturiertes Ergebnis für
Retirement/Session-Close; kein Tracking unvollständig retirierter Generationen.

### Korrektur
Commit 21: immutables `PluginRuntimeGeneration` + `activeGeneration`, off-EDT-Candidate, atomarer Swap,
`PluginCatalogSnapshot`. Korrekturcommit 21b: (a) `buildCandidate` bereinigt einen halbfertigen Manager
bei jedem Fehler (`cleanUpHalfBuilt`); (b) Generationswechsel über `GenerationSwapHook` — EDT detachiert
nur die Sessions, off-EDT werden sie geschlossen; die alte Generation wird **nur** bei erfolgreichem
Session-Close (`SessionCloseResult.isSuccessful()`) retiriert, sonst wird der Candidate verworfen und die
vorherige Generation aktiv gelassen; (c) `retire()` liefert `GenerationRetirementResult`
(stopped/unloaded/stop-/unload-Fehler/complete); unvollständige Generationen bleiben in
`retiringGenerations` und werden bei Refresh und Shutdown erneut retiriert; Fehler erscheinen als globale
Lifecycle-Fehler im Snapshot. Alle blockierenden Schritte laufen über `runOnEdtAndWait` außerhalb des EDT.

### Verifikation
`WorkspacePluginTransactionalRefreshTest` (Generation-Monotonie/Single-Active, JAR-Release),
`WorkspacePluginRetirementTest` (halbfertiger Manager bereinigt + vorherige Generation behalten;
fehlgeschlagener Session-Close bricht Swap ab und lässt alte Generation geladen; Close läuft off-EDT;
Startfehler → `START_FAILED`), `PluginRuntimeGenerationRetireTest` (Stop-/Unload-Fehler isoliert,
gemeldet, `complete=false` für Retry). `./gradlew clean build` grün.

### Nachhärtung (Korrekturcommit 22b)
Ein Nachaudit fand Restrisiken im Swap/Shutdown: (a) fehlgeschlagene `session.close()` verloren die
Session-Referenz (nur `List<String>`); (b) `runOnEdtAndWait` schluckte EDT-Task-Exceptions und hatte kein
Timeout; (c) `shutdown()` retirierte auf dem EDT; (d) `retiringGenerations` ohne Dedup. Behoben:
`AgentSessionCoordinator` hält fehlgeschlagene Sessions in `unclosed` und schließt sie bei nächstem Detach
und bei Shutdown erneut; `runOnEdtAndWait` liefert `UiCallResult` (propagiert Exceptions, `EDT_WAIT_TIMEOUT`),
fehlgeschlagener Detach bricht den Swap ab (vorherige Generation bleibt); `shutdown()` ist zweistufig
(EDT-Detach → off-EDT Close/Stop/Unload, `SHUTDOWN_TIMEOUT`-begrenzt); `retiringGenerations` ist nach
Generation-ID dedupliziert mit letztem `GenerationRetirementResult` (Plugin-ID + Stop/Unload-Phase im
Katalog).

### Verifikation (Nachhärtung)
`AgentSessionCoordinatorSwapTest` (fehlgeschlagener Close bleibt referenziert + Retry bei Detach/Shutdown),
`WorkspacePluginRetirementTest#detachFailureAbortsSwapAndKeepsPreviousGeneration`. `clean build` grün, 753.

### Nachhärtung 2 (Korrekturcommit 21c)
Vier weitere Lifecycle-Sicherheitsfehler behoben: (1) `runOnEdtAndWait` ist jetzt cancel-sicher — ein
EDT-Runnable mutiert nur nach atomarem `PENDING→RUNNING`; bei Timeout setzt der Lifecycle-Thread
`PENDING→CANCELLED`, sodass ein spät laufender Runnable nichts mehr tut (keine „Geister“-Detach/-Publish);
ein abgebrochener Publish verwirft den Candidate und behält die vorherige Generation; Timeout injizierbar.
(2) `PluginRuntimeGeneration.retire()` arbeitet pro Plugin: ein gestartetes Plugin wird erst nach
bestätigtem Stop entladen (Stop-Fehler ⇒ kein Unload), der `unloadPlugin`-Boolean wird geprüft, und
`complete=true` gilt nur, wenn der Manager für jedes ursprünglich geladene Plugin „nicht gestartet und nicht
geladen“ bestätigt. (3) Einziger produktiver Shutdown-Pfad: `ChatWorkspaceHostPanel.shutdown()` schließt
keine Sessions mehr selbst; `WorkspacePluginService.shutdown()` detachiert auf dem EDT und schließt/stoppt/
entlädt off-EDT auf einem dedizierten Non-Daemon-Thread (kein `join` auf dem EDT); ein fehlgeschlagener
Session-Close verhindert das Unload. (4) `fireChange()` isoliert jeden Listenerfehler, sodass ein kaputter
UI-Listener einen bereits ausgeführten Detach nicht in einen Fehler verwandelt oder Session-Referenzen verliert.

### Verifikation (Nachhärtung 2)
`EdtCancelSafetyTest` (timed-out Runnable mutiert nicht; rechtzeitig gepumpt läuft),
`PluginRuntimeGenerationRetireTest` (Stop-Fehler ⇒ kein Unload; `unload`-false/unbestätigt ⇒ incomplete;
Retry nur Rest; complete nur bei Manager-Bestätigung), `ShutdownOrderingTest` (Shutdown detachiert auf EDT,
schließt off-EDT via Coordinator), `AgentSessionCoordinatorSwapTest#aBrokenChangeListener…`. `clean build`
grün, 766 Tests.

### Restwirkung
Bei jeder erfolgreichen Generation werden alle AgentSessions geschlossen und lazy neu erzeugt
(bewusst einfach + sicher, RA-P010/RA-P002 liefern später den Restore aus dem Project Store).

## RA-P004 — Disabled plugins are still started

**Erkannt in:** Audit nach Commit 20
**Status:** RESOLVED (Commit 21)
**Schweregrad:** HIGH
**Betroffene Module:** agent-plugin-host

### Erwartung
Ein deaktiviertes Plugin wird geladen, aber `Plugin.start()` wird nicht aufgerufen; es liefert keine
selektierbare Extension; der Katalog zeigt `Enabled=false` und einen ehrlichen PF4J-State.

### Beobachtung
Zuvor wurden alle Plugins via `startPlugins()` gestartet und erst danach über den Enablement-Service
aus dem auswählbaren Katalog gefiltert — die Plugin-Instanz lief also trotz „disabled“.

### Ursache
Start-Entscheidung erfolgte nach dem Start, nicht davor.

### Korrektur
Nach `loadPlugins()` wird pro geladenem Plugin anhand der stabilen PF4J-Plugin-ID die Aktivierung
geprüft; nur aktivierte werden einzeln via `startPlugin(id)` gestartet. Deaktivierte bleiben RESOLVED,
erhalten eine ehrliche `Enabled=false`-Zeile und erscheinen in keiner selectable Map.

### Verifikation
`WorkspacePluginTransactionalRefreshTest#disabledPluginIsLoadedButNotStartedAndNotSelectable`
(Disable→RESOLVED+nicht selektierbar, Re-Enable→STARTED, Re-Disable→nicht selektierbar).

### Restwirkung
Keine.

## RA-P005 — Live backend still uses legacy phase/run-state pair

**Erkannt in:** Audit nach Commit 20
**Status:** RESOLVED (Commit 22)
**Schweregrad:** HIGH
**Betroffene Module:** research-agent-ui-plugin (state, backend, agent)

### Erwartung
`ResearchStateMemento` ist die einzige Live-Wahrheit; `PAUSED/BLOCKED/FAILED` erhalten den exakten
`continuationStateId` und die Approval-ID.

### Beobachtung
Der Live-Backend-State war `ResearchPhase + ResearchRunState`; vor jedem Dispatch wurde ein OO-State
rekonstruiert und der exakte Continuation-State durch `defaultContinuationStateId` ersetzt; die Approval-ID
ging bei Unterbrechungen verloren (`interrupt` übergab `null`, `continueInto` erzeugte eine neue ID).

### Ursache
Kein memento-basierter Live-Port; die OO-Interruptions trugen die Approval-ID nicht durch.

### Korrektur
Neuer nativer Port `ResearchStateMachinePort` + `OoResearchStateMachine`
(`dispatch(ResearchStateMemento) → ResearchStateTransitionResult{accepted,nextMemento,events,reason}`).
Die OO-Interruptions (`PausedState/BlockedState/FailedState`) tragen jetzt die `pendingApprovalId`;
`interrupt()` übernimmt sie vom unterbrochenen Approval-Gate, `continueInto()` stellt exakt dieselbe ID
wieder her. `ResearchStateFactory.state()` validiert Approval-IDs (nur am Gate bzw. bei einem in ein Gate
fortsetzenden Interrupt). `FakeResearchSessionBackend.FakeSession` hält ausschließlich ein
`ResearchStateMemento`; `ResearchBackendEvent` transportiert das Memento (Legacy-Getter daraus abgeleitet);
`ResearchAgentSession` leitet `AgentStateSnapshot`, State-View und Allowed-Commands allein aus dem Memento
ab (kein Default-Continuation-Raten). `DefaultResearchStateMachine` ist `@Deprecated` (Legacy-Adapter, von
keinem Live-Backend verwendet).

### Verifikation
`OoResearchStateMachineTest` (Block/Fail aus WAITING_APPROVAL → exakte Continuation + Approval-ID über
Unblock/Retry erhalten; Pause/Resume; Snapshot-Round-Trip; ungültiges Memento abgelehnt; Revision nur bei
Akzeptanz), `MementoBackendEventTest` (State-Events tragen konsistentes Memento; Approval-ID überlebt
Block/Unblock im Live-Backend). `./gradlew clean build` grün, 747 Tests.

### Nachhärtung (Korrekturcommit 22b)
Nachaudit-Punkte behoben: die OO-State-Machine erhält jetzt den injizierten `ResearchIdGenerator`/
`ResearchClock` des Backends (deterministische Approval-IDs/Mementos); `canExecute()` ist rein
(`ResearchStateMachinePort.allowedCommands(memento)`, keine Probe-Transition, kein ID-Verbrauch);
Approval-/Reject-IDs werden erst nach akzeptierter Transition als `processed` markiert und die
„Changes requested“-Meldung erst danach ausgegeben (ein No-op-Reject blockiert das Gate nicht mehr).

### Verifikation (Nachhärtung)
`BackendHardeningTest` (identische Läufe deterministisch; `canExecute` verbraucht keine IDs; No-op-Reject
lässt das Gate aktionierbar). `clean build` grün, 753 Tests.

### Nachhärtung 2 (Korrekturcommit 21c) — strikte Approval-Mementos
`ResearchStateFactory` ist jetzt streng: `WAITING_APPROVAL` verlangt zwingend eine nichtleere
`pendingApprovalId`, ebenso eine Interruption, deren `continuationStateId == WAITING_APPROVAL` ist; sonst
Ablehnung. `continueInto()` erzeugt im nativen Pfad keine neue ID mehr (die Interruption trägt die
Original-ID per Invariante). Die Reparatur alter Legacy-Daten (legacy phase/run-state ohne Approval-ID)
liegt ausschließlich im ausdrücklichen `LegacyResearchStateMigration`, das eine synthetische ID erzeugt;
`DefaultResearchStateMachine` nutzt dieses Adapter statt die Factory mit `null` aufzurufen.

### Verifikation (Nachhärtung 2)
`ApprovalMementoStrictnessTest` (Approval-Gate/Interruption ohne ID abgelehnt; Restore erfindet nie eine ID;
exakter Round-Trip; Migration synthetisiert ID für Legacy-Daten). `clean build` grün, 766 Tests.

### Restwirkung
`problemCode`/`publicProblemMessage` werden derzeit noch über Backend-Events (BLOCKED/ERROR) transportiert,
nicht im Memento. Die vollständig atomare Persistenz von State + Problem folgt in Commit 27 (RA-P002).
Persistenz/Restore des Mementos folgt ebenfalls in Commit 27.

## RA-P006 — Store compare-and-write is not atomic under concurrency

**Erkannt in:** Audit nach Commit 20
**Status:** OPEN (Ziel: Commit 25)
**Schweregrad:** HIGH
**Betroffene Module:** research-agent-ui-plugin (store)

### Beobachtung
Read→expectedRevision→write findet nicht durchgängig unter demselben Lock pro Ressource statt.

### Korrektur
Siehe Commit 25 (Lock über den gesamten CAS-Ablauf, JVM-weit + File-Lock soweit Java-8/Windows-tauglich).

## RA-P007 — UTF-8/source persistence and error classification

**Erkannt in:** Audit nach Commit 20
**Status:** OPEN (Ziel: Commit 25)
**Schweregrad:** MEDIUM
**Betroffene Module:** research-agent-ui-plugin (store, sources)

### Beobachtung
Selbstgeschriebenes `.properties`-Format via `Properties.load(InputStream)` ist nicht Unicode-sicher;
IO-Fehler können als Revisionskonflikt erscheinen.

### Korrektur
Siehe Commit 25 (versioniertes UTF-8-Format, deterministische Round-Trips, `ERROR`≠`CONFLICT`).

## RA-P008 — Unsaved artifact/source drafts can be lost

**Erkannt in:** Audit nach Commit 20
**Status:** OPEN (Ziel: Commit 26)
**Schweregrad:** MEDIUM
**Betroffene Module:** agent-plugin-host (artifact area), research-agent-ui-plugin (views)

### Beobachtung
Bei Artifact-Rebuild, Moduswechsel oder Konflikt können lokale ungespeicherte Texte überschrieben werden;
der Markdown-Konfliktpfad behauptet, lokal zu behalten, setzt aber den Store-Text in den Editor.

### Korrektur
Siehe Commit 26 (View-Identität, Draft-Cache, Konfliktoptionen ohne stille Überschreibung).

## RA-P009 — Composer availability does not track session state correctly

**Erkannt in:** Audit nach Commit 20
**Status:** OPEN (Ziel: Commit 24)
**Schweregrad:** MEDIUM
**Betroffene Module:** agent-plugin-host, askai-app (composer)

### Beobachtung
`AgentSessionCoordinator` meldet nicht bei jedem Backend-State-Event; `setChatBusy(true)` deaktiviert den
gesamten Editor, sodass Slash Commands (`/status`, `/pause`, `/cancel`) nicht zuverlässig eingebbar sind.

### Korrektur
Siehe Commit 24 (Session-Change-Listener; getrennte Prompt-/Command-Verfügbarkeit).

## RA-P010 — Session identity and background-event routing are underspecified

**Erkannt in:** Audit nach Commit 20
**Status:** OPEN (Ziel: Commit 23, vollständig mit Commit 27)
**Schweregrad:** MEDIUM
**Betroffene Module:** agent-plugin-host, research-agent-ui-plugin

### Beobachtung
Sessions werden nur nach Agent-ID unterschieden; Hintergrundsessions können den sichtbaren Chat
unmarkiert mit transienten Ereignissen überfluten.

### Korrektur
Siehe Commit 23 (`AgentSessionKey = agentId+conversationId+projectId`, sessionbezogene Puffer) und
Commit 27 (Restore aus Project Store).

## RA-P011 — Global discovery failures are not visible in Plugin Management

**Erkannt in:** Audit nach Commit 20
**Status:** RESOLVED (Commit 21)
**Schweregrad:** MEDIUM
**Betroffene Module:** agent-plugin-host

### Erwartung
`PluginManagementPanel` zeigt sowohl Plugin-Fehlerzeilen als auch globale Discovery-/Startfehler und bei
globalem Refresh-Fehler „Refresh failed; previous plugin generation remains active.“

### Korrektur
`PluginCatalogSnapshot` trägt `globalFailures` + `generationFailed`; `PluginManagementPanel` rendert eine
globale Statuszeile und die o. g. Meldung. `WorkspaceCatalogListener.onCatalogSnapshot` liefert den
Snapshot (mit Default auf die Legacy-Form, damit bestehende Listener unverändert bleiben).

### Verifikation
`WorkspacePluginTransactionalRefreshTest#globalFailureKeepsPreviousGeneration` prüft das
`generationFailed`-Flag und nicht-leere `globalFailures`; Panel-Rendering ist ein reiner Swing-View.

### Restwirkung
Keine.

### Enablement-Key-Migration (RA-P004, Zusatz)
Der Enablement-Key ist die stabile PF4J-Plugin-ID (`Plugin-Id` aus dem Manifest). Für kompatible Plugins
erzwingt `PluginCompatibilityChecker` `manifestId == descriptor.getId()`, d. h. die bisher persistierten
Keys (Descriptor-ID) sind bereits identisch mit der PF4J-ID — die Migration ist ein verifizierter No-op.
Keine stillen Mehrdeutigkeiten.
# Research MVP (Solon MCP / ACP / Playwright4j, Commits 30–39) — problem log

Fortlaufende IDs `MCP-Pnnn`. Feasibility probes gegen Maven Central durchgeführt.

## MCP-P001 — playwright4j:0.1.0 ist Java-21-Bytecode → Java-21-Browser-MCP-Sidecar (kein Host-Blocker)

**Erkannt in:** Commit 32 (browser MCP) — Vorab-Feasibility
**Status:** WORKAROUND
**Schweregrad:** MEDIUM
**Betroffene Module:** browser-mcp-server-java21 (neu), browser-api

### Erwartung
Browserzugriff über MCP; `com.aresstack:playwright4j:0.1.0` verwenden.

### Beobachtung
`playwright4j:0.1.0` ist **Bytecode-Major 65 (Java 21)** und zieht `com.microsoft.playwright:driver-bundle`
(Node + Browser-Binaries) nach. Es gehört daher **nicht in ein Java-8-Modul** und nicht in den AskAI-Host.

### Analyse (korrigiert)
Das ist **kein** MVP-Blocker. Da der Browserzugriff ohnehin über MCP (Prozessgrenze) läuft, ist die saubere
Lösung ein separater **Java-21-Browser-MCP-Sidecar-Prozess**, den der Java-8-Host nur über den MCP-Client
anspricht. Vorteile: kein Java-21-Bytecode im Host, Browserabsturz reißt AskAI nicht mit, Ressourcen
pro-Prozess schließbar, keine Classloader-Konflikte.

### Gewähltes Zwischenverhalten
Neues Modul `:browser-mcp-server-java21` (Java-21-Toolchain, `playwright4j` + `solon-ai-mcp`), separates
ausführbares Artefakt, **nicht** im AskAI-Fat-JAR, nur über den Java-8-MCP-Client angesprochen. Der Host
kennt nur den Browser-MCP-Endpoint + die flachen Tools (`web_search/open/read/links/follow/back`).
Optionaler `HttpURLConnection`+jsoup-Fallback für statische Seiten hinter demselben Browser-Port (nicht als
Ersatz für den Sidecar, nicht als zweiter Tool-Satz).

### Spätere Entscheidung
Browser-Binary-/Node-Runtime-Bedarf des `driver-bundle` ehrlich dokumentieren (Packaging), Sidecar + Tools +
deterministische Tests implementieren (Commit 32).

## MCP-P002 — ACP-SDK ist veröffentlicht und Java-8-tauglich (frühere Aussage war falsch)

**Erkannt in:** Commit 34 — Vorab-Feasibility (korrigiert)
**Status:** RESOLVED
**Schweregrad:** —
**Betroffene Module:** acp-client-api, acp-solon-client

### Erwartung
`org.noear:acp-sdk` / Solon-ACP hinter einem gekapselten ACP-Adapter nutzen (keine Eigenimplementierung).

### Beobachtung / Korrektur
Die frühere „404"-Schlussfolgerung beruhte auf **frei geratenen Versionen** (0.1.0/0.9.0/1.0.0). Mit den
tatsächlichen Koordinaten lösen alle Artefakte auf und sind **Java-8-Bytecode**:

```
org.noear:acp-sdk:3.10.1        → resolved
org.noear:solon-ai-acp:3.10.1   → resolved
org.noear:solon-ai-mcp:3.10.1   → resolved
```

Bytecode-Prüfung des vollständigen Graphen (48 Jars) inkl. `reactor-core:3.7.4` und `jackson:2.19.4`:
**kein Jar mit Classfile-Major > 52** — d. h. alle produktiven Klassen sind Java 8. Solon selbst bewirbt
Java-8- bis Java-25-Kompatibilität.

### Auswirkung
Kein Blocker mehr. Commits 34–36 (ACP-Adapter, externer Agent, Loop) sind regulär umsetzbar; Reactor/ACP-SDK
bleiben im Adaptermodul gekapselt.

### Verifikation
`gradle`-Resolve + `unzip`/`od`-Bytecode-Major-Check aller 48 transitiven Jars (Ergebnis: alle ≤ 52).

## MCP-P003 — Marketplace-Quelle übernommen

**Erkannt in:** Commit 31 (marketplace)
**Status:** RESOLVED
**Schweregrad:** —
**Betroffene Module:** mcp-marketplace

### Auflösung
Die Marketplace-Quellen aus dem bereitgestellten `mcp-marketplace-swing-java8.zip` wurden in Commit
`5439076` verbatim als Modul `:mcp-marketplace` übernommen (nicht neu geschrieben) und um das neutrale
`McpServerConfiguration`-Modell + `McpInstallOptionMapper` ergänzt. Installation und Runtime-Aktivierung
sind strikt getrennt (gespeicherte Konfiguration immer `enabled=false`; kein Prozess-/Verbindungscode im
Modul). 7 Tests grün; Isolation verifiziert (kein Solon im Marketplace, kein Marketplace in der Runtime-API).

## MCP-P004 — Commit 30 vervollständigt: echter Solon streamable-HTTP-Transport

**Erkannt in:** Commit 30 (mcp runtime foundation)
**Status:** RESOLVED
**Schweregrad:** —
**Betroffene Module:** mcp-solon-runtime (neu)

### Auflösung
`:mcp-solon-runtime` implementiert `SolonMcpServerRuntime implements McpServerRegistry` mit einem echten
Solon streamable-HTTP-Server auf `127.0.0.1` (freier Port), per-Endpoint-Token im Pfad, dynamischem
Tool-Update und idempotentem Shutdown. `SolonMcpServerRuntimeTest` beweist den vollen Round-trip mit einem
**echten Solon-MCP-Client**: tools/list, `ping`→pong, `echo`→text, dynamisches Entfernen (frischer Client
sieht nur noch `ping`), Abweisung eines falschen Tokens, kontrollierter Shutdown. `InProcessMcpServerRegistry`
bleibt die deterministische Testimplementierung; die produktive Runtime ist Solon. (1 Test grün.)

### Verwendete Grundlage (Java 8, verifiziert)
```
org.noear:solon:3.10.1                  (Solon.start/stopBlock)
org.noear:solon-boot-jdkhttp:3.10.1     (JDK-HTTP-Server, Java 8)
org.noear:solon-ai-mcp:3.10.1           (McpServerEndpointProvider, McpClientProvider)
```

### Ursprüngliche Beobachtung (historisch, behoben)
Vor der Vervollständigung war nur die transportfreie `InProcessMcpServerRegistry` geliefert; die Begründung
„der reale Transport wird erst vom Agenten gebraucht" war nicht tragfähig — Commit 30 sollte den Transport
isoliert beweisen. Dies ist durch die obige Auflösung erledigt.

## MCP-P005 — Playwright-Treiber-Orchestrierung im Sidecar noch nicht implementiert

**Erkannt in:** Commit 32 (browser MCP sidecar)
**Status:** OPEN
**Schweregrad:** HIGH
**Betroffene Module:** browser-mcp-server-java21

### Erwartung
Der Java-21-Sidecar bedient `web_*` real über Playwright (JS-Navigation, dynamische Seiten).

### Beobachtung
`playwright4j:0.1.0` ist **kein** High-Level-Port der Playwright-Java-API, sondern eine GraalJS-Runtime, die
den rohen Playwright-**JS-Driver** hostet (`GraalPlaywrightRuntime.evaluate(...)`, polyglot `Value`,
`drainTransports()`/`runDueTimers()`-Eventloop). Es gibt keine `Browser`/`Page`-Fassade; zusätzlich braucht
die Laufzeit die Browser-Binaries des `driver-bundle`.

### Gewähltes Zwischenverhalten
Sidecar vollständig baubar + paketierbar (`sidecarJar`, 252 MB, Main major 65): Solon-MCP-Endpoint (loopback,
Token im Pfad) mit exakt `web_search/web_open/web_read/web_links/web_follow/web_back`; die Playwright-Session
meldet jede Tool-Ausführung als lesbaren NOT_INSTALLED-artigen Fehler. Kein Build-Bruch bei fehlendem Driver.
`STATIC_HTTP` existiert als separates, sichtbar limitiertes Backend hinter demselben `BrowserSession`-Port
(kein stiller Fallback, kein zweiter Tool-Satz) und ist voll getestet.

### Spätere Entscheidung
Treiber-Orchestrierung über `GraalPlaywrightRuntime` implementieren (Driver-JS laden, Connection/Transport
über `drainTransports`, Browser-Launch, Page-Navigation) + Live-Test, sofern Browser-Binaries vorhanden.

## MCP-P006 — Produktiver Lucene-Indexadapter noch nicht verdrahtet

**Erkannt in:** Commit 37 (source lifecycle / indexing boundary)
**Status:** DEFERRED
**Schweregrad:** LOW
**Betroffene Module:** research-agent-ui-plugin (capture)

### Erwartung
Ein produktiver Lucene-Adapter hinter `ResearchSearchIndex`, als aus den Source-Records rebuildbare,
abgeleitete Sicht.

### Gewähltes Zwischenverhalten
Der Port ist final (`index`/`remove`/`rebuild`), die Indexgrenze steht (nur ACCEPTED wird indexiert; Index-
Fehler verliert nie die Source, markiert nur STALE; `rebuild()` beweist die Derived-View-Eigenschaft) und der
deterministische In-Memory-Adapter deckt Tests + MVP ab. Keine halbfertige Lucene-Integration in Commit 37.

### Spätere Entscheidung
Eigener Slice: Lucene-Adapter (Java-8-taugliche Lucene-Version prüfen) hinter demselben Port; Rebuild aus
`ResearchSourceRepository`.
