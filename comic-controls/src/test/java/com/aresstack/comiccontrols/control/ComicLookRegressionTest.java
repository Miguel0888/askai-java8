package com.aresstack.comiccontrols.control;

import org.junit.Test;

import javax.swing.JScrollBar;
import javax.swing.UIManager;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regressions seen in the GUI gate of theme v2. */
public class ComicLookRegressionTest {

    @Test
    public void aBarStyledThroughTheGlobalDefaultIsSlimAndTransparent() {
        Object previous = UIManager.get("ScrollBarUI");
        try {
            UIManager.put("ScrollBarUI", ComicScrollBarUI.class.getName());
            JScrollBar vertical = new JScrollBar(JScrollBar.VERTICAL);
            JScrollBar horizontal = new JScrollBar(JScrollBar.HORIZONTAL);

            assertTrue(vertical.getUI() instanceof ComicScrollBarUI);
            assertFalse("no grey Metal track", vertical.isOpaque());
            assertFalse(horizontal.isOpaque());
            assertEquals(ComicScrollBarUI.BAR_THICKNESS, vertical.getPreferredSize().width);
            assertEquals(ComicScrollBarUI.BAR_THICKNESS, horizontal.getPreferredSize().height);
        } finally {
            UIManager.put("ScrollBarUI", previous);
        }
    }

    @Test
    public void aSlimToggleScalesItsLabelSoTheTextFits() {
        ComicSegmentedToggle toggle = new ComicSegmentedToggle("Text", "Audio");
        assertEquals("default height keeps the design size", 12.5f, toggle.fontSize(), 0.001f);

        toggle.setToggleHeight(12);
        Font label = new Font(Font.SANS_SERIF, Font.BOLD, 12).deriveFont(toggle.fontSize());
        FontMetrics metrics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
                .createGraphics().getFontMetrics(label);
        assertTrue("label cap height must fit the 12 px plate: ascent=" + metrics.getAscent(),
                metrics.getAscent() <= 12);
        assertTrue(toggle.fontSize() < 12.5f);
    }
}
