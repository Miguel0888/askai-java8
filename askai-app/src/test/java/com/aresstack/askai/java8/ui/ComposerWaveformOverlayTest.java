package com.aresstack.askai.java8.ui;

import org.junit.Test;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The recording sampler's contract: everything except the threshold line's grab band is
 * click-through to the editor below, and dragging the line reports the percent the auto-stop
 * logic will use (mirrored around the vertical center, like the bars).
 */
public class ComposerWaveformOverlayTest {

    private ComposerWaveformOverlay overlay(int thresholdPercent) {
        ComposerWaveformOverlay overlay = new ComposerWaveformOverlay();
        overlay.setSize(400, 106); // usable height 94 → percent math is exact enough to assert
        overlay.setThresholdPercent(thresholdPercent);
        overlay.setActive(true);
        return overlay;
    }

    @Test
    public void onlyTheThresholdBandIsInteractive() {
        ComposerWaveformOverlay overlay = overlay(40);
        int centerY = overlay.getHeight() / 2;
        int usable = overlay.getHeight() - 12;
        int lineY = centerY - 40 * usable / 200;
        assertTrue("the line's grab band claims the click", overlay.contains(200, lineY));
        assertTrue(overlay.contains(200, lineY + 4));
        assertFalse("the editor keeps every other pixel", overlay.contains(200, lineY + 20));
        assertFalse("top edge stays click-through", overlay.contains(200, 1));
        overlay.setActive(false);
        assertFalse("inactive: fully click-through", overlay.contains(200, lineY));
    }

    @Test
    public void draggingTheLineReportsTheNewPercent() {
        ComposerWaveformOverlay overlay = overlay(40);
        final List<Integer> reported = new ArrayList<Integer>();
        overlay.setThresholdListener(new ComposerWaveformOverlay.ThresholdListener() {
            public void thresholdChanged(int percent) {
                reported.add(percent);
            }
        });
        int centerY = overlay.getHeight() / 2;
        int usable = overlay.getHeight() - 12;
        int targetY = centerY - 20 * usable / 200; // drag DOWN towards the center → 20 percent
        overlay.dispatchEvent(new MouseEvent(overlay, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, 200, targetY,
                1, false, MouseEvent.BUTTON1));
        assertEquals(1, reported.size());
        int percent = reported.get(0);
        assertTrue("integer pixel→percent rounding stays within one step: " + percent,
                Math.abs(percent - 20) <= 1);
        assertEquals(percent, overlay.getThresholdPercent());
    }

    @Test
    public void theDraggedPercentStaysInSaneBounds() {
        ComposerWaveformOverlay overlay = overlay(40);
        overlay.dispatchEvent(new MouseEvent(overlay, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, 200,
                overlay.getHeight() / 2, 1, false, MouseEvent.BUTTON1));
        assertTrue("dragging onto the center clamps to the minimum, never zero",
                overlay.getThresholdPercent() >= 1);
        overlay.dispatchEvent(new MouseEvent(overlay, MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, 200, -500,
                1, false, MouseEvent.BUTTON1));
        assertTrue("dragging far above clamps below 100", overlay.getThresholdPercent() <= 95);
    }
}
