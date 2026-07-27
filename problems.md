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
