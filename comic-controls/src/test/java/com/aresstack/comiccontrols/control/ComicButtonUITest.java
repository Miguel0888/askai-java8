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
}
