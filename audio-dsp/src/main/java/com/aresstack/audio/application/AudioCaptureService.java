package com.aresstack.audio.application;

import com.aresstack.audio.domain.FrameSegmenter;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.dsp.AudioProcessingPipeline;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wire source, frame segmentation, DSP pipeline and sink together for one capture run.
 *
 * <p>Keep the capture thread real-time friendly: it only cuts frames and offers copies into a
 * bounded queue. A separate writer thread runs the DSP pipeline and the (possibly slow) sink.
 * When the queue is full the frame is dropped and counted instead of blocking the capture
 * thread.</p>
 */
public final class AudioCaptureService {

    private static final int QUEUE_CAPACITY_FRAMES = 128;
    /** Poison pill telling the writer thread that capture has finished. */
    private static final short[] END_OF_STREAM = new short[0];

    private final AtomicLong droppedFrames = new AtomicLong();

    /**
     * Capture from the source for the given duration, process every frame through the pipeline
     * and write the result to the sink. Block until finished.
     *
     * @return the number of frames dropped because the sink could not keep up
     */
    public long capture(AudioSource source, AudioProcessingPipeline pipeline, AudioSink sink,
                        int frameDurationMillis, long durationMillis)
            throws AudioCaptureException, IOException {
        final PcmAudioFormat format = source.getFormat();
        final int frameSize = format.samplesForMillis(frameDurationMillis);
        final FrameSegmenter segmenter = new FrameSegmenter(frameSize);
        final BlockingQueue<short[]> queue = new ArrayBlockingQueue<short[]>(QUEUE_CAPACITY_FRAMES);
        droppedFrames.set(0);

        sink.open(format);
        final CountDownLatch writerDone = new CountDownLatch(1);
        final IOException[] writerError = new IOException[1];
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        short[] frame = queue.take();
                        if (frame == END_OF_STREAM) {
                            return;
                        }
                        pipeline.process(frame, frame.length, format);
                        sink.write(frame, frame.length);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (IOException ex) {
                    writerError[0] = ex;
                } finally {
                    writerDone.countDown();
                }
            }
        }, "audio-dsp-writer");
        writer.setDaemon(true);
        writer.start();

        final FrameSegmenter.FrameConsumer enqueue = new FrameSegmenter.FrameConsumer() {
            @Override
            public void onFrame(short[] samples, int count) {
                // Copy: the segmenter reuses its buffer, and the queue hands off to another thread.
                short[] copy = new short[count];
                System.arraycopy(samples, 0, copy, 0, count);
                if (!queue.offer(copy)) {
                    droppedFrames.incrementAndGet();
                }
            }
        };

        try {
            source.start(new AudioSource.SampleListener() {
                @Override
                public void onSamples(short[] samples, int count) {
                    segmenter.push(samples, count, enqueue);
                }
            });
            sleepQuietly(durationMillis);
        } finally {
            source.stop();
            segmenter.flush(enqueue);
            enqueueEndOfStream(queue);
            awaitQuietly(writerDone);
            sink.close();
        }
        if (writerError[0] != null) {
            throw writerError[0];
        }
        return droppedFrames.get();
    }

    private static void enqueueEndOfStream(BlockingQueue<short[]> queue) {
        try {
            queue.put(END_OF_STREAM);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
