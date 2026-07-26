package com.aresstack.askai.java8.audio.preview;

/** Describe how much audio a backend accepted before completing. */
final class PlaybackMetrics {

    private final long framePosition;
    private final int byteCount;

    PlaybackMetrics(long framePosition, int byteCount) {
        this.framePosition = framePosition;
        this.byteCount = byteCount;
    }

    long getFramePosition() {
        return framePosition;
    }

    int getByteCount() {
        return byteCount;
    }
}
