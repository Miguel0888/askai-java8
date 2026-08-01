package com.aresstack.askai.java8.video.optional;

import com.aresstack.askai.java8.video.MediaRecorder;
import com.aresstack.askai.java8.video.MediaRecorderProvider;

import java.io.File;

/**
 * OPTIONAL FFmpeg backend, prepared but not bundled. AskAI never downloads or starts an FFmpeg runtime on
 * its own; this provider is available only when the user pointed AskAI at an ffmpeg binary via
 * {@code -Daskai.video.ffmpeg=/path/to/ffmpeg} (or {@code ASKAI_VIDEO_FFMPEG}). Otherwise it is unavailable
 * and never offered — no silent fallback. The productive capture is wired in a later slice.
 */
public final class FfmpegRecorderProvider implements MediaRecorderProvider {

    public static final String ID = "ffmpeg";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "FFmpeg (uses your configured ffmpeg)";
    }

    @Override
    public boolean isAvailable() {
        String configured = System.getProperty("askai.video.ffmpeg",
                System.getenv("ASKAI_VIDEO_FFMPEG"));
        return configured != null && !configured.trim().isEmpty()
                && new File(configured.trim()).isFile();
    }

    @Override
    public MediaRecorder createRecorder() {
        throw new UnsupportedOperationException(
                "The FFmpeg backend is prepared but its capture is not wired yet; use JCodec.");
    }
}
