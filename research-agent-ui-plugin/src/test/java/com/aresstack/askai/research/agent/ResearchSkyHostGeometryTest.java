package com.aresstack.askai.research.agent;

import org.junit.Test;

import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hotfix 4.2 regression: the sky through the REAL host geometry — the layered transcript pane
 * (chat-column nesting, pass-4 layout), the overlay wrapper, and a DYNAMIC add followed by the
 * normal revalidate path. Only the OUTERMOST frame gets a size; the sky itself is NEVER sized by
 * hand (that hand-sizing is exactly what hotfix 4.1's test got wrong). Asserts positive bounds
 * down the whole chain, a positive published inset, and — via an actual paint pass — that the sky
 * really tints the top of the transcript while the bottom stays untouched.
 */
public class ResearchSkyHostGeometryTest {

    /** Replica of {@code OllamaChatPanel.transcriptLayers}' layout contract. */
    private static JLayeredPane transcriptLayersReplica() {
        return new JLayeredPane() {
            @Override
            public void doLayout() {
                for (Component child : getComponents()) {
                    child.setBounds(0, 0, getWidth(), getHeight());
                }
            }
        };
    }

    /** Replica of {@code AgentComposerAccessoryArea.newOverlayStack()}'s layout contract. */
    private static JPanel overlayStackReplica() {
        JPanel stack = new JPanel(null) {
            @Override
            public void doLayout() {
                for (Component child : getComponents()) {
                    child.setBounds(0, 0, getWidth(), getHeight());
                }
            }

            @Override
            public boolean contains(int x, int y) {
                for (Component child : getComponents()) {
                    if (child.isVisible() && child.contains(x, y)) {
                        return true;
                    }
                }
                return false;
            }
        };
        stack.setOpaque(false);
        return stack;
    }

    @Test
    public void aDynamicallyAddedSkyGetsRealBoundsAndPaintsThroughTheHostChain() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JLayeredPane layers = transcriptLayersReplica();
                JPanel transcriptStandIn = new JPanel();
                transcriptStandIn.setBackground(new Color(0xCC3333)); // loud → the tint is provable
                layers.add(transcriptStandIn, JLayeredPane.DEFAULT_LAYER);
                JPanel chatColumn = new JPanel(new BorderLayout());
                chatColumn.add(layers, BorderLayout.CENTER);
                JPanel bottom = new JPanel();
                bottom.setPreferredSize(new java.awt.Dimension(10, 120));
                chatColumn.add(bottom, BorderLayout.SOUTH);
                JPanel root = new JPanel(new BorderLayout());
                root.add(chatColumn, BorderLayout.CENTER);

                JFrame frame = new JFrame("sky-geometry-test");
                try {
                    frame.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
                    frame.getContentPane().setLayout(new BorderLayout());
                    frame.getContentPane().add(root, BorderLayout.CENTER);
                    frame.setSize(920, 760);
                    frame.addNotify(); // peer without showing a window
                    frame.validate();  // the app's initial layout pass

                    // The accessory arrives DYNAMICALLY, exactly like setTranscriptSkyAccessory.
                    JPanel stack = overlayStackReplica();
                    ResearchOutOfScopeSky sky = new ResearchOutOfScopeSky();
                    sky.setExclusions(java.util.Arrays.asList("Thema A", "Thema B"));
                    sky.setOpenForTest(true); // this test proves the OPEN sky's geometry/paint
                    stack.add(sky);
                    layers.add(stack, Integer.valueOf(50));
                    layers.revalidate();
                    // A non-showing frame is skipped by the RepaintManager, so run its endpoint
                    // ourselves: validateRoot.validate() — the exact call the live EDT performs.
                    frame.getRootPane().validate();

                    assertTrue("the layered pane has real bounds", layers.getWidth() > 0
                            && layers.getHeight() > 0);
                    assertEquals("the overlay wrapper fills the layered pane",
                            layers.getWidth(), stack.getWidth());
                    assertEquals(layers.getHeight(), stack.getHeight());
                    assertEquals("the sky fills the wrapper — never 0×0 after a dynamic add",
                            stack.getWidth(), sky.getWidth());
                    assertEquals(stack.getHeight(), sky.getHeight());
                    Component cloudScroll = sky.getComponent(0);
                    assertTrue("the cloud area is really laid out",
                            cloudScroll.getWidth() > 0 && cloudScroll.getHeight() > 0);
                    Object inset = sky.getClientProperty(
                            com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory
                                    .TRANSCRIPT_TOP_INSET_PROPERTY);
                    assertTrue("a laid-out sky publishes a positive transcript inset",
                            inset instanceof Integer && (Integer) inset > 0);

                    // The PAINT proof: the sky tint covers the top, the deep chat stays untouched.
                    BufferedImage image = new BufferedImage(900, 700,
                            BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2 = image.createGraphics();
                    root.paint(g2);
                    g2.dispose();
                    int top = image.getRGB(450, 5) & 0xFFFFFF;
                    int deep = image.getRGB(450, 450) & 0xFFFFFF;
                    assertTrue("the top of the transcript is sky-tinted (was " + hex(top) + ")",
                            nearWhiteWash(top));
                    assertEquals("the deep chat keeps its own color", 0xCC3333, deep);
                } finally {
                    frame.dispose();
                }
            }
        });
    }

    @Test
    public void theCollapsedStatusBarReallyPaintsThroughTheHostChain() throws Exception {
        // The user-facing regression check: DEFAULT state, no setOpenForTest, no hand-sizing —
        // the firmer sky-blue status bar must actually appear over the transcript.
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JLayeredPane layers = transcriptLayersReplica();
                JPanel transcriptStandIn = new JPanel();
                transcriptStandIn.setBackground(new Color(0xCC3333));
                layers.add(transcriptStandIn, JLayeredPane.DEFAULT_LAYER);
                JPanel root = new JPanel(new BorderLayout());
                root.add(layers, BorderLayout.CENTER);
                JFrame frame = new JFrame("sky-bar-test");
                try {
                    frame.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
                    frame.getContentPane().setLayout(new BorderLayout());
                    frame.getContentPane().add(root, BorderLayout.CENTER);
                    frame.setSize(920, 760);
                    frame.addNotify();
                    frame.validate();

                    JPanel stack = overlayStackReplica();
                    ResearchOutOfScopeSky sky = new ResearchOutOfScopeSky();
                    sky.setExclusions(java.util.Arrays.asList("Thema A", "Thema B"));
                    stack.add(sky);
                    layers.add(stack, Integer.valueOf(50));
                    layers.revalidate();
                    frame.getRootPane().validate();

                    java.awt.Component bar = findByName(sky, "sky.statusBar");
                    assertTrue("the status bar child exists", bar != null);
                    assertTrue("the status bar is visible", bar.isVisible());
                    assertTrue("the status bar is really laid out (was "
                            + bar.getWidth() + "x" + bar.getHeight() + ")",
                            bar.getWidth() > 0 && bar.getHeight() > 0);

                    BufferedImage image = new BufferedImage(900, 700,
                            BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2 = image.createGraphics();
                    root.paint(g2);
                    g2.dispose();
                    int inBar = image.getRGB(450, 25) & 0xFFFFFF; // middle of the bar zone
                    int deep = image.getRGB(450, 450) & 0xFFFFFF;
                    assertTrue("the bar zone shows the firmer sky blue, not the raw chat (was "
                            + hex(inBar) + ")", coolBlue(inBar));
                    assertEquals("the deep chat keeps its own color", 0xCC3333, deep);
                } finally {
                    frame.dispose();
                }
            }
        });
    }

    private static java.awt.Component findByName(java.awt.Component root, String name) {
        if (name.equals(root.getName())) {
            return root;
        }
        if (root instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) root).getComponents()) {
                java.awt.Component found = findByName(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The SKY_BAR_SURFACE family: clearly blue-leaning and bright — never the loud red chat. */
    private static boolean coolBlue(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return b > 180 && b > r && g > 150;
    }

    /**
     * The near-covering SKY_TOP wash over the loud red stand-in: every channel bright (the red
     * background alone has g/b ≈ 0x33, so a bright green/blue proves the sky really painted).
     */
    private static boolean nearWhiteWash(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r > 200 && g > 200 && b > 200;
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb);
    }
}
