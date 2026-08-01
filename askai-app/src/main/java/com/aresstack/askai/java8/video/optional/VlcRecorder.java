package com.aresstack.askai.java8.video.optional;

import com.aresstack.askai.java8.video.MediaRecorder;
import com.aresstack.askai.java8.video.RecordingException;
import com.aresstack.askai.java8.video.RecordingProfile;

import uk.co.caprica.vlcj.player.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.headless.HeadlessMediaPlayer;

import java.awt.Rectangle;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The productive VLC backend (ported from the WD4J/corenth {@code LibVlcRecorder}, vlcj 3.x): a headless
 * media player on the user's INSTALLED VLC captures {@code screen://} for the profile's bounds and
 * transcodes to H.264/MP4 via a {@code :sout} pipeline. AskAI starts no external process — libvlc runs
 * in-process through vlcj/JNA. {@link LibVlcLocator#configureRuntime()} must have succeeded before this
 * class is instantiated (the provider guarantees that; there is no fallback from here).
 */
public final class VlcRecorder implements MediaRecorder {

    private MediaPlayerFactory factory;
    private HeadlessMediaPlayer player;
    private volatile boolean recording;

    @Override
    public void start(RecordingProfile profile) throws Exception {
        if (recording) {
            return;
        }
        if (profile.getOutputFile().getParent() != null) {
            Files.createDirectories(profile.getOutputFile().getParent());
        }
        this.factory = new MediaPlayerFactory(new String[] {
                "--intf", "dummy", "--no-video-title-show", "--no-plugins-cache", "--quiet"
        });
        this.player = factory.newHeadlessMediaPlayer();
        boolean ok;
        try {
            ok = player.playMedia("screen://", buildOptions(profile));
        } catch (Throwable t) {
            safeRelease();
            throw new RecordingException("VLC capture failed to start: " + t.getMessage());
        }
        if (!ok) {
            safeRelease();
            throw new RecordingException(
                    "VLC refused to start the screen capture (playMedia returned false).");
        }
        recording = true;
    }

    @Override
    public void stop() {
        if (!recording) {
            return;
        }
        try {
            player.stop(); // finalizes the sout muxer so the MP4 is playable
        } finally {
            safeRelease();
            recording = false;
        }
    }

    @Override
    public boolean isRecording() {
        return recording;
    }

    private void safeRelease() {
        try {
            if (player != null) {
                player.release();
            }
        } catch (Throwable ignore) {
        }
        try {
            if (factory != null) {
                factory.release();
            }
        } catch (Throwable ignore) {
        }
        player = null;
        factory = null;
    }

    /** Screen-module options for the region plus the transcode/mux pipeline (reference defaults). */
    private static String[] buildOptions(RecordingProfile profile) {
        Rectangle bounds = profile.getSource().getBounds();
        int fps = Math.max(1, profile.getFps());
        // x264 needs even dimensions, exactly like the JCodec path.
        int width = bounds.width - (bounds.width % 2);
        int height = bounds.height - (bounds.height % 2);

        List<String> opts = new ArrayList<String>();
        opts.add(":screen-fps=" + fps);
        opts.add(":screen-left=" + Math.max(0, bounds.x));
        opts.add(":screen-top=" + Math.max(0, bounds.y));
        opts.add(":screen-width=" + width);
        opts.add(":screen-height=" + height);

        String dst = profile.getOutputFile().toString().replace('\\', '/');
        StringBuilder sout = new StringBuilder();
        sout.append(":sout=#transcode{vcodec=h264,fps=").append(fps)
                .append(",width=").append(width).append(",height=").append(height)
                .append(",venc=x264{crf=23,preset=veryfast},acodec=none}")
                .append(":std{access=file,mux=mp4,dst=").append(dst).append("}");
        opts.add(sout.toString());
        opts.add(":sout-keep");
        return opts.toArray(new String[0]);
    }
}
