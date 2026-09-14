package com.aresstack.comiccontrols.control;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.FlowLayout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #43 sources detail row: a narrow container must WRAP the controls onto further rows and
 * report the grown height — never clip them away. Wide containers stay a single row.
 */
public class WrapRowLayoutTest {

    private static JPanel panelWith(int width, int buttons) {
        JPanel panel = new JPanel(new WrapRowLayout(FlowLayout.LEFT, 0, 2));
        for (int index = 0; index < buttons; index++) {
            JButton button = new JButton("control-" + index);
            button.setPreferredSize(new Dimension(120, 24));
            panel.add(button);
        }
        panel.setSize(width, 24);
        return panel;
    }

    @Test
    public void aNarrowContainerReportsTheWrappedHeightSoNothingIsClipped() {
        JPanel narrow = panelWith(260, 5); // fits 2 per row → 3 rows
        Dimension preferred = narrow.getLayout().preferredLayoutSize(narrow);
        assertTrue("the preferred height must cover the wrapped rows: " + preferred.height,
                preferred.height >= 3 * 24);
        narrow.setSize(preferred.width == 0 ? 260 : Math.min(260, preferred.width),
                preferred.height);
        narrow.doLayout();
        for (java.awt.Component component : narrow.getComponents()) {
            assertTrue("every control stays inside the visible area",
                    component.getY() + component.getHeight() <= preferred.height);
            assertTrue(component.getX() >= 0);
        }
    }

    @Test
    public void aWideContainerStaysASingleRow() {
        JPanel wide = panelWith(1000, 5);
        Dimension preferred = wide.getLayout().preferredLayoutSize(wide);
        assertTrue("one row only: " + preferred.height, preferred.height <= 24 + 2 * 2 + 2);
    }

    @Test
    public void plainFlowLayoutShowsTheBugThisClassFixes() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        for (int index = 0; index < 5; index++) {
            JButton button = new JButton("control-" + index);
            button.setPreferredSize(new Dimension(120, 24));
            panel.add(button);
        }
        panel.setSize(260, 24);
        Dimension preferred = panel.getLayout().preferredLayoutSize(panel);
        assertEquals("plain FlowLayout claims ONE row even when it will wrap — the clip bug",
                24 + 2 * 2, preferred.height);
    }
}
