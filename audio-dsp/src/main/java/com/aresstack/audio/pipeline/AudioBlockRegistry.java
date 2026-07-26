package com.aresstack.audio.pipeline;

import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The central registry of {@link AudioBlockDescriptor}s. It is the single place that knows every block
 * type: its parameters/defaults, capabilities, category, processor factory and canvas summary. The former
 * per-type switches in the profile processor, the inspector and the canvas all resolve through here, and a
 * new block is added by registering one descriptor.
 */
public final class AudioBlockRegistry {

    private static final AudioBlockRegistry INSTANCE = new AudioBlockRegistry();

    private final Map<AudioBlockType, AudioBlockDescriptor> descriptors =
            new LinkedHashMap<AudioBlockType, AudioBlockDescriptor>();

    private AudioBlockRegistry() {
        register(channelMixer());
        register(lowPass());
        register(highPass());
        register(bandPass());
        register(bandStop());
        register(resampler());
        register(dcOffsetRemoval());
        register(noiseGate());
        register(compressor());
        register(limiter());
        register(gain());
        register(parametricEqualizer());
        register(lowShelfEqualizer());
        register(highShelfEqualizer());
        register(voiceActivityDetection());
        register(expander());
        register(silenceTrimmer());
    }

    public static AudioBlockRegistry getInstance() {
        return INSTANCE;
    }

    private void register(AudioBlockDescriptor descriptor) {
        descriptors.put(descriptor.getType(), descriptor);
    }

    /** @return the descriptor for a type, never null (every {@link AudioBlockType} is registered). */
    public AudioBlockDescriptor descriptor(AudioBlockType type) {
        AudioBlockDescriptor descriptor = descriptors.get(type);
        if (descriptor == null) {
            throw new IllegalArgumentException("No descriptor registered for block type: " + type);
        }
        return descriptor;
    }

    /** @return the descriptor for a persisted type id, or null when the id is unknown (diagnosable). */
    public AudioBlockDescriptor descriptorForId(String typeId) {
        if (typeId == null) {
            return null;
        }
        for (AudioBlockDescriptor descriptor : descriptors.values()) {
            if (descriptor.getTypeId().equals(typeId.trim())) {
                return descriptor;
            }
        }
        return null;
    }

    public List<AudioBlockDescriptor> all() {
        return Collections.unmodifiableList(new ArrayList<AudioBlockDescriptor>(descriptors.values()));
    }

    public AudioBlockProcessor createProcessor(AudioBlockType type) {
        return descriptor(type).createProcessor();
    }

    public Map<String, String> defaultParameters(AudioBlockType type) {
        return descriptor(type).defaultParameters();
    }

    public AudioBlockDefinition defaultDefinition(AudioBlockType type, String id) {
        return descriptor(type).createDefaultDefinition(id);
    }

    /** @return a default definition of the type with a freshly generated block id (for "add block"). */
    public AudioBlockDefinition createDefaultDefinition(AudioBlockType type) {
        return descriptor(type).createDefaultDefinition("block-" + java.util.UUID.randomUUID().toString());
    }

    // ------------------------------------------------------------------ descriptor declarations

    private static AudioBlockDescriptor channelMixer() {
        return descriptor(AudioBlockType.CHANNEL_MIXER, AudioBlockCategory.INPUT_CHANNEL,
                Collections.singletonList(AudioParameterDescriptor.integer("channels", "Output channels", 1, 1, 1)),
                StaticBlockCapabilities.audioEffect(),
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.channelMixer();
                    }
                },
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return "Output: mono";
                    }
                });
    }

    private static AudioBlockDescriptor lowPass() {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.choice("implementation", "Filter design", "FIR_65", Arrays.asList(
                new AudioParameterChoice("FIR_65", "65-tap FIR (existing)"),
                new AudioParameterChoice("BUTTERWORTH", "Butterworth (iirj)"))));
        params.add(AudioParameterDescriptor.decimal("cutoffHz", "Cutoff (Hz)", 7200.0d, 1.0d, 96000.0d, 10.0d));
        params.add(AudioParameterDescriptor.integer("order", "Butterworth order", 4, 1, 12));
        return descriptor(AudioBlockType.LOW_PASS, AudioBlockCategory.FILTERS_EQ, params,
                StaticBlockCapabilities.audioEffect(),
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.lowPass();
                    }
                },
                filterSummary());
    }

    private static AudioBlockDescriptor highPass() {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.choice("implementation", "Filter design", "LEGACY_IIR", Arrays.asList(
                new AudioParameterChoice("LEGACY_IIR", "First-order IIR (existing)"),
                new AudioParameterChoice("BUTTERWORTH", "Butterworth (iirj)"))));
        params.add(AudioParameterDescriptor.decimal("cutoffHz", "Cutoff (Hz)", 80.0d, 1.0d, 96000.0d, 10.0d));
        params.add(AudioParameterDescriptor.integer("order", "Butterworth order", 2, 1, 12));
        return descriptor(AudioBlockType.HIGH_PASS, AudioBlockCategory.FILTERS_EQ, params,
                StaticBlockCapabilities.audioEffect(),
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.highPass();
                    }
                },
                filterSummary());
    }

    private static AudioBlockDescriptor bandPass() {
        return bandDescriptor(AudioBlockType.BAND_PASS, new SimpleAudioBlockDescriptor.ProcessorFactory() {
            public AudioBlockProcessor create() {
                return AudioBlockProcessors.bandPass();
            }
        });
    }

    private static AudioBlockDescriptor bandStop() {
        return bandDescriptor(AudioBlockType.BAND_STOP, new SimpleAudioBlockDescriptor.ProcessorFactory() {
            public AudioBlockProcessor create() {
                return AudioBlockProcessors.bandStop();
            }
        });
    }

    private static AudioBlockDescriptor bandDescriptor(AudioBlockType type,
                                                       SimpleAudioBlockDescriptor.ProcessorFactory factory) {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.decimal("centerHz", "Center (Hz)", 1000.0d, 1.0d, 96000.0d, 10.0d));
        params.add(AudioParameterDescriptor.decimal("widthHz", "Width (Hz)", 500.0d, 1.0d, 96000.0d, 10.0d));
        params.add(AudioParameterDescriptor.integer("order", "Filter order", 2, 1, 12));
        return descriptor(type, AudioBlockCategory.FILTERS_EQ, params, StaticBlockCapabilities.audioEffect(),
                factory,
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return block.getParameter("centerHz", "") + " Hz · width "
                                + block.getParameter("widthHz", "") + " Hz";
                    }
                });
    }

    private static AudioBlockDescriptor resampler() {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.integer("targetRateHz", "Target rate (Hz)", 16000, 4000, 192000));
        params.add(AudioParameterDescriptor.choice("quality", "Quality", "BALANCED", Arrays.asList(
                new AudioParameterChoice("FAST", "FAST"),
                new AudioParameterChoice("BALANCED", "BALANCED"),
                new AudioParameterChoice("HIGH", "HIGH"))));
        params.add(AudioParameterDescriptor.bool("hiddenAntiAliasing", "Hidden anti-alias filter", false));
        return descriptor(AudioBlockType.RESAMPLER, AudioBlockCategory.OUTPUT, params,
                StaticBlockCapabilities.audioEffect(),
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.resampler();
                    }
                },
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return block.getParameter("targetRateHz", "") + " Hz · "
                                + block.getParameter("quality", "BALANCED");
                    }
                });
    }

    private static AudioBlockDescriptor dcOffsetRemoval() {
        return descriptor(AudioBlockType.DC_OFFSET_REMOVAL, AudioBlockCategory.FILTERS_EQ,
                Collections.<AudioParameterDescriptor>emptyList(), StaticBlockCapabilities.audioEffect(),
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.dcOffsetRemoval();
                    }
                },
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return "Adaptive offset estimate";
                    }
                });
    }

    private static AudioBlockDescriptor noiseGate() {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.decimal("threshold", "Threshold", 300.0d, 0.0d, 32767.0d, 10.0d));
        params.add(AudioParameterDescriptor.decimal("closedGain", "Closed gain", 0.3d, 0.0d, 1.0d, 0.05d));
        params.add(AudioParameterDescriptor.decimal("attackMillis", "Attack (ms)", 5.0d, 0.0d, 5000.0d, 1.0d));
        params.add(AudioParameterDescriptor.decimal("releaseMillis", "Release (ms)", 150.0d, 0.0d, 10000.0d, 5.0d));
        return descriptor(AudioBlockType.NOISE_GATE, AudioBlockCategory.DYNAMICS, params,
                StaticBlockCapabilities.audioEffect(),
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.noiseGate();
                    }
                },
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return "Threshold " + block.getParameter("threshold", "");
                    }
                });
    }

    private static AudioBlockDescriptor compressor() {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.decimal("threshold", "Threshold", 12000.0d, 0.0d, 32767.0d, 100.0d));
        params.add(AudioParameterDescriptor.decimal("ratio", "Ratio", 3.0d, 1.0d, 30.0d, 0.5d));
        params.add(AudioParameterDescriptor.decimal("attackMillis", "Attack (ms)", 5.0d, 0.0d, 5000.0d, 1.0d));
        params.add(AudioParameterDescriptor.decimal("releaseMillis", "Release (ms)", 100.0d, 0.0d, 10000.0d, 5.0d));
        return descriptor(AudioBlockType.COMPRESSOR, AudioBlockCategory.DYNAMICS, params,
                StaticBlockCapabilities.audioEffect(),
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.compressor();
                    }
                },
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return block.getParameter("ratio", "") + ":1 above " + block.getParameter("threshold", "");
                    }
                });
    }

    private static AudioBlockDescriptor limiter() {
        return descriptor(AudioBlockType.LIMITER, AudioBlockCategory.OUTPUT,
                Collections.singletonList(AudioParameterDescriptor.integer("ceiling", "Ceiling", 30000, 1, 32767)),
                StaticBlockCapabilities.audioEffect(),
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.limiter();
                    }
                },
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return "Ceiling " + block.getParameter("ceiling", "");
                    }
                });
    }

    private static AudioBlockDescriptor gain() {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.decimal("gainDb", "Gain (dB)", 0.0d, -60.0d, 24.0d, 0.5d));
        return descriptor(AudioBlockType.GAIN, AudioBlockCategory.DYNAMICS, params,
                StaticBlockCapabilities.audioEffect(),
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.gain();
                    }
                },
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return formatDb(block.getDoubleParameter("gainDb", 0.0d));
                    }
                });
    }

    private static AudioBlockDescriptor parametricEqualizer() {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.decimal("centerHz", "Center (Hz)", 1000.0d, 1.0d, 96000.0d, 10.0d));
        params.add(AudioParameterDescriptor.decimal("gainDb", "Gain (dB)", 0.0d, -24.0d, 24.0d, 0.5d));
        params.add(AudioParameterDescriptor.decimal("q", "Q factor", 1.0d, 0.1d, 24.0d, 0.1d));
        return descriptor(AudioBlockType.PARAMETRIC_EQ, AudioBlockCategory.FILTERS_EQ, params,
                StaticBlockCapabilities.audioEffect(),
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.parametricEqualizer();
                    }
                },
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return formatHz(block.getDoubleParameter("centerHz", 0.0d)) + " · "
                                + formatDb(block.getDoubleParameter("gainDb", 0.0d)) + " · Q "
                                + formatQ(block.getDoubleParameter("q", 1.0d));
                    }
                });
    }

    private static AudioBlockDescriptor lowShelfEqualizer() {
        return shelfDescriptor(AudioBlockType.LOW_SHELF, 200.0d,
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.lowShelfEqualizer();
                    }
                });
    }

    private static AudioBlockDescriptor highShelfEqualizer() {
        return shelfDescriptor(AudioBlockType.HIGH_SHELF, 6000.0d,
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.highShelfEqualizer();
                    }
                });
    }

    private static AudioBlockDescriptor shelfDescriptor(AudioBlockType type, double defaultCutoffHz,
                                                        SimpleAudioBlockDescriptor.ProcessorFactory factory) {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.decimal("cutoffHz", "Cutoff (Hz)", defaultCutoffHz, 1.0d, 96000.0d, 10.0d));
        params.add(AudioParameterDescriptor.decimal("gainDb", "Gain (dB)", 0.0d, -24.0d, 24.0d, 0.5d));
        params.add(AudioParameterDescriptor.decimal("slope", "Shelf slope", 1.0d, 0.1d, 2.0d, 0.1d));
        return descriptor(type, AudioBlockCategory.FILTERS_EQ, params, StaticBlockCapabilities.audioEffect(),
                factory,
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return formatHz(block.getDoubleParameter("cutoffHz", 0.0d)) + " · "
                                + formatDb(block.getDoubleParameter("gainDb", 0.0d));
                    }
                });
    }

    private static AudioBlockDescriptor voiceActivityDetection() {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.decimal("sensitivity", "Sensitivity", 0.5d, 0.0d, 1.0d, 0.05d));
        params.add(AudioParameterDescriptor.decimal("minSpeechProbability", "Min speech probability",
                0.5d, 0.3d, 0.9d, 0.05d));
        params.add(AudioParameterDescriptor.integer("frameDurationMs", "Frame duration (ms)", 20, 10, 30));
        params.add(AudioParameterDescriptor.decimal("attackMs", "Attack (ms)", 50.0d, 20.0d, 300.0d, 5.0d));
        params.add(AudioParameterDescriptor.decimal("releaseMs", "Release (ms)", 300.0d, 100.0d, 1500.0d, 10.0d));
        params.add(AudioParameterDescriptor.decimal("hangoverMs", "Hangover (ms)", 200.0d, 0.0d, 1000.0d, 10.0d));
        params.add(AudioParameterDescriptor.decimal("minSpeechMs", "Min speech duration (ms)",
                80.0d, 0.0d, 500.0d, 5.0d));
        params.add(AudioParameterDescriptor.decimal("minSilenceMs", "Min silence duration (ms)",
                150.0d, 0.0d, 1000.0d, 10.0d));
        params.add(AudioParameterDescriptor.decimal("noiseAdaptationSpeed", "Noise adaptation speed",
                0.05d, 0.001d, 0.2d, 0.001d));
        params.add(AudioParameterDescriptor.bool("adaptNoiseDuringSpeech", "Adapt noise during speech", false));
        AudioBlockCapabilities capabilities = StaticBlockCapabilities.builder()
                .modifiesAudio(false)
                .producesMetadata(true)
                .framing(320, 0, 0) // requires framing; frame size is derived per rate at run time
                .build();
        return descriptor(AudioBlockType.VOICE_ACTIVITY_DETECTION, AudioBlockCategory.ANALYSIS, params,
                capabilities,
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.voiceActivityDetection();
                    }
                },
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return "Sensitivity "
                                + String.format(java.util.Locale.ROOT, "%.2f",
                                        block.getDoubleParameter("sensitivity", 0.5d))
                                + " · threshold "
                                + String.format(java.util.Locale.ROOT, "%.2f",
                                        block.getDoubleParameter("minSpeechProbability", 0.5d));
                    }
                });
    }

    private static AudioBlockDescriptor expander() {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.decimal("thresholdDb", "Threshold (dBFS)", -45.0d, -80.0d, 0.0d, 1.0d));
        params.add(AudioParameterDescriptor.decimal("ratio", "Ratio", 2.0d, 1.0d, 20.0d, 0.5d));
        params.add(AudioParameterDescriptor.decimal("kneeDb", "Knee (dB)", 6.0d, 0.0d, 24.0d, 1.0d));
        params.add(AudioParameterDescriptor.decimal("attackMs", "Attack (ms)", 10.0d, 0.0d, 500.0d, 1.0d));
        params.add(AudioParameterDescriptor.decimal("releaseMs", "Release (ms)", 200.0d, 10.0d, 5000.0d, 10.0d));
        params.add(AudioParameterDescriptor.decimal("holdMs", "Hold (ms)", 50.0d, 0.0d, 2000.0d, 10.0d));
        params.add(AudioParameterDescriptor.decimal("maxAttenuationDb", "Maximum attenuation (dB)",
                18.0d, 0.0d, 80.0d, 1.0d));
        params.add(AudioParameterDescriptor.decimal("detectorWindowMs", "Detector window (ms)",
                20.0d, 5.0d, 100.0d, 1.0d));
        params.add(AudioParameterDescriptor.bool("speechProtection", "Speech protection", false));
        params.add(AudioParameterDescriptor.decimal("minSpeechProbability", "Min speech probability",
                0.5d, 0.0d, 1.0d, 0.05d));
        AudioBlockCapabilities capabilities = StaticBlockCapabilities.builder()
                .framing(320, 0, 0)
                .consumesSpeechMetadata(true)
                .build();
        return descriptor(AudioBlockType.EXPANDER, AudioBlockCategory.DYNAMICS, params, capabilities,
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.expander();
                    }
                },
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        return formatHz0(block.getDoubleParameter("thresholdDb", 0.0d)) + " dB · "
                                + formatQ(block.getDoubleParameter("ratio", 1.0d)) + ":1 · max -"
                                + formatQ(block.getDoubleParameter("maxAttenuationDb", 0.0d)) + " dB";
                    }
                });
    }

    private static AudioBlockDescriptor silenceTrimmer() {
        List<AudioParameterDescriptor> params = new ArrayList<AudioParameterDescriptor>();
        params.add(AudioParameterDescriptor.bool("trimLeading", "Trim leading silence", true));
        params.add(AudioParameterDescriptor.bool("trimTrailing", "Trim trailing silence", true));
        params.add(AudioParameterDescriptor.decimal("minSpeechProbability", "Min speech probability",
                0.5d, 0.0d, 1.0d, 0.05d));
        params.add(AudioParameterDescriptor.decimal("preRollMs", "Pre-roll (ms)", 200.0d, 0.0d, 5000.0d, 10.0d));
        params.add(AudioParameterDescriptor.decimal("postRollMs", "Post-roll (ms)", 350.0d, 0.0d, 5000.0d, 10.0d));
        params.add(AudioParameterDescriptor.decimal("minRetainedMs", "Minimum retained (ms)",
                400.0d, 0.0d, 60000.0d, 50.0d));
        params.add(AudioParameterDescriptor.choice("noSpeechBehavior", "No-speech behavior", "KEEP_ORIGINAL",
                Arrays.asList(new AudioParameterChoice("KEEP_ORIGINAL", "Keep original"),
                        new AudioParameterChoice("FAIL", "Fail"))));
        params.add(AudioParameterDescriptor.bool("zeroCrossingAlignment", "Zero-crossing alignment", true));
        params.add(AudioParameterDescriptor.decimal("zeroCrossingSearchMs", "Zero-crossing search (ms)",
                5.0d, 0.0d, 100.0d, 1.0d));
        AudioBlockCapabilities capabilities = StaticBlockCapabilities.builder()
                .streaming(false)
                .requiresCompleteSignal(true)
                .requiresSpeechActivityTrack(true)
                .consumesSpeechMetadata(true)
                .changesDuration(true)
                .changesSampleCount(true)
                .build();
        return descriptor(AudioBlockType.SILENCE_TRIMMER, AudioBlockCategory.OUTPUT, params, capabilities,
                new SimpleAudioBlockDescriptor.ProcessorFactory() {
                    public AudioBlockProcessor create() {
                        return AudioBlockProcessors.silenceTrimmer();
                    }
                },
                new SimpleAudioBlockDescriptor.Summarizer() {
                    public String summarize(AudioBlockDefinition block) {
                        boolean lead = block.getBooleanParameter("trimLeading", true);
                        boolean trail = block.getBooleanParameter("trimTrailing", true);
                        String scope = lead && trail ? "Leading + trailing"
                                : lead ? "Leading" : trail ? "Trailing" : "Off";
                        return scope + " · " + formatHz0(block.getDoubleParameter("preRollMs", 0.0d)) + "/"
                                + formatHz0(block.getDoubleParameter("postRollMs", 0.0d)) + " ms";
                    }
                });
    }

    private static String formatHz0(double value) {
        return String.format(java.util.Locale.ROOT, "%.0f", value);
    }

    private static String formatDb(double value) {
        return (value >= 0.0d ? "+" : "") + String.format(java.util.Locale.ROOT, "%.1f", value) + " dB";
    }

    private static String formatHz(double value) {
        return String.format(java.util.Locale.ROOT, "%.0f", value) + " Hz";
    }

    private static String formatQ(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static SimpleAudioBlockDescriptor.Summarizer filterSummary() {
        return new SimpleAudioBlockDescriptor.Summarizer() {
            public String summarize(AudioBlockDefinition block) {
                return block.getParameter("cutoffHz", "") + " Hz · "
                        + filterDesignLabel(block.getParameter("implementation", ""));
            }
        };
    }

    private static String filterDesignLabel(String value) {
        if ("FIR_65".equals(value)) {
            return "65-tap FIR";
        }
        if ("LEGACY_IIR".equals(value)) {
            return "1st-order IIR";
        }
        if ("BUTTERWORTH".equals(value)) {
            return "Butterworth";
        }
        return value;
    }

    private static AudioBlockDescriptor descriptor(AudioBlockType type, AudioBlockCategory category,
                                                   List<AudioParameterDescriptor> params,
                                                   AudioBlockCapabilities capabilities,
                                                   SimpleAudioBlockDescriptor.ProcessorFactory factory,
                                                   SimpleAudioBlockDescriptor.Summarizer summarizer) {
        return new SimpleAudioBlockDescriptor(type, type.getDisplayName(), category, params,
                capabilities, factory, summarizer);
    }
}
