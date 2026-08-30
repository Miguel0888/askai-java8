package com.aresstack.askai.research.agent;

import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Folding the open sky is a SKY gesture: pressing its own air — the transparent background in
 * the cloud zone or the visible fade below it — tucks it back into the bar, while clicks DOWN in
 * the chat (below the fade) leave it alone. This needs a real (off-screen) window because the
 * presses are dispatched on the FRAME so Swing's own lightweight hit-testing (the sky's
 * {@code contains}) decides who gets them — exactly the live routing.
 */
public class ResearchSkyClickOutsideCollapseTest {

    private JFrame frame;
    private ResearchOutOfScopeSky sky;

    @Before
    public void openRealWindow() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                sky = new ResearchOutOfScopeSky();
                frame = new JFrame("sky-test");
                frame.setUndecorated(true);
                frame.setLocation(-4000, -4000); // real peer + screen coords, out of the user's way
                frame.getContentPane().setLayout(null);
                sky.setBounds(0, 0, 600, 500);
                frame.getContentPane().add(sky);
                frame.setSize(600, 500);
                frame.setVisible(true);
                frame.getContentPane().validate();
            }
        });
    }

    @After
    public void closeWindow() throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    frame.dispose();
                }
            });
        }
    }

    @Test
    public void pressingTheSkysOwnAirInTheCloudZoneFoldsIt() throws Exception {
        openSky();
        pressAt(300, 2); // top edge: sky background above the cloud rows, no child there
        assertFalse("a press on the transparent cloud-zone air must fold the sky", isOpen());
    }

    @Test
    public void pressingTheVisibleFadeBelowTheCloudsFoldsIt() throws Exception {
        openSky();
        pressAt(300, contentBottom() + ResearchUiMetrics.SKY_FADE_TAIL / 2);
        assertFalse("a press in the painted fade must fold the sky", isOpen());
    }

    @Test
    public void clicksDownInTheChatLeaveTheSkyAlone() throws Exception {
        openSky();
        pressAt(300, contentBottom() + ResearchUiMetrics.SKY_FADE_TAIL + 40);
        assertTrue("below the fade the chat keeps its clicks — the sky stays open", isOpen());
    }

    @Test
    public void foldingStillWorksAfterADetachReattachCycle() throws Exception {
        openSky();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                // Hosts rehome the accessory (rebuilds, tab moves): the gesture must survive.
                frame.getContentPane().remove(sky);
                frame.getContentPane().add(sky);
                frame.getContentPane().validate();
            }
        });
        assertTrue("re-add alone must not close the sky", isOpen());
        pressAt(300, 2);
        assertFalse("the sky gesture must still fold after a re-add", isOpen());
    }

    private void openSky() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                sky.setOpenForTest(true);
                frame.getContentPane().validate();
            }
        });
        assertTrue(isOpen());
    }

    /**
     * Dispatch a MOUSE_PRESSED on the FRAME (the heavyweight): Swing's LightweightDispatcher
     * routes it by hit-testing, so the sky only sees it where its {@code contains} claims.
     */
    private void pressAt(final int x, final int y) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                Point screen = frame.getLocationOnScreen();
                // BUTTON1_DOWN_MASK is load-bearing: the dispatcher's grab logic ignores a
                // press whose modifiers do not carry the pressed button.
                frame.dispatchEvent(new MouseEvent(frame, MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, x, y,
                        screen.x + x, screen.y + y, 1, false, MouseEvent.BUTTON1));
            }
        });
    }

    private int contentBottom() throws Exception {
        final AtomicInteger value = new AtomicInteger();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                value.set(sky.contentBottomForTest());
            }
        });
        assertTrue("layout must have produced a content zone", value.get() > 0);
        return value.get();
    }

    private boolean isOpen() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean value =
                new java.util.concurrent.atomic.AtomicBoolean();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                value.set(sky.isOpenForTest());
            }
        });
        return value.get();
    }
}
