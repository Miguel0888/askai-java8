package com.aresstack.audio.dsp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The per-run sequence of {@link SpeechActivityMetadata}, one entry per analyzed frame, in processing
 * order. Produced by the voice-activity block and readable by later blocks through the processing context.
 * It is transient runtime state for a single pipeline run and is never persisted into a profile.
 */
public final class SpeechActivityTrack {

    private final List<SpeechActivityMetadata> frames = new ArrayList<SpeechActivityMetadata>();

    public void add(SpeechActivityMetadata metadata) {
        if (metadata != null) {
            frames.add(metadata);
        }
    }

    public List<SpeechActivityMetadata> getFrames() {
        return Collections.unmodifiableList(frames);
    }

    public int size() {
        return frames.size();
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public SpeechActivityMetadata getLatest() {
        return frames.isEmpty() ? null : frames.get(frames.size() - 1);
    }

    public int activeFrameCount() {
        int count = 0;
        for (SpeechActivityMetadata frame : frames) {
            if (frame.isSpeechActive()) {
                count++;
            }
        }
        return count;
    }
}
