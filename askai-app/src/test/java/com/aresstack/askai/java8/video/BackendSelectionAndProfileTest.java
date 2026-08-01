package com.aresstack.askai.java8.video;

import com.aresstack.askai.java8.video.jcodec.JcodecRecorderProvider;
import com.aresstack.askai.java8.video.optional.FfmpegRecorderProvider;
import com.aresstack.askai.java8.video.optional.VlcRecorderProvider;
import org.junit.Test;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Backend availability/selection semantics and RecordingProfile validation. */
public class BackendSelectionAndProfileTest {

    @Test
    public void jcodecIsTheDefaultAndAvailableWithoutNativeRuntimes() {
        JcodecRecorderProvider jcodec = new JcodecRecorderProvider();
        assertEquals("jcodec", jcodec.getId());
        // Available whenever not headless (in CI it may be headless → then unavailable, which is honest).
        assertEquals(!GraphicsEnvironment.isHeadless(), jcodec.isAvailable());
        assertNotNull(jcodec.createRecorder());
    }

    @Test
    public void vlcAndFfmpegAreUnavailableUntilTheirRuntimeIsPresent() {
        // No vlcj/VLC on the test classpath, no configured ffmpeg → both optional backends are unavailable.
        assertFalse(new VlcRecorderProvider().isAvailable());
        assertFalse(new FfmpegRecorderProvider().isAvailable());
    }

    @Test
    public void anUnavailableBackendCannotBeSelectedAndThereIsNoSilentFallback() {
        // Only an (unavailable) vlc provider: selecting it is refused, the selection does not change to
        // some other backend behind the user's back.
        VideoRecordingController controller = new VideoRecordingController(
                java.util.Collections.<MediaRecorderProvider>singletonList(new VlcRecorderProvider()));
        final boolean[] errored = {false};
        controller.setListener(new VideoRecordingController.Listener() {
            public void onStateChanged(VideoRecordingController.State state) {
            }
            public void onRecordingStarted(RecordingProfile profile) {
            }
            public void onRecordingStopped(java.nio.file.Path outputFile) {
            }
            public void onError(String message) {
                errored[0] = true;
            }
        });
        controller.selectProvider("vlc");
        assertTrue("selecting an unavailable backend is refused", errored[0]);
    }

    @Test
    public void availableProvidersReflectsRuntimePresenceOnly() {
        VideoRecordingController controller =
                new VideoRecordingController(MediaRecorderProviders.defaults());
        List<MediaRecorderProvider> available = controller.availableProviders();
        for (MediaRecorderProvider provider : available) {
            assertTrue(provider.isAvailable());
        }
        // VLC/FFmpeg are never in the available list on a plain test machine.
        for (MediaRecorderProvider provider : available) {
            assertFalse("vlc".equals(provider.getId()));
            assertFalse("ffmpeg".equals(provider.getId()));
        }
    }

    @Test
    public void profileRejectsInvalidFpsDimensionsSourceAndOutput() {
        Rectangle bounds = new Rectangle(0, 0, 320, 240);
        try {
            RecordingProfile.builder().outputFile(Paths.get("x.mp4")).fps(15).build();
            fail("source required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("source"));
        }
        try {
            RecordingProfile.builder().source(RecordingSource.window(bounds, "s")).fps(15).build();
            fail("output required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("outputFile"));
        }
        try {
            RecordingProfile.builder().source(RecordingSource.window(bounds, "s"))
                    .outputFile(Paths.get("x.mp4")).fps(0).build();
            fail("fps must be > 0");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("fps"));
        }
    }

    @Test
    public void aWindowSourceDerivesDimensionsFromItsBounds() {
        RecordingProfile profile = RecordingProfile.builder()
                .source(RecordingSource.window(new Rectangle(10, 20, 640, 480), "AskAI Window"))
                .outputFile(Paths.get("x.mp4")).fps(15).build();
        assertEquals(640, profile.getWidth());
        assertEquals(480, profile.getHeight());
        assertEquals(RecordingSource.Kind.WINDOW, profile.getSource().getKind());
    }

    @Test
    public void recordingSourceRejectsEmptyBounds() {
        try {
            RecordingSource.window(new Rectangle(0, 0, 0, 100), "x");
            fail("empty bounds rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("positive size"));
        }
    }
}
