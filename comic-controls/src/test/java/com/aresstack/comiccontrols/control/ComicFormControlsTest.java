package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import com.aresstack.comiccontrols.theme.ComicPalette;
import org.junit.Test;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.plaf.basic.BasicToggleButtonUI;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ComicFormControlsTest {

    private final ComicPalette palette = AskAiTheme.active().comicPalette();

    @Test
    public void checkBoxGetsTheComicGlyphForEveryStateAndTheInkText() {
        JCheckBox box = new JCheckBox("x");
        ComicFormControls.install(box);
        assertTrue(box.getIcon() instanceof ComicFormControls.CheckIcon);
        assertSame(box.getIcon(), box.getSelectedIcon());
        assertSame(box.getIcon(), box.getDisabledSelectedIcon());
        assertFalse(box.isOpaque());
        assertEquals(palette.getInk(), box.getForeground());
        box.setSelected(true);
        paint(box);
    }

    @Test
    public void sliderGetsTheComicDelegate() {
        JSlider slider = new JSlider(0, 10, 5);
        ComicFormControls.install(slider);
        assertTrue(slider.getUI() instanceof ComicFormControls.SliderUI);
        assertEquals(palette.getInk(), slider.getForeground());
        paint(slider);
    }

    @Test
    public void spinnerGetsTheComicDelegateBorderAndInkEditor() {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(1, 0, 9, 1));
        ComicFormControls.install(spinner);
        assertTrue(spinner.getUI() instanceof ComicFormControls.SpinnerUI);
        assertTrue(spinner.getBorder() instanceof ComicFormControls.RoundBorder);
        assertEquals(palette.getInk(),
                ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().getForeground());
        paint(spinner);
    }

    @Test
    public void toggleGetsThePillDelegate() {
        JToggleButton toggle = new JToggleButton("x");
        ComicFormControls.install(toggle);
        assertTrue(toggle.getUI() instanceof BasicToggleButtonUI);
        assertFalse(toggle.isContentAreaFilled());
        assertEquals(palette.getInk(), toggle.getForeground());
        toggle.setSelected(true);
        paint(toggle);
        assertEquals(AskAiTheme.active().buttonPlate(), toggle.getForeground());
    }

    /** Paints a component headlessly into an image (smoke test of the painting code). */
    static BufferedImage paint(JComponent c) {
        c.setSize(Math.max(40, c.getPreferredSize().width), Math.max(20, c.getPreferredSize().height));
        c.doLayout();
        BufferedImage image = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            c.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }
}
