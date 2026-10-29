package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaceholderTextAreaTest {

    @Test
    public void wrapsTransparentlyAndPaintsThePlaceholderInTheThemeColor() {
        PlaceholderTextArea area = new PlaceholderTextArea("Ask anything", 2, 20);
        assertFalse(area.isOpaque());
        assertTrue(area.getLineWrap());
        assertEquals("Ask anything", area.getPlaceholder());
        assertTrue("placeholder visible while empty", countPlaceholderPixels(area) > 0);
        area.setText("x");
        assertEquals("no placeholder once there is text", 0, countPlaceholderPixels(area));
    }

    private static int countPlaceholderPixels(PlaceholderTextArea area) {
        area.setSize(300, 60);
        BufferedImage image = new BufferedImage(300, 60, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            area.paint(g);
        } finally {
            g.dispose();
        }
        int target = AskAiTheme.active().placeholderText().getRGB() & 0xFFFFFF;
        int found = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) == 0xFF && (argb & 0xFFFFFF) == target) {
                    found++;
                }
            }
        }
        return found;
    }
}
