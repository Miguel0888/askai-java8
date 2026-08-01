package com.aresstack.askai.java8.video.optional;

import com.aresstack.askai.java8.video.MediaRecorder;
import com.aresstack.askai.java8.video.MediaRecorderProvider;

/**
 * OPTIONAL VLC backend, prepared but not bundled. It reports availability strictly through
 * {@link LibVlcLocator}; when the VLC runtime/binding is absent it is unavailable and never offered — the
 * app never silently switches to another backend. The productive VLC capture (vlcj) is wired in a later
 * slice; until then {@link #createRecorder()} refuses clearly instead of pretending to record.
 */
public final class VlcRecorderProvider implements MediaRecorderProvider {

    public static final String ID = "vlc";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "VLC (uses your installed VLC)";
    }

    @Override
    public boolean isAvailable() {
        return LibVlcLocator.isAvailable();
    }

    @Override
    public MediaRecorder createRecorder() {
        throw new UnsupportedOperationException(
                "The VLC backend is prepared but its capture is not wired yet; use JCodec.");
    }
}
