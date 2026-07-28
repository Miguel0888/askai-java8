# Vision image payloads — investigation (#11)

## Question

In a multi-turn chat with a vision model, different attached images sometimes get the same wrong
description. Is AskAI reusing stale Base64 data or associating the wrong image with a turn, or is
this a weak-model quality issue?

## Finding: request construction is correct

The per-turn image path was verified end to end; each attachment is transmitted and bound to its
own turn:

- **Fresh encoding per send.** `ImageAttachmentContentLoader.encodeAll` reads each attachment's
  bytes from disk and Base64-encodes them at send time — never cached, never shared between turns.
  Different files therefore produce different Base64 (verified by test).
- **Per-turn ownership.** Each user turn is an immutable `OllamaChatTurn` that defensively copies
  its own `images` list. `dispatchChat` adds exactly the images encoded for that turn.
- **Correct serialization.** `AskAiOllamaClient` maps each turn to a `ChatMessage`, and
  `Ollama.java` writes each message's `images` as its own base64 array on `/api/chat`. No flatten,
  no cross-turn reuse.
- **Same path for image-only and text+image.** Both go through `dispatchChat` →
  `OllamaChatTurn.user(text, images)`; only the transcript rendering differs.
- **No stale draft.** `composer.clearDraft()` runs only after successful encoding, inside
  `dispatchChat`, so a hand-off cannot leave stale attachment state behind.

These properties are locked down by `VisionRequestConstructionTest`.

## Most likely cause of the observed behavior

Every request sends the **entire history**, so in a multi-turn vision chat *all* previously
attached images are re-sent on every turn. A weak model (e.g. `gemma4:e2b`) with several images in
one context readily conflates them and repeats an earlier description. This is a model-capability
limitation, not a payload bug in AskAI.

## Diagnostics

Run with `-Daskai.vision.diagnostics=true` to print, for every outbound request, safe per-image
metadata — never Base64, file contents or paths:

```text
[vision] turnIndex=2 role=user imageIndex=0 mediaType=image/png byteLength=20482
[vision] turnIndex=4 role=user imageIndex=0 mediaType=image/jpeg byteLength=51120
[vision] summary turns=5 imageTurns=2 totalImages=2
```

Use this to confirm the image count and turn association for a suspicious conversation before
attributing a wrong description to the payload.

## Possible follow-up (not implemented)

If multi-image confusion is a problem in practice, an option to send only the most recent turn's
images (dropping older image turns from the request while keeping their text) would reduce the
context a weak model has to disambiguate. This changes conversation semantics, so it is left as an
opt-in follow-up rather than a default.
