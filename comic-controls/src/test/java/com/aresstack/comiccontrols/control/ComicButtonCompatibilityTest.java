package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import org.junit.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A raw {@code JButton} replaced by a {@link ComicButton} keeps what its caller configured: explicit
 * margins, flat buttons ({@code setContentAreaFilled(false)}) and an explicit background as plate.
 */
public class ComicButtonCompatibilityTest {

    @Test
    public void noArgAndIconConstructorsExist() {
        assertEquals("", new ComicButton().getText());
        assertTrue(new ComicButton(new javax.swing.ImageIcon()).getIcon() != null);
    }

    @Test
    public void explicitMarginDrivesThePaddingAlsoAfterUpdateUi() {
        ComicButton button = new ComicButton("x");
        assertEquals(new Insets(5, 14, 5, 14), button.getInsets());
        button.setMargin(new Insets(1, 2, 3, 4));
        assertEquals(new Insets(1, 2, 3, 4), button.getInsets());
        JPanel host = new JPanel();
        host.add(button);
        SwingUtilities.updateComponentTreeUI(host);
        assertEquals(new Insets(1, 2, 3, 4), button.getInsets());
    }

    @Test
    public void flatButtonsPaintNoPlate() {
        ComicButton button = new ComicButton("x");
        assertTrue(button.isPlateShown());
        button.setContentAreaFilled(false);
        assertFalse(button.isPlateShown());
        assertFalse("the look and feel fill stays off", button.isContentAreaFilled());
        button.setSize(60, 24);
        BufferedImage image = render(button);
        assertEquals("no plate at the centre-left", 0, image.getRGB(3, 12) >>> 24);
    }

    @Test
    public void explicitBackgroundBecomesTheRestingPlate() {
        ComicButton button = new ComicButton("");
        button.setBackground(Color.MAGENTA);
        button.setSize(60, 24);
        assertEquals(Color.MAGENTA.getRGB(), render(button).getRGB(30, 12));
        ComicButton plain = new ComicButton("");
        plain.setSize(60, 24);
        assertEquals(AskAiTheme.active().buttonPlate().getRGB(), render(plain).getRGB(30, 12));
    }

    private static BufferedImage render(ComicButton button) {
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            button.paint(g2);
        } finally {
            g2.dispose();
        }
        return image;
    }
}
