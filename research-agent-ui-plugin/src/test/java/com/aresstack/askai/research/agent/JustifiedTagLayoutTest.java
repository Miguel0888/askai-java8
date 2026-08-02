package com.aresstack.askai.research.agent;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The Blocksatz tag layout: wrapping, even gap distribution, single-tag rows left-aligned. */
public class JustifiedTagLayoutTest {

    private static JButton tag(int width) {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension(width, 24));
        return button;
    }

    @Test
    public void aFullRowIsJustifiedByDistributingTheFreeSpaceIntoTheGaps() {
        JPanel panel = new JPanel(new JustifiedTagLayout(6, 6));
        JButton a = tag(100);
        JButton b = tag(100);
        JButton c = tag(100);
        panel.add(a);
        panel.add(b);
        panel.add(c);
        panel.setSize(400, 100); // 100 px free → 50 extra per gap
        panel.doLayout();

        assertEquals(0, a.getX());
        assertEquals(100, a.getWidth()); // tags keep their preferred width
        int gap1 = b.getX() - (a.getX() + a.getWidth());
        int gap2 = c.getX() - (b.getX() + b.getWidth());
        assertEquals("gaps share the free space evenly", gap1, gap2);
        assertEquals("the row ends flush right", 400, c.getX() + c.getWidth());
    }

    @Test
    public void tagsWrapIntoMultipleRowsWhenTheWidthIsExceeded() {
        JPanel panel = new JPanel(new JustifiedTagLayout(6, 6));
        JButton a = tag(150);
        JButton b = tag(150);
        JButton c = tag(150);
        panel.add(a);
        panel.add(b);
        panel.add(c);
        panel.setSize(340, 200); // two fit per row, the third wraps
        panel.doLayout();

        assertEquals("first row", a.getY(), b.getY());
        assertTrue("third tag wrapped to a second row", c.getY() > a.getY());
        assertEquals("wrapped row starts at the left edge", 0, c.getX());
        assertEquals("full first row is flush right", 340, b.getX() + b.getWidth());
    }

    @Test
    public void aSingleTagRowStaysLeftAlignedInsteadOfStretching() {
        JPanel panel = new JPanel(new JustifiedTagLayout(6, 6));
        JButton only = tag(120);
        panel.add(only);
        panel.setSize(400, 100);
        panel.doLayout();
        assertEquals(0, only.getX());
        assertEquals(120, only.getWidth());
    }

    @Test
    public void preferredHeightGrowsWithTheWrappedRows() {
        JPanel panel = new JPanel(new JustifiedTagLayout(6, 6));
        for (int i = 0; i < 6; i++) {
            panel.add(tag(150));
        }
        panel.setSize(340, 10);
        Dimension pref = panel.getLayout().preferredLayoutSize(panel);
        assertEquals("6 tags à 150px in a 340px panel → 3 rows of 24px + 2 gaps", 3 * 24 + 2 * 6, pref.height);
    }

    @Test
    public void invisibleTagsAreSkipped() {
        JPanel panel = new JPanel(new JustifiedTagLayout(6, 6));
        JButton a = tag(100);
        Component hidden = tag(100);
        hidden.setVisible(false);
        JButton b = tag(100);
        panel.add(a);
        panel.add(hidden);
        panel.add(b);
        panel.setSize(400, 100);
        panel.doLayout();
        assertEquals("the visible pair is justified across the full width",
                400, b.getX() + b.getWidth());
    }
}
