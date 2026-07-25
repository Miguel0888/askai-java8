package com.aresstack.audio.dsp;

import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import java.util.List;

/** Execute a stored profile over a complete PCM buffer and update the format after structural blocks. */
public final class AudioProfileProcessor {

    public AudioBuffer process(AudioBuffer input, AudioProcessingProfile profile) {
        if (input == null) {
            throw new IllegalArgumentException("Input buffer must not be null.");
        }
        if (profile == null) {
            throw new IllegalArgumentException("Profile must not be null.");
        }
        AudioBuffer current = new AudioBuffer(copy(input.getSamples()), input.getFormat());
        List<AudioBlockDefinition> blocks = profile.getBlocks();
        for (int i = 0; i < blocks.size(); i++) {
            AudioBlockDefinition block = blocks.get(i);
            if (block.isEnabled()) {
                current = apply(current, block);
            }
        }
        return current;
    }

    private AudioBuffer apply(AudioBuffer input, AudioBlockDefinition block) {
        AudioBlockType type = block.getType();
        if (type == AudioBlockType.CHANNEL_MIXER) {
            return mixChannels(input, block);
        }
        if (type == AudioBlockType.RESAMPLER) {
            return resample(input, block);
        }
        Pcm16Processor processor = createProcessor(block);
        processor.process(input.getSamples(), input.getSamples().length, input.getFormat());
        return input;
    }

    private AudioBuffer mixChannels(AudioBuffer input, AudioBlockDefinition block) {
        int targetChannels = block.getIntParameter("channels", 1);
        if (targetChannels != 1) {
            throw new IllegalArgumentException("Only mono channel mixing is supported at present.");
        }
        if (input.getFormat().getChannels() == 1) {
            return input;
        }
        short[] mono = PcmChannelConverter.downmixToMono(input.getSamples(), input.getSamples().length,
                input.getFormat().getChannels());
        PcmAudioFormat format = new PcmAudioFormat(input.getFormat().getSampleRateHz(), 1,
                input.getFormat().getBitsPerSample());
        return new AudioBuffer(mono, format);
    }

    private AudioBuffer resample(AudioBuffer input, AudioBlockDefinition block) {
        if (input.getFormat().getChannels() != 1) {
            throw new IllegalArgumentException("Place a channel mixer before the resampler.");
        }
        int targetRate = block.getIntParameter("targetRateHz", 16000);
        ResamplingQuality quality = ResamplingQuality.parse(block.getParameter("quality", "BALANCED"));
        boolean hiddenAntiAliasing = block.getBooleanParameter("hiddenAntiAliasing", false);
        short[] samples = Pcm16Resampler.resample(input.getSamples(), input.getFormat().getSampleRateHz(),
                targetRate, quality, hiddenAntiAliasing);
        PcmAudioFormat format = new PcmAudioFormat(targetRate, input.getFormat().getChannels(),
                input.getFormat().getBitsPerSample());
        return new AudioBuffer(samples, format);
    }

    private Pcm16Processor createProcessor(AudioBlockDefinition block) {
        switch (block.getType()) {
            case LOW_PASS:
                return createLowPassProcessor(block);
            case HIGH_PASS:
                return createHighPassProcessor(block);
            case BAND_PASS:
                return ButterworthFilterProcessor.bandPass(
                        block.getIntParameter("order", 2),
                        block.getDoubleParameter("centerHz", 1000.0d),
                        block.getDoubleParameter("widthHz", 500.0d));
            case BAND_STOP:
                return ButterworthFilterProcessor.bandStop(
                        block.getIntParameter("order", 2),
                        block.getDoubleParameter("centerHz", 1000.0d),
                        block.getDoubleParameter("widthHz", 500.0d));
            case DC_OFFSET_REMOVAL:
                return new DcOffsetRemovalProcessor();
            case NOISE_GATE:
                return new SoftNoiseGateProcessor(
                        block.getDoubleParameter("threshold", 300.0d),
                        block.getDoubleParameter("closedGain", 0.3d),
                        block.getDoubleParameter("attackMillis", 5.0d),
                        block.getDoubleParameter("releaseMillis", 150.0d));
            case COMPRESSOR:
                return new CompressorProcessor(
                        block.getDoubleParameter("threshold", 12000.0d),
                        block.getDoubleParameter("ratio", 3.0d),
                        block.getDoubleParameter("attackMillis", 5.0d),
                        block.getDoubleParameter("releaseMillis", 100.0d));
            case LIMITER:
                return new LimiterProcessor(block.getIntParameter("ceiling", 30000));
            default:
                throw new IllegalArgumentException("Block requires structural handling: " + block.getType());
        }
    }

    private Pcm16Processor createLowPassProcessor(AudioBlockDefinition block) {
        double cutoffHz = block.getDoubleParameter("cutoffHz", 7200.0d);
        if ("BUTTERWORTH".equalsIgnoreCase(block.getParameter("implementation", "FIR_65"))) {
            return ButterworthFilterProcessor.lowPass(
                    block.getIntParameter("order", 4), cutoffHz);
        }
        return new FirLowPassProcessor(cutoffHz);
    }

    private Pcm16Processor createHighPassProcessor(AudioBlockDefinition block) {
        double cutoffHz = block.getDoubleParameter("cutoffHz", 80.0d);
        if ("BUTTERWORTH".equalsIgnoreCase(block.getParameter("implementation", "LEGACY_IIR"))) {
            return ButterworthFilterProcessor.highPass(
                    block.getIntParameter("order", 2), cutoffHz);
        }
        return new HighPassFilterProcessor(cutoffHz);
    }

    private static short[] copy(short[] input) {
        short[] copy = new short[input.length];
        System.arraycopy(input, 0, copy, 0, input.length);
        return copy;
    }
}
