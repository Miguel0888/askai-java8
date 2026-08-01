package com.aresstack.askai.java8.video.optional;

import com.aresstack.askai.java8.video.MediaRecorder;
import com.aresstack.askai.java8.video.MediaRecorderProvider;

/**
 * OPTIONAL VLC backend. AskAI bundles only the vlcj binding — libvlc must already be installed by the
 * user, and availability is reported strictly through {@link LibVlcLocator}. When VLC is absent this
 * backend is unavailable and the app never silently switches to another one.
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
        if (!LibVlcLocator.configureRuntime()) {
            throw new IllegalStateException(
                    "No VLC installation was found. Install VLC or choose another backend.");
        }
        return new VlcRecorder();
    }
}
