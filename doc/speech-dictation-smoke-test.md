# Speech dictation — manual end-to-end smoke test

Automated tests cover format negotiation, normalization, the dictation state machine and the HTTP
adapter (against a fake server). They cannot exercise a **real microphone** or a **real Ollama audio
model**, so this manual smoke test must pass before the dictation feature stops being labelled
**"Experimental"** (acceptance criterion 13).

## Preconditions

- A reachable Ollama server whose `/api/show` reports the exact capability `audio` for at least one
  installed model (verify: *Models → Installed*; the model card shows the audio icon, or
  `POST /api/show` lists `"audio"` in `capabilities`).
- The server offers `POST /v1/audio/transcriptions` (recent Ollama).
- A working Windows microphone.

## Happy path

1. Confirm `/api/show` reports `audio` for the intended model.
2. Open the chat, expand **Chat settings**, pick your microphone under **Microphone**
   (or leave **System default**). Leave **Audio model** on **Automatic**.
3. Click **Test microphone** — a level should register while you speak. Nothing is sent to Ollama.
4. Click the microphone button and speak a fixed German sentence, e.g.
   *„Dies ist ein Test der Spracheingabe von AskAI."*
5. Watch the live level bar move and **Recording — m:ss** count up.
6. Click **Stop**.
7. Verify (in **Technical details**) the normalized WAV is **16 kHz, mono, 16-bit**.
8. `/v1/audio/transcriptions` returns text; the status shows **Transcribing …** then
   **Transcription ready. Review the text and press Send.**
9. The recognized text appears **at the caret** in the composer, existing text preserved.
10. Confirm the text is **not** auto-sent.
11. Confirm the temp recording is deleted after success (no leftover `askai-speech-*.wav`).

## Negative tests

- **Non-audio model:** force the audio model to a text-only model → dictation is refused with a clear
  "not audio-capable" message; the recording is kept for retry.
- **Old Ollama without the endpoint:** point at a server without `/v1/audio/transcriptions` → the
  readiness preflight reports the endpoint is unavailable *before* a long recording; recording action
  is disabled or fails fast, not after upload.
- **Blocked/wrong microphone:** deny mic access or select a dead device → a clear
  "microphone could not be opened" error naming the device.
- **Silence:** stay silent for the whole recording → **No signal** verdict blocks the upload.
- **Abort during upload:** start a long transcription and press **Cancel** → the HTTP call is aborted,
  reported as cancelled (not a technical error), and the recording is kept for retry.

## Record the result

Note the Ollama version, the model, the OS and the outcome. Once the happy path and all negative
tests pass, remove the **"Experimental"** marker from the audio/dictation controls.
