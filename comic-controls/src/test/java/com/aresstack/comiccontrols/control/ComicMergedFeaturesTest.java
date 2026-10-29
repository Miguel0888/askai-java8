package com.aresstack.comiccontrols.control;

import org.junit.Test;

import javax.accessibility.AccessibleRole;
import javax.swing.JLabel;
import javax.swing.JScrollBar;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Features merged from the enterprise comic controls into the existing classes. */
public class ComicMergedFeaturesTest {

    @Test
    public void scrollBarAcceptsSurfaceSpecificThumbColors() {
        JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL);
        ComicScrollBarUI.install(bar, Color.WHITE, Color.ORANGE);
        assertTrue(bar.getUI() instanceof ComicScrollBarUI);
        assertEquals(new Dimension(ComicScrollBarUI.BAR_THICKNESS, 0), bar.getPreferredSize());
    }

    @Test
    public void overlayPlateCanBeCapped() {
        ComicOverlayPanel overlay = new ComicOverlayPanel("t", new JLabel("c"), new Runnable() {
            @Override
            public void run() {
            }
        });
        assertNotNull(overlay.closeButton());
        overlay.setSize(1000, 800);
        overlay.setPlateLimit(new Dimension(400, 300));
        overlay.doLayout();
        Component plate = overlay.getComponent(0);
        assertEquals(400, plate.getWidth());
        assertEquals(300, plate.getHeight());
        assertEquals(300, plate.getX());
    }

    @Test
    public void pillDropdownStepsOverDisabledItemsAndActsAsComboForAssistiveTech() {
        ResearchPillDropdown pill = new ResearchPillDropdown(30, 10, 100, 10, 10);
        pill.setItems(Arrays.asList(
                new ResearchPillDropdown.Item("a", null, true, null),
                new ResearchPillDropdown.Item("b", null, false, null),
                new ResearchPillDropdown.Item("c", null, true, null)));
        pill.setSelectedIndex(0);
        final List<Integer> selected = new ArrayList<Integer>();
        pill.setSelectionListener(new ResearchPillDropdown.SelectionListener() {
            @Override
            public void itemSelected(int index) {
                selected.add(index);
            }
        });
        pill.selectRelative(1);
        assertEquals(Arrays.asList(2), selected);
        assertTrue(pill.isFocusable());
        assertEquals(AccessibleRole.COMBO_BOX, pill.getAccessibleContext().getAccessibleRole());
        assertEquals("a", pill.getAccessibleContext().getAccessibleName());
    }
}
