package com.aresstack.askai.research.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The OPEN sky must fold back into the slim bar when the user clicks anywhere OUTSIDE its content
 * zone — the drawer-like behavior the workspace sidebar already has. The closer is a global AWT
 * watcher that only acts under a REAL window (the deliberate window-ancestor guard), so headless
 * component tests can never prove it; this test builds a real (off-screen) frame and dispatches
 * synthetic presses through the normal event path the watcher listens on.
 */
public class ResearchSkyClickOutsideCollapseTest {

    private JFrame frame;
    private ResearchOutOfScopeSky sky;
    private JPanel chatBelow;

    @Before
    public void openRealWindow() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                sky = new ResearchOutOfScopeSky();
                chatBelow = new JPanel();
                frame = new JFrame("sky-test");
                frame.setUndecorated(true);
                frame.setLocation(-4000, -4000); // real peer + screen coords, out of the user's way
                frame.getContentPane().setLayout(null);
                sky.setBounds(0, 0, 600, 400);
                chatBelow.setBounds(0, 400, 600, 200);
                frame.getContentPane().add(sky);
                frame.getContentPane().add(chatBelow);
                frame.setSize(600, 600);
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
    public void aClickIntoTheChatBelowFoldsTheOpenSky() throws Exception {
        openSky();
        pressAt(chatBelow, 300, 100);
        assertFalse("outside click must collapse the sky", isOpen());
    }

    @Test
    public void aClickInsideTheCloudZoneKeepsTheSkyOpen() throws Exception {
        openSky();
        pressAt(sky, 300, 8); // well inside the content zone's top
        assertTrue("inside click must NOT collapse the sky", isOpen());
    }

    @Test
    public void theCloserSurvivesADetachReattachCycle() throws Exception {
        openSky();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                // Hosts may rehome the accessory component (rebuild, tab moves): the global
                // watcher must come back with the component, not die with removeNotify.
                frame.getContentPane().remove(sky);
                frame.getContentPane().add(sky);
                frame.getContentPane().validate();
            }
        });
        assertTrue("re-add alone must not close the sky", isOpen());
        pressAt(chatBelow, 300, 100);
        assertFalse("outside click must still collapse after re-add", isOpen());
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

    /** Dispatch a MOUSE_PRESSED with REAL screen coordinates through the component, as AWT would. */
    private void pressAt(final Component target, final int x, final int y) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                Point screen = target.getLocationOnScreen();
                target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(), 0, x, y, screen.x + x, screen.y + y,
                        1, false, MouseEvent.BUTTON1));
            }
        });
    }

    private boolean isOpen() throws Exception {
        final AtomicBoolean value = new AtomicBoolean();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                value.set(sky.isOpenForTest());
            }
        });
        return value.get();
    }
}
