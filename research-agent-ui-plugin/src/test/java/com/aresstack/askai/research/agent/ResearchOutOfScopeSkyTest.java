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
                sky.setSize(900, 600);
                sky.doLayout();
            }
        });
    }

    @Test
    public void withoutExclusionsTheSkyClaimsNothingAndPublishesZeroInset() throws Exception {
        final ResearchOutOfScopeSky sky = build();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                sky.setExclusions(Collections.<String>emptyList());
            }
        });
        layout(sky);
        assertEquals("no exclusions → no scroll inset", 0, publishedInset(sky));
        assertFalse("no exclusions → the chat owns every click", sky.contains(20, 20));
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
    public void emptyingTheExclusionsResetsTheInsetToZero() throws Exception {
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
                sky.doLayout();
            }
        });
        assertEquals("removing the last exclusion removes the sky's claim on the chat's space",
                0, publishedInset(sky));
    }
}
