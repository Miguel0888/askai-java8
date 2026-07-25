package com.aresstack.askai.java8.ui;

import org.junit.Test;

import javax.swing.JLabel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The inspector card starts rolled up and only reveals its content once a block is selected. */
public class AudioInspectorCardTest {

    @Test
    public void startsCollapsedWithNoHeightAndHiddenContent() {
        JLabel content = new JLabel("inspector");
        AudioInspectorCard card = new AudioInspectorCard(content);
        // Collapsed: it must not occupy any vertical space and its content must be hidden.
        assertEquals(0, card.getPreferredSize().height);
        assertFalse(content.isVisible());
    }

    @Test
    public void expandingRevealsTheContentImmediately() {
        JLabel content = new JLabel("inspector");
        AudioInspectorCard card = new AudioInspectorCard(content);
        card.setExpanded(true);
        // The roll-down animation drives the height over time, but the content is shown right away so it
        // becomes visible as the card opens.
        assertTrue(content.isVisible());
    }
}
