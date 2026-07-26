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

