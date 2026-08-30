package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Hotfix 4.1 regression: the sky's visibility and scroll-geometry contract at the component
 * level — SCOPING+exclusions shows clouds and publishes a POSITIVE transcript top inset;
 * empty/hidden publishes 0 and claims nothing, leaving no invisible dead zone behind.
 */
public class ResearchOutOfScopeSkyTest {

    private static ResearchOutOfScopeSky build() throws Exception {
        final AtomicReference<ResearchOutOfScopeSky> ref =
                new AtomicReference<ResearchOutOfScopeSky>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ref.set(new ResearchOutOfScopeSky());
            }
        });
        return ref.get();
    }

    private static int publishedInset(JComponent sky) {
        Object value = sky.getClientProperty(ComposerAccessory.TRANSCRIPT_TOP_INSET_PROPERTY);
        return value instanceof Integer ? (Integer) value : 0;
    }

    private static void layout(final ResearchOutOfScopeSky sky) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                if (!sky.isDisplayable()) {
                    sky.addNotify(); // a real (lightweight) peer so validate() descends
                }
                sky.setSize(900, 600);
                sky.invalidate();
                sky.validate(); // full validateTree: scroll pane, viewport, flow, clouds
            }
        });
    }

    @Test
    public void withoutExclusionsTheSkyStillShowsTheAddCloud() throws Exception {
        // Within SCOPING there is NO blank sky: zero exclusions render exactly [+ Hinzufügen],
        // so the FIRST exclusion can always be added right here (the original cloud behavior).
        final ResearchOutOfScopeSky sky = build();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                sky.setExclusions(Collections.<String>emptyList());
            }
        });
        layout(sky);
        JComponent addCloud = sky.addCloudForTest();
        assertTrue("the + Hinzufügen cloud is visible", addCloud.isVisible());
        assertTrue("…and really laid out", addCloud.getWidth() > 0 && addCloud.getHeight() > 0);
        assertTrue("the minimal sky publishes its inset", publishedInset(sky) > 0);
        assertTrue("the add cloud's zone is interactive", sky.contains(20, 12));
    }

    @Test
    public void withExclusionsTheSkyShowsCloudsAndPublishesAPositiveInset() throws Exception {
        final ResearchOutOfScopeSky sky = build();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                sky.setExclusions(Arrays.asList("Thema A", "Thema B"));
            }
        });
        layout(sky);
        assertTrue("the sky publishes the top room the transcript must reserve",
                publishedInset(sky) > 0);
        assertTrue("the content zone is interactive", sky.contains(20, 12));
        assertFalse("the fade tail below the content stays the chat's",
                sky.contains(20, publishedInset(sky) + 200));
    }

    @Test
    public void hidingTheSkyResetsTheInsetToZero() throws Exception {
        final ResearchOutOfScopeSky sky = build();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                sky.setExclusions(Collections.singletonList("Thema A"));
            }
        });
        layout(sky);
        assertTrue(publishedInset(sky) > 0);
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                sky.setVisible(false); // phase change: the accessory hides the sky
            }
        });
        assertEquals("leaving Phase 1 gives the chat its full height back — no dead zone",
                0, publishedInset(sky));
        assertFalse(sky.contains(20, 12));
    }

    @Test
    public void emptyingTheExclusionsFallsBackToTheAddCloudNotToABlankSky() throws Exception {
        final ResearchOutOfScopeSky sky = build();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                sky.setExclusions(Collections.singletonList("Thema A"));
            }
        });
        layout(sky);
        assertTrue(publishedInset(sky) > 0);
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                sky.setExclusions(Collections.<String>emptyList());
            }
        });
        layout(sky);
        assertTrue("the sky shrinks to the + Hinzufügen row but never disappears within scoping",
                publishedInset(sky) > 0);
        assertTrue(sky.addCloudForTest().isVisible());
    }
}
