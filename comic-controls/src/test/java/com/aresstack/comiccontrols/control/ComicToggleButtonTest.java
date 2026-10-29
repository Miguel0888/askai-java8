package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ComicToggleButtonTest {

    @Test
    public void inkAtRestLightTextWhenSelected() {
        ComicToggleButton toggle = new ComicToggleButton("RAG");
        assertFalse(toggle.isContentAreaFilled());
        assertEquals(AskAiTheme.active().comicPalette().getInk(), toggle.getForeground());
        toggle.setSelected(true);
        assertEquals(AskAiTheme.active().buttonPlate(), toggle.getForeground());
        ComicFormControlsTest.paint(toggle);
        toggle.setSelected(false);
        assertEquals(AskAiTheme.active().comicPalette().getInk(), toggle.getForeground());
    }
}
