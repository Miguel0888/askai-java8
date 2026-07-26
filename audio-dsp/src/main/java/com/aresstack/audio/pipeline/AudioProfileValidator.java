package com.aresstack.audio.pipeline;

import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Validate a profile without silently reordering it: report understandable problems (invalid band filter
 * range, out-of-range resampler rate, a filter above Nyquist for a given input rate, a resampler that is
 * not preceded by a channel mixer) so the editor can warn instead of rearranging the user's pipeline.
 */
public final class AudioProfileValidator {

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    /** One validation finding, tied to a block when applicable. */
    public static final class Message {
        private final Severity severity;
        private final String blockId;
        private final String text;

        public Message(Severity severity, String blockId, String text) {
            this.severity = severity;
            this.blockId = blockId;
            this.text = text;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getBlockId() {
            return blockId;
        }

        public String getText() {
            return text;
        }
    }

    /** Validate structural correctness that does not depend on the input rate. */
    public List<Message> validate(AudioProcessingProfile profile) {
        return validate(profile, null);
    }

    /**
     * Validate the profile; when {@code inputFormat} is given, also check filter frequencies against the
     * running Nyquist frequency as the format changes through the pipeline.
     */
    public List<Message> validate(AudioProcessingProfile profile, PcmAudioFormat inputFormat) {
        List<Message> messages = new ArrayList<Message>();
        if (profile == null) {
            return messages;
        }
        int currentRate = inputFormat == null ? 0 : inputFormat.getSampleRateHz();
        boolean sawChannelMixer = false;
        List<AudioBlockDefinition> blocks = profile.getBlocks();
        for (int i = 0; i < blocks.size(); i++) {
            AudioBlockDefinition block = blocks.get(i);
            AudioBlockType type = block.getType();
            if (type == AudioBlockType.CHANNEL_MIXER) {
                sawChannelMixer = true;
            }
            if (!block.isEnabled()) {
                continue; // a bypassed block imposes no requirement
            }
            switch (type) {
                case BAND_PASS:
                case BAND_STOP:
                    validateBand(messages, block, currentRate);
                    break;
                case LOW_PASS:
                case HIGH_PASS:
                    validateCutoff(messages, block, currentRate);
                    break;
                case RESAMPLER:
                    validateResampler(messages, block, sawChannelMixer);
                    currentRate = block.getIntParameter("targetRateHz", currentRate);
                    break;
                case GAIN:
                    validateGain(messages, block);
                    break;
                case PARAMETRIC_EQ:
                    validateEqBand(messages, block, "centerHz", currentRate, true);
                    break;
                case LOW_SHELF:
                case HIGH_SHELF:
                    validateEqBand(messages, block, "cutoffHz", currentRate, false);
                    break;
                case VOICE_ACTIVITY_DETECTION:
                    validateVoiceActivity(messages, block);
                    break;
                default:
                    break;
            }
        }
        return messages;
    }

    private void validateBand(List<Message> messages, AudioBlockDefinition block, int rate) {
        double center = block.getDoubleParameter("centerHz", 0.0d);
        double width = block.getDoubleParameter("widthHz", 0.0d);
        if (center <= 0.0d || width <= 0.0d || center - width / 2.0d <= 0.0d) {
            messages.add(new Message(Severity.ERROR, block.getId(),
                    block.getType().getDisplayName() + ": the band (center " + center + " Hz, width "
                            + width + " Hz) must stay above 0 Hz."));
            return;
        }
        if (rate > 0 && center + width / 2.0d >= rate / 2.0d) {
            messages.add(new Message(Severity.WARNING, block.getId(),
                    block.getType().getDisplayName() + ": the band reaches beyond the Nyquist frequency ("
                            + (rate / 2) + " Hz) at this point and will be limited."));
        }
    }

    private void validateCutoff(List<Message> messages, AudioBlockDefinition block, int rate) {
        double cutoff = block.getDoubleParameter("cutoffHz", 0.0d);
        if (cutoff <= 0.0d) {
            messages.add(new Message(Severity.ERROR, block.getId(),
                    block.getType().getDisplayName() + ": the cutoff frequency must be positive."));
            return;
        }
        if (rate > 0 && cutoff >= rate / 2.0d) {
            messages.add(new Message(Severity.WARNING, block.getId(),
                    block.getType().getDisplayName() + ": the cutoff (" + cutoff
                            + " Hz) is at or above the Nyquist frequency (" + (rate / 2) + " Hz) here."));
        }
    }

    private void validateGain(List<Message> messages, AudioBlockDefinition block) {
        double gainDb = block.getDoubleParameter("gainDb", 0.0d);
        if (!isFinite(gainDb)) {
            messages.add(new Message(Severity.ERROR, block.getId(),
                    "Gain: the value must be a finite dB number."));
            return;
        }
        if (Math.abs(gainDb) > 48.0d) {
            messages.add(new Message(Severity.WARNING, block.getId(),
                    "Gain: " + gainDb + " dB is extreme and will heavily clip or mute the signal."));
        }
    }

    private void validateEqBand(List<Message> messages, AudioBlockDefinition block, String frequencyKey,
                                int rate, boolean peaking) {
        double frequency = block.getDoubleParameter(frequencyKey, 0.0d);
        double gainDb = block.getDoubleParameter("gainDb", 0.0d);
        String name = block.getType().getDisplayName();
        if (!isFinite(frequency) || frequency <= 0.0d) {
            messages.add(new Message(Severity.ERROR, block.getId(),
                    name + ": the frequency must be a finite value above 0 Hz."));
            return;
        }
        if (rate > 0 && frequency >= rate / 2.0d) {
            messages.add(new Message(Severity.WARNING, block.getId(),
                    name + ": the frequency (" + frequency + " Hz) is at or above the Nyquist frequency ("
                            + (rate / 2) + " Hz) here and the band will be bypassed."));
        }
        if (peaking) {
            double q = block.getDoubleParameter("q", 0.0d);
            if (!isFinite(q) || q <= 0.0d) {
                messages.add(new Message(Severity.ERROR, block.getId(),
                        name + ": the Q factor must be a finite value above 0."));
            }
        } else {
            double slope = block.getDoubleParameter("slope", 0.0d);
            if (!isFinite(slope) || slope <= 0.0d) {
                messages.add(new Message(Severity.ERROR, block.getId(),
                        name + ": the shelf slope must be a finite value above 0."));
            }
        }
        if (!isFinite(gainDb)) {
            messages.add(new Message(Severity.ERROR, block.getId(),
                    name + ": the gain must be a finite dB value."));
        } else if (Math.abs(gainDb) > 36.0d) {
            messages.add(new Message(Severity.WARNING, block.getId(),
                    name + ": " + gainDb + " dB is an extreme equalizer gain."));
        }
    }

    private void validateVoiceActivity(List<Message> messages, AudioBlockDefinition block) {
        String name = block.getType().getDisplayName();
        rangeError(messages, block, name, "sensitivity", "Sensitivity", 0.0d, 1.0d);
        rangeError(messages, block, name, "minSpeechProbability", "Minimum speech probability", 0.0d, 1.0d);
        double frame = block.getDoubleParameter("frameDurationMs", 20.0d);
        if (!isFinite(frame) || frame <= 0.0d) {
            messages.add(new Message(Severity.ERROR, block.getId(),
                    name + ": the frame duration must be a finite value above 0 ms."));
        }
        nonNegativeError(messages, block, name, "attackMs", "Attack");
        nonNegativeError(messages, block, name, "releaseMs", "Release");
        nonNegativeError(messages, block, name, "hangoverMs", "Hangover");
        nonNegativeError(messages, block, name, "minSpeechMs", "Minimum speech duration");
        nonNegativeError(messages, block, name, "minSilenceMs", "Minimum silence duration");
        double adapt = block.getDoubleParameter("noiseAdaptationSpeed", 0.05d);
        if (!isFinite(adapt) || adapt <= 0.0d || adapt > 0.5d) {
            messages.add(new Message(Severity.ERROR, block.getId(),
                    name + ": the noise adaptation speed must be a finite value in (0, 0.5]."));
        }
    }

    private void rangeError(List<Message> messages, AudioBlockDefinition block, String name,
                            String key, String label, double min, double max) {
        double value = block.getDoubleParameter(key, Double.NaN);
        if (!isFinite(value) || value < min || value > max) {
            messages.add(new Message(Severity.ERROR, block.getId(),
                    name + ": " + label + " must be a finite value between " + min + " and " + max + "."));
        }
    }

    private void nonNegativeError(List<Message> messages, AudioBlockDefinition block, String name,
                                  String key, String label) {
        double value = block.getDoubleParameter(key, 0.0d);
        if (!isFinite(value) || value < 0.0d) {
            messages.add(new Message(Severity.ERROR, block.getId(),
                    name + ": " + label + " must be a finite value of 0 ms or more."));
        }
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private void validateResampler(List<Message> messages, AudioBlockDefinition block, boolean sawChannelMixer) {
        int target = block.getIntParameter("targetRateHz", 0);
        if (target < 4000 || target > 192000) {
            messages.add(new Message(Severity.ERROR, block.getId(),
                    "Resampler: the target rate " + target + " Hz is outside the supported 4000–192000 Hz range."));
        }
        if (!sawChannelMixer) {
            messages.add(new Message(Severity.WARNING, block.getId(),
                    "Resampler: place a channel mixer before the resampler so the input is mono."));
        }
    }
}
