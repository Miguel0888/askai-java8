package com.aresstack.audio.profile;

import java.util.ArrayList;
import java.util.List;

/** Provide the immutable built-in profiles shipped with AskAI. */
public final class AudioProcessingProfiles {

    public static final String DEFAULT_PROFILE_ID = "default-speech";

    private AudioProcessingProfiles() {
    }

    public static AudioProcessingProfile defaultSpeech() {
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        blocks.add(AudioBlockDefinition.of("channel-mixer", AudioBlockType.CHANNEL_MIXER));
        blocks.add(AudioBlockDefinition.of("anti-alias-low-pass", AudioBlockType.LOW_PASS));
        blocks.add(AudioBlockDefinition.of("resampler", AudioBlockType.RESAMPLER));
        blocks.add(AudioBlockDefinition.of("dc-offset", AudioBlockType.DC_OFFSET_REMOVAL));
        blocks.add(AudioBlockDefinition.of("high-pass", AudioBlockType.HIGH_PASS));
        blocks.add(AudioBlockDefinition.of("noise-gate", AudioBlockType.NOISE_GATE));
        blocks.add(AudioBlockDefinition.of("compressor", AudioBlockType.COMPRESSOR));
        blocks.add(AudioBlockDefinition.of("limiter", AudioBlockType.LIMITER));
        return new AudioProcessingProfile(DEFAULT_PROFILE_ID, "Default speech", true, blocks);
    }
}
