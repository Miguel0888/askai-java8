package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;

public class ComicTabsAndProgressTest {

    private final AskAiTheme theme = AskAiTheme.active();

    @Test
    public void tabPlateColorsFollowTheState() {
        assertEquals(theme.comicPalette().getAccentYellow(), ComicTabbedPaneUI.tabFill(theme, true, true, false));
        assertEquals(theme.comicPalette().getAccentYellow(), ComicTabbedPaneUI.tabFill(theme, true, true, true));
        assertEquals(theme.warmFocus(), ComicTabbedPaneUI.tabFill(theme, true, false, true));
        assertEquals(theme.buttonPlate(), ComicTabbedPaneUI.tabFill(theme, true, false, false));
        assertEquals(theme.comicPalette().getSurface(), ComicTabbedPaneUI.tabFill(theme, false, true, false));
    }

    @Test
    public void tabbedPanePaintsWithSurfaceContent() {
        JTabbedPane tabs = ComicControls.tabbedPane();
        tabs.addTab("One", new JLabel("1"));
        tabs.addTab("Two", new JLabel("2"));
        tabs.setSize(300, 200);
        tabs.doLayout();
        BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        tabs.paint(g);
        g.dispose();
        assertEquals(theme.comicPalette().getSurface().getRGB(), image.getRGB(150, 190) | 0xFF000000);
        assertEquals(theme.comicPalette().getSurface(), tabs.getBackground());
    }

    @Test
    public void determinateFillIsOrange() {
        JProgressBar bar = ComicControls.progressBar(0, 100);
        bar.setValue(50);
        bar.setSize(200, 16);
        BufferedImage image = new BufferedImage(200, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        bar.paint(g);
        g.dispose();
        assertEquals(theme.comicPalette().getAccentOrange().getRGB(), image.getRGB(50, 8));
        assertEquals(theme.fieldBg().getRGB(), image.getRGB(150, 8));
    }
}
