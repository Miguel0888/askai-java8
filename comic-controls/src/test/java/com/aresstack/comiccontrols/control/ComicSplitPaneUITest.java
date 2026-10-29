package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Every split pane gets the drawer's comic divider, in both orientations. */
public class ComicSplitPaneUITest {

    private Object previous;

    @Before
    public void installGlobally() {
        previous = UIManager.get("SplitPaneUI");
        UIManager.put("SplitPaneUI", ComicSplitPaneUI.class.getName());
    }

    @After
    public void restore() {
        UIManager.put("SplitPaneUI", previous);
    }

    @Test
    public void aPlainSplitPaneGetsTheComicDividerInBothOrientations() {
        for (int orientation : new int[]{JSplitPane.HORIZONTAL_SPLIT, JSplitPane.VERTICAL_SPLIT}) {
            JSplitPane split = new JSplitPane(orientation, new JLabel("a"), new JLabel("b"));
            assertTrue(split.getUI() instanceof ComicSplitPaneUI);
            assertEquals(ComicSplitPaneUI.DIVIDER_THICKNESS, split.getDividerSize());
            split.setSize(200, 200);
            split.setDividerLocation(90);
            split.doLayout();
            assertTrue("the grip shows the accent", paintsAccent(split));
        }
    }

    @Test
    public void theDrawerSplitKeepsItsDividerAcrossUpdateUi() {
        ComicSplitPane drawer = new ComicSplitPane(new JLabel("l"), new JLabel("r"), 100, 400);
        JPanel host = new JPanel();
        host.add(drawer);
        SwingUtilities.updateComponentTreeUI(host);
        assertTrue(drawer.getUI() instanceof ComicSplitPaneUI);
    }

    /** The divider is an AWT container: paint IT (an unrealized split pane skips it offscreen). */
    private static boolean paintsAccent(JSplitPane split) {
        java.awt.Component divider = null;
        for (java.awt.Component child : split.getComponents()) {
            if (child instanceof ComicSplitPaneUI.ComicDivider) {
                divider = child;
            }
        }
        assertTrue("the comic divider is installed", divider != null);
        assertEquals("laid out at the comic thickness", ComicSplitPaneUI.DIVIDER_THICKNESS,
                split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? divider.getWidth() : divider.getHeight());
        BufferedImage image = new BufferedImage(divider.getWidth(), divider.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        divider.paint(g);
        g.dispose();
        int accent = AskAiTheme.active().comicPalette().getAccentYellow().getRGB() & 0xFFFFFF;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == accent) {
                    return true;
                }
            }
        }
        return false;
    }
}
