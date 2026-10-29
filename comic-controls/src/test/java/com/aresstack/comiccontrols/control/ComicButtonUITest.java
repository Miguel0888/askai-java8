package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import com.aresstack.comiccontrols.theme.ComicPalette;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ComicButtonUITest {

    private final ComicPalette palette = AskAiTheme.classic().comicPalette();
    private final Color plate = AskAiTheme.classic().buttonPlate();

    @Test
    public void plateFillFollowsTheStates() {
        assertEquals(plate, ComicButtonUI.plateFill(palette, plate, true, false, false, false));
        assertEquals(palette.getAccentYellow(),
                ComicButtonUI.plateFill(palette, plate, true, false, true, false));
        assertEquals(palette.getAccentOrange(),
                ComicButtonUI.plateFill(palette, plate, true, true, true, false));
        assertEquals(palette.getSurface(),
                ComicButtonUI.plateFill(palette, plate, false, true, true, false));
    }

    @Test
    public void criticalUsesRedForRolloverAndPress() {
        assertEquals(palette.getAccentRed(),
                ComicButtonUI.plateFill(palette, plate, true, false, true, true));
        assertEquals(palette.getAccentRed(),
                ComicButtonUI.plateFill(palette, plate, true, true, true, true));
        assertEquals(plate, ComicButtonUI.plateFill(palette, plate, true, false, false, true));
    }

    @Test
    public void optOutPropertyIsRespected() {
        JButton button = new JButton("x");
        assertFalse(ComicButtonUI.isPlain(button));
        button.putClientProperty(ComicButtonUI.PLAIN_BUTTON_PROPERTY, Boolean.TRUE);
        assertTrue(ComicButtonUI.isPlain(button));
    }

    @Test
    public void comicButtonCarriesTheOptOutProperty() {
        assertTrue(ComicButtonUI.isPlain(new ComicButton("x")));
    }

    @Test
    public void createUiYieldsTheDelegateAndInstallsIt() {
        Object old = UIManager.get("ButtonUI");
        try {
            UIManager.put("ButtonUI", ComicButtonUI.class.getName());
            JButton button = new JButton("x");
            assertTrue(button.getUI() instanceof ComicButtonUI);
            assertTrue(button.isRolloverEnabled());
        } finally {
            UIManager.put("ButtonUI", old);
        }
    }

    @Test
    public void lafBorderIsFlattenedButMarginStaysLive() {
        Object old = UIManager.get("ButtonUI");
        try {
            UIManager.put("ButtonUI", ComicButtonUI.class.getName());
            JButton button = new JButton("x");
            assertTrue(button.getBorder() instanceof javax.swing.plaf.UIResource);
            button.setMargin(new java.awt.Insets(0, 0, 0, 0));
            java.awt.Insets tight = button.getInsets();
            button.setMargin(new java.awt.Insets(10, 20, 10, 20));
            java.awt.Insets wide = button.getInsets();
            assertEquals(tight.left + 20, wide.left);
            assertEquals(tight.top + 10, wide.top);
        } finally {
            UIManager.put("ButtonUI", old);
        }
    }

    @Test
    public void explicitBordersAreKept() {
        Object old = UIManager.get("ButtonUI");
        try {
            UIManager.put("ButtonUI", ComicButtonUI.class.getName());
            JButton button = new JButton("x");
            javax.swing.border.Border custom = javax.swing.BorderFactory.createEmptyBorder(1, 2, 3, 4);
            button.setBorder(custom);
            button.updateUI();
            assertEquals(custom, button.getBorder());
        } finally {
            UIManager.put("ButtonUI", old);
        }
    }
}
