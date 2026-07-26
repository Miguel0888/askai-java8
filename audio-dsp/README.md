# audio-dsp

Java 8 microphone capture with a small, classic PCM DSP pipeline. The output (WAV file or PCM
stream) is meant to be handed to a **remote** speech-to-text system (e.g. Ollama's
OpenAI-compatible `/v1/audio/transcriptions`).

**Not part of this module:** local AI inference of any kind — no ONNX Runtime, no local VAD
model, no local STT logic, no direct Ollama coupling in the recorder.

## Goal

```
Microphone
  → Java Sound capture (TargetDataLine)
  → PCM 16-bit signed little endian
  → DSP pipeline (fixed 10/20 ms frames)
  → WAV file or PCM stream
  → later: remote hand-off to Ollama/STT
```

Default format: **16 kHz, mono, 16-bit PCM**, 20 ms frames.

## Architecture

Strict layering; no direct coupling between recorder, DSP steps and output sinks:

| Layer | Package | Contents |
|---|---|---|
| Domain / DSP core | `com.aresstack.audio.domain` | `PcmAudioFormat`, `AudioFrame`, `Pcm16Samples`, `FrameSegmenter` |
| DSP | `com.aresstack.audio.dsp` | `Pcm16Processor` (SPI), `Pcm16ProcessingPipeline`, `DcOffsetRemovalProcessor`, `HighPassFilterProcessor`, `SoftNoiseGateProcessor`, `CompressorProcessor`, `LimiterProcessor`, `AudioLevelMeter` |
| Application | `com.aresstack.audio.application` | `AudioSource` / `AudioSink` (ports), `AudioCaptureService`, `RecordSpeechInputUseCase`, `SpeechCaptureConfiguration` |
| Infrastructure | `com.aresstack.audio.infrastructure` | `JavaSoundMicrophoneSource`, `WavFileAudioSink`, `MemoryAudioSink`, `Pcm16LittleEndianCodec`, `AvailableAudioDevices` |
| Demo | `com.aresstack.audio.demo` | `RecordSpeechDemo` |

Extension points:

- New DSP step: implement `Pcm16Processor`, add it to the pipeline list — no existing class changes.
- New output (e.g. a streaming HTTP upload): implement `AudioSink` — capture and DSP stay untouched.
- New capture backend: implement `AudioSource`.

The capture thread never runs DSP or I/O: frames go through a bounded queue to a writer thread,
so a slow file/network sink cannot block recording (overflow frames are dropped and counted).

## Standard pipeline

`RecordSpeechInputUseCase.buildSpeechPipeline(...)` assembles, in order:

1. **DC offset removal** — slowly adapting running offset estimate.
2. **High-pass filter** — first-order IIR, default cutoff 80 Hz (rumble/footfall).
3. **Soft noise gate** — RMS-based; below the threshold the signal is *reduced* (default gain 0.3),
   never muted; attack/release smoothed.
4. **Compressor** — gentle 3:1 above threshold 12000 to even out speech loudness.
5. **Limiter** — clamps at ceiling 30000 to protect against clipping.
6. **Level meter** — RMS/peak/clip counters for logging; does not modify audio.

All parameters live in `SpeechCaptureConfiguration` (builder with speech defaults).

## Build & run

Gradle (part of this repository):

```
./gradlew :audio-dsp:test
```

Maven (standalone):

```
mvn -f audio-dsp/pom.xml test
```

Demo — list devices, then record 5 seconds from the default microphone into `speech-input.wav`:

```
java -cp audio-dsp/build/classes/java/main com.aresstack.audio.demo.RecordSpeechDemo --list-devices
java -cp audio-dsp/build/classes/java/main com.aresstack.audio.demo.RecordSpeechDemo
java -cp audio-dsp/build/classes/java/main com.aresstack.audio.demo.RecordSpeechDemo --device "Mikrofonarray"
```

If the device does not support 16 kHz/mono/16-bit, the recorder aborts with a clear error instead
of silently recording a different format (a resampling processor can be added later).

## Notes

- Dependencies: none at runtime; JUnit 4 for tests only.
- TarsosDSP was considered and deliberately not added: every required step is small enough to
  implement and test directly. JTransforms can be added later for FFT-based processing.
