package com.aresstack.askai.java8.tts;

import com.aresstack.askai.java8.settings.AskAiPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * On-disk layout of the Piper speech-output engine and its installed voices:
 * {@code %APPDATA%\.askai-java8\tts\piper\} (engine: piper.exe + espeak data + dlls) and
 * {@code %APPDATA%\.askai-java8\tts\voices\<voiceId>\} (one directory per voice: .onnx + .onnx.json).
 * "Installed" is fail-closed: a voice counts only when BOTH files exist — a half-written directory
 * (crashed download) is invisible and simply reinstalled.
 */
public final class PiperTtsStore {

    private final Path root;

    public PiperTtsStore() {
        this(AskAiPaths.appDirectory().resolve("tts"));
    }

    /** Visible for tests: an explicit root. */
    public PiperTtsStore(Path root) {
        this.root = root;
    }

    public Path engineDirectory() {
        return root.resolve("piper");
    }

    public Path engineExecutable() {
        return engineDirectory().resolve("piper.exe");
    }

    public boolean isEngineInstalled() {
        return Files.isRegularFile(engineExecutable());
    }

    public Path voiceDirectory(PiperVoice voice) {
        return root.resolve("voices").resolve(voice.getId());
    }

    public Path voiceModelFile(PiperVoice voice) {
        return voiceDirectory(voice).resolve(voice.onnxFileName());
    }

    public Path voiceConfigFile(PiperVoice voice) {
        return voiceDirectory(voice).resolve(voice.configFileName());
    }

    public boolean isVoiceInstalled(PiperVoice voice) {
        return voice != null
                && Files.isRegularFile(voiceModelFile(voice))
                && Files.isRegularFile(voiceConfigFile(voice));
    }

    /** @return the curated voices that are fully installed (engine not required), catalog order. */
    public List<PiperVoice> installedVoices() {
        List<PiperVoice> installed = new ArrayList<PiperVoice>();
        for (PiperVoice voice : PiperVoiceCatalog.curated()) {
            if (isVoiceInstalled(voice)) {
                installed.add(voice);
            }
        }
        return installed;
    }

    /** @return whether this voice is fully usable: engine + both voice files present. */
    public boolean isReadyToSpeak(PiperVoice voice) {
        return isEngineInstalled() && isVoiceInstalled(voice);
    }
}
