package com.aresstack.comiccontrols.paint;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import com.aresstack.comiccontrols.theme.ColorContrast;
import org.junit.Test;

import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ported paint helpers: stroke icons, count badge, sky clouds, contrast math. */
public class ComicPaintTest {

    @Test
    public void strokeIconPaintsWithTheOwnersForeground() {
        JLabel owner = new JLabel();
        owner.setForeground(Color.RED);
        StrokeIcon icon = ComposerIcons.chevronDown();
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        icon.paintIcon(owner, g, 0, 0);
        g.dispose();
        boolean red = false;
        for (int y = 0; y < image.getHeight() && !red; y++) {
            for (int x = 0; x < image.getWidth() && !red; x++) {
                int argb = image.getRGB(x, y);
                red = (argb >>> 24) > 0 && (argb & 0xFFFF) == 0 && ((argb >> 16) & 0xFF) > 0;
            }
        }
        assertTrue(red);
    }

    @Test
    public void countBadgeCapsTheLabelAndGrowsTheIcon() {
        StrokeIcon glyph = ComposerIcons.paperclip();
        CountBadgeIcon badge = new CountBadgeIcon(glyph, 120, Color.ORANGE, Color.BLACK);
        assertEquals("99+", badge.label());
        assertTrue(badge.getIconWidth() > glyph.getIconWidth());
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        badge.paintIcon(new JLabel(), g, 0, 0);
        g.dispose();
    }

    @Test
    public void skyTopTakesTheSkyToken() {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        SkyClouds.paintSky(g, 100, 60, 100);
        g.dispose();
        Color top = AskAiTheme.active().research("SKY_TOP");
        int argb = image.getRGB(50, 0);
        assertEquals(top.getRGB() & 0xFFFFFF, argb & 0xFFFFFF);
        assertTrue((argb >>> 24) >= 240);
        assertTrue("fades out towards the bottom", (image.getRGB(50, 99) >>> 24) < 10);
    }

    @Test
    public void classicSkyIsTheHistoricSingleToneRamp() {
        AskAiTheme classic = AskAiTheme.classic();
        Color[] stops = SkyClouds.skyColors(classic.research("SKY_TOP"), classic.research("SKY_MID"));
        int[] alphas = {242, 205, 105, 0};
        for (int i = 0; i < stops.length; i++) {
            assertEquals(0xF5FAFE, stops[i].getRGB() & 0xFFFFFF);
            assertEquals(alphas[i], stops[i].getAlpha());
        }
    }

    @Test
    public void arcaneSkyRunsFromSunsetRedIntoEveningGold() {
        AskAiTheme arcane = AskAiTheme.arcane();
        Color[] stops = SkyClouds.skyColors(arcane.research("SKY_TOP"), arcane.research("SKY_MID"));
        assertEquals(0xE4572E, stops[0].getRGB() & 0xFFFFFF);
        assertEquals(242, stops[0].getAlpha());
        for (int i = 1; i < stops.length; i++) {
            assertEquals(0xF4A259, stops[i].getRGB() & 0xFFFFFF);
        }
        assertEquals(205, stops[1].getAlpha());
        assertEquals(105, stops[2].getAlpha());
        assertEquals(0, stops[3].getAlpha());
    }

    @Test
    public void skyFractionsFollowTheContentZone() {
        float[] f = SkyClouds.skyFractions(50, 100);
        assertEquals(0f, f[0], 0f);
        assertEquals(0.275f, f[1], 0.0001f);
        assertEquals(0.5f, f[2], 0.0001f);
        assertEquals(1f, f[3], 0f);
        assertEquals(0.85f, SkyClouds.skyFractions(99, 100)[2], 0.0001f);
        assertEquals(0.1f, SkyClouds.skyFractions(1, 100)[2], 0.0001f);
    }

    @Test
    public void cloudShapeIsNonEmpty() {
        Area shape = SkyClouds.cloudShape(80, 32);
        assertFalse(shape.isEmpty());
    }

    @Test
    public void contrastOfBlackOnWhiteIs21() {
        assertEquals(21.0, ColorContrast.ratio(Color.BLACK, Color.WHITE), 0.01);
        assertEquals(1.0, ColorContrast.ratio(Color.GRAY, Color.GRAY), 0.0001);
        assertTrue(ColorContrast.isReadable(Color.BLACK, Color.WHITE));
    }
}
