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

