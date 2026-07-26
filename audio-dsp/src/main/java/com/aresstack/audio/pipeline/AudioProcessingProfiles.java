package com.aresstack.audio.pipeline;

import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Provide the immutable built-in profiles shipped with AskAI. Lives in {@code pipeline} (not
 * {@code profile}) because it builds its blocks from the {@link AudioBlockRegistry} descriptors — the
 * single source of default parameters — which keeps {@code profile} free of any dependency back on the
 * registry.
 */
public final class AudioProcessingProfiles {

    public static final String DEFAULT_PROFILE_ID = "default-speech";

    private AudioProcessingProfiles() {
    }

    public static AudioProcessingProfile defaultSpeech() {
        AudioBlockRegistry registry = AudioBlockRegistry.getInstance();
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        blocks.add(registry.defaultDefinition(AudioBlockType.CHANNEL_MIXER, "channel-mixer"));
        blocks.add(registry.defaultDefinition(AudioBlockType.LOW_PASS, "anti-alias-low-pass"));
        blocks.add(registry.defaultDefinition(AudioBlockType.RESAMPLER, "resampler"));
        blocks.add(registry.defaultDefinition(AudioBlockType.DC_OFFSET_REMOVAL, "dc-offset"));
        blocks.add(registry.defaultDefinition(AudioBlockType.HIGH_PASS, "high-pass"));
        blocks.add(registry.defaultDefinition(AudioBlockType.NOISE_GATE, "noise-gate"));
        blocks.add(registry.defaultDefinition(AudioBlockType.COMPRESSOR, "compressor"));
        blocks.add(registry.defaultDefinition(AudioBlockType.LIMITER, "limiter"));
        return new AudioProcessingProfile(DEFAULT_PROFILE_ID, "Default speech", true, blocks);
    }
}
