package com.aresstack.askai.agent.model.speech;

/**
 * Host speech-output (text-to-speech) service for plugins: when the user selected a MODEL voice in
 * the central AskAI settings (chat settings → Audio &amp; Dictation), this port speaks with it;
 * otherwise the port is inactive and the caller keeps its own default (e.g. the Windows OS voice).
 * The Windows voice deliberately stays the plugin-side default — the model voice is an opt-in
 * upgrade, never a silent replacement.
 *
 * <p>Contract: {@link #speak(String)} is BLOCKING (synthesis + playback) and must be called off the
 * EDT; a second {@code speak} implicitly stops the first. {@code speak} returns {@code false} when
 * no model voice is active or the engine failed — the caller then falls back to its default voice,
 * so read-aloud never goes silent because of a model problem.</p>
 */
public interface SpeechSynthesisPort {

    /** @return whether a model voice is selected AND installed (i.e. {@code speak} would use it). */
    boolean isModelVoiceActive();

    /** @return a short human label of the active model voice, or "" when none is active. */
    String describeActiveVoice();

    /**
     * Speak plain text (no markdown) with the active model voice, blocking until playback finishes
     * or {@link #stop()} interrupts it.
     *
     * @return true when the model voice spoke (even if interrupted); false when no model voice is
     *         active or synthesis failed — the caller should fall back to its default voice
     */
    boolean speak(String plainText);

    /** Stop the current utterance immediately; no-op when silent. */
    void stop();
}
