package com.aresstack.audio.pipeline;

import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.dsp.ButterworthFilterProcessor;
import com.aresstack.audio.dsp.CompressorProcessor;
import com.aresstack.audio.dsp.DcOffsetRemovalProcessor;
import com.aresstack.audio.dsp.FirLowPassProcessor;
import com.aresstack.audio.dsp.GainProcessor;
import com.aresstack.audio.dsp.HighPassFilterProcessor;
import com.aresstack.audio.dsp.HighShelfEqualizerProcessor;
import com.aresstack.audio.dsp.LimiterProcessor;
import com.aresstack.audio.dsp.LowShelfEqualizerProcessor;
import com.aresstack.audio.dsp.ParametricEqualizerProcessor;
import com.aresstack.audio.dsp.Pcm16Processor;
import com.aresstack.audio.dsp.Pcm16Resampler;
import com.aresstack.audio.dsp.PcmChannelConverter;
import com.aresstack.audio.dsp.ResamplingQuality;
import com.aresstack.audio.dsp.SoftNoiseGateProcessor;
import com.aresstack.audio.profile.AudioBlockDefinition;

/**
 * Buffer-level {@link AudioBlockProcessor} factories for the built-in blocks. Each factory reproduces the
 * exact behavior the former {@code AudioProfileProcessor} switch had, so a profile's audio output is
 * unchanged: structural blocks (channel mixer, resampler) return a re-formatted buffer, and filter/dynamics
 * blocks wrap a freshly created {@link Pcm16Processor} and process in place.
 */
final class AudioBlockProcessors {

    /** Creates the {@link Pcm16Processor} for one block from its parameters. */
    private interface Pcm16Factory {
        Pcm16Processor create(AudioBlockDefinition block);
    }

    private AudioBlockProcessors() {
    }

    static AudioBlockProcessor channelMixer() {
        return new AudioBlockProcessor() {
            public AudioBuffer process(AudioBuffer input, AudioBlockDefinition block, AudioProcessingContext context) {
                int target = block.getIntParameter("channels", 1);
                if (target != 1) {
                    throw new IllegalArgumentException("Only mono channel mixing is supported at present.");
                }
                if (input.getFormat().getChannels() == 1) {
                    return input;
                }
                short[] mono = PcmChannelConverter.downmixToMono(input.getSamples(),
                        input.getSamples().length, input.getFormat().getChannels());
                PcmAudioFormat format = new PcmAudioFormat(input.getFormat().getSampleRateHz(), 1,
                        input.getFormat().getBitsPerSample());
                return new AudioBuffer(mono, format);
            }
        };
    }

    static AudioBlockProcessor resampler() {
        return new AudioBlockProcessor() {
            public AudioBuffer process(AudioBuffer input, AudioBlockDefinition block, AudioProcessingContext context) {
                if (input.getFormat().getChannels() != 1) {
                    throw new IllegalArgumentException("Place a channel mixer before the resampler.");
                }
                int targetRate = block.getIntParameter("targetRateHz", 16000);
                ResamplingQuality quality = ResamplingQuality.parse(block.getParameter("quality", "BALANCED"));
                boolean hiddenAntiAliasing = block.getBooleanParameter("hiddenAntiAliasing", false);
                short[] samples = Pcm16Resampler.resample(input.getSamples(),
                        input.getFormat().getSampleRateHz(), targetRate, quality, hiddenAntiAliasing);
                PcmAudioFormat format = new PcmAudioFormat(targetRate, input.getFormat().getChannels(),
                        input.getFormat().getBitsPerSample());
                return new AudioBuffer(samples, format);
            }
        };
    }

    static AudioBlockProcessor lowPass() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                double cutoffHz = block.getDoubleParameter("cutoffHz", 7200.0d);
                if ("BUTTERWORTH".equalsIgnoreCase(block.getParameter("implementation", "FIR_65"))) {
                    return ButterworthFilterProcessor.lowPass(block.getIntParameter("order", 4), cutoffHz);
                }
                return new FirLowPassProcessor(cutoffHz);
            }
        });
    }

    static AudioBlockProcessor highPass() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                double cutoffHz = block.getDoubleParameter("cutoffHz", 80.0d);
                if ("BUTTERWORTH".equalsIgnoreCase(block.getParameter("implementation", "LEGACY_IIR"))) {
                    return ButterworthFilterProcessor.highPass(block.getIntParameter("order", 2), cutoffHz);
                }
                return new HighPassFilterProcessor(cutoffHz);
            }
        });
    }

    static AudioBlockProcessor bandPass() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                return ButterworthFilterProcessor.bandPass(block.getIntParameter("order", 2),
                        block.getDoubleParameter("centerHz", 1000.0d),
                        block.getDoubleParameter("widthHz", 500.0d));
            }
        });
    }

    static AudioBlockProcessor bandStop() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                return ButterworthFilterProcessor.bandStop(block.getIntParameter("order", 2),
                        block.getDoubleParameter("centerHz", 1000.0d),
                        block.getDoubleParameter("widthHz", 500.0d));
            }
        });
    }

    static AudioBlockProcessor dcOffsetRemoval() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                return new DcOffsetRemovalProcessor();
            }
        });
    }

    static AudioBlockProcessor noiseGate() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                return new SoftNoiseGateProcessor(
                        block.getDoubleParameter("threshold", 300.0d),
                        block.getDoubleParameter("closedGain", 0.3d),
                        block.getDoubleParameter("attackMillis", 5.0d),
                        block.getDoubleParameter("releaseMillis", 150.0d));
            }
        });
    }

    static AudioBlockProcessor compressor() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                return new CompressorProcessor(
                        block.getDoubleParameter("threshold", 12000.0d),
                        block.getDoubleParameter("ratio", 3.0d),
                        block.getDoubleParameter("attackMillis", 5.0d),
                        block.getDoubleParameter("releaseMillis", 100.0d));
            }
        });
    }

    static AudioBlockProcessor limiter() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                return new LimiterProcessor(block.getIntParameter("ceiling", 30000));
            }
        });
    }

    static AudioBlockProcessor gain() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                return new GainProcessor(block.getDoubleParameter("gainDb", 0.0d));
            }
        });
    }

    static AudioBlockProcessor parametricEqualizer() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                return new ParametricEqualizerProcessor(
                        block.getDoubleParameter("centerHz", 1000.0d),
                        block.getDoubleParameter("gainDb", 0.0d),
                        block.getDoubleParameter("q", 1.0d));
            }
        });
    }

    static AudioBlockProcessor lowShelfEqualizer() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                return new LowShelfEqualizerProcessor(
                        block.getDoubleParameter("cutoffHz", 200.0d),
                        block.getDoubleParameter("gainDb", 0.0d),
                        block.getDoubleParameter("slope", 1.0d));
            }
        });
    }

    static AudioBlockProcessor highShelfEqualizer() {
        return inPlace(new Pcm16Factory() {
            public Pcm16Processor create(AudioBlockDefinition block) {
                return new HighShelfEqualizerProcessor(
                        block.getDoubleParameter("cutoffHz", 6000.0d),
                        block.getDoubleParameter("gainDb", 0.0d),
                        block.getDoubleParameter("slope", 1.0d));
            }
        });
    }

    /** Wrap a per-block {@link Pcm16Processor} as an in-place, format-preserving buffer processor. */
    private static AudioBlockProcessor inPlace(final Pcm16Factory factory) {
        return new AudioBlockProcessor() {
            public AudioBuffer process(AudioBuffer input, AudioBlockDefinition block, AudioProcessingContext context) {
                Pcm16Processor processor = factory.create(block);
                processor.process(input.getSamples(), input.getSamples().length, input.getFormat());
                return input;
            }
        };
    }
}
