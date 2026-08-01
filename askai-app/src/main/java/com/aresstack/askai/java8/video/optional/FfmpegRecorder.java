package com.aresstack.askai.java8.video.optional;

import com.aresstack.askai.java8.video.MediaRecorder;
import com.aresstack.askai.java8.video.RecordingProfile;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The productive FFmpeg/JavaCV backend: the same portable {@link Robot} region grab as the JCodec path,
 * but encoded through {@code FFmpegFrameRecorder} (native x264 — faster and smaller files than pure-Java
 * JCodec). This class is ONLY loaded after {@link FfmpegRuntimeLoader#isReady()} confirmed the JavaCV
 * classes are attached; the jars themselves reach the machine exclusively through the user-confirmed
 * download in {@link FfmpegRuntimeLoader}. Not Swing-aware.
 */
public final class FfmpegRecorder implements MediaRecorder {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Exception> failure = new AtomicReference<Exception>();
    private Thread worker;
    private CountDownLatch finished;

    @Override
    public void start(final RecordingProfile profile) throws Exception {
        if (running.get()) {
            return;
        }
        final Robot robot;
        try {
            robot = new Robot();
        } catch (AWTException headless) {
            throw new Exception("Screen capture is not available (headless environment)", headless);
        }
        Rectangle bounds = profile.getSource().getBounds();
        // x264 requires even dimensions, exactly like the JCodec path.
        final int captureW = bounds.width - (bounds.width % 2);
        final int captureH = bounds.height - (bounds.height % 2);
        final Rectangle grab = new Rectangle(bounds.x, bounds.y, captureW, captureH);
        final int fps = Math.max(1, profile.getFps());

        BufferedImage probe = robot.createScreenCapture(grab);
        if (probe == null) {
            throw new Exception("Screen capture returned no image for bounds " + grab);
        }
        if (profile.getOutputFile().getParent() != null) {
            Files.createDirectories(profile.getOutputFile().getParent());
        }
        final FFmpegFrameRecorder recorder =
                new FFmpegFrameRecorder(profile.getOutputFile().toFile(), captureW, captureH);
        recorder.setFormat("mp4");
        recorder.setFrameRate(fps);
        recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264);
        recorder.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
        recorder.setVideoOption("preset", "veryfast");
        recorder.setVideoOption("crf", "23");
        recorder.start(); // fails here (not mid-recording) when the natives are broken

        running.set(true);
        finished = new CountDownLatch(1);
        worker = new Thread(new Runnable() {
            public void run() {
                Java2DFrameConverter converter = new Java2DFrameConverter();
                long frameNanos = 1_000_000_000L / fps;
                try {
                    while (running.get()) {
                        long frameStart = System.nanoTime();
                        BufferedImage shot = robot.createScreenCapture(grab);
                        Frame frame = converter.convert(shot);
                        recorder.record(frame);
                        long sleep = frameNanos - (System.nanoTime() - frameStart);
                        if (sleep > 0) {
                            Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                        }
                    }
                } catch (Exception ex) {
                    failure.compareAndSet(null, ex);
                } finally {
                    try {
                        recorder.stop(); // flushes the muxer — a half-written MP4 would be corrupt
                        recorder.release();
                    } catch (Exception finishFailure) {
                        failure.compareAndSet(null, finishFailure);
                    }
                    finished.countDown();
                }
            }
        }, "ffmpeg-recorder");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (finished != null) {
                finished.await(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // Like the JCodec adapter: the file is finalized best-effort; failures were already latched at
        // the frame that caused them and surfaced through start()/the worker where actionable.
    }

    @Override
    public boolean isRecording() {
        return running.get();
    }
}
