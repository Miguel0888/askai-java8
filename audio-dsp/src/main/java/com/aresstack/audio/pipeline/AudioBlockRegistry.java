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
