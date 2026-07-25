package com.aresstack.askai.java8.ui;

import com.aresstack.audio.profile.AudioBlockDefinition;

import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Render and reorder a linear DSP pipeline as interactive Java2D blocks.
 *
 * <p>Extends {@link JPanel} (not a bare {@code JComponent}) so its UI delegate clears the full opaque
 * background on every {@link #paintComponent(Graphics)} via {@code super.paintComponent(...)}. A bare
 * opaque {@code JComponent} does not reliably erase its area, which left stale double-buffer pixels
 * (ghosting of the menu/toolbar/blocks) visible on a selection-triggered repaint, especially maximized.</p>
 */
public final class AudioPipelineCanvas extends JPanel {

    private static final int BLOCK_WIDTH = 168;
    private static final int BLOCK_HEIGHT = 92;
    private static final int BLOCK_GAP = 54;
    private static final int MARGIN = 28;

    public interface Listener {
        void selectionChanged(int selectedIndex);

        void orderChanged(List<AudioBlockDefinition> blocks, int selectedIndex);
    }

    private List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
    private int selectedIndex = -1;
    private int dragIndex = -1;
    private Listener listener;

    public AudioPipelineCanvas() {
        setOpaque(true);
        installMouseInteraction();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setBlocks(List<AudioBlockDefinition> blocks) {
        this.blocks = blocks == null
                ? new ArrayList<AudioBlockDefinition>()
                : new ArrayList<AudioBlockDefinition>(blocks);
        if (selectedIndex >= this.blocks.size()) {
            selectedIndex = this.blocks.isEmpty() ? -1 : this.blocks.size() - 1;
        }
        updatePreferredSize();
        repaint();
    }

    public List<AudioBlockDefinition> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    public void setSelectedIndex(int index) {
        selectedIndex = index >= 0 && index < blocks.size() ? index : -1;
        repaint();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    /** @return the horizontal center of the selected block in canvas coordinates, or -1 when none. */
    public int selectedBlockCenterX() {
        if (selectedIndex < 0 || selectedIndex >= blocks.size()) {
            return -1;
        }
        return blockX(selectedIndex) + BLOCK_WIDTH / 2;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paintPipeline(g);
        } finally {
            g.dispose();
        }
    }

    private void paintPipeline(Graphics2D g) {
        int y = Math.max(MARGIN, (getHeight() - BLOCK_HEIGHT) / 2);
        for (int i = 0; i < blocks.size(); i++) {
            int x = blockX(i);
            if (i > 0) {
                paintConnector(g, blockX(i - 1) + BLOCK_WIDTH, y + BLOCK_HEIGHT / 2,
                        x, y + BLOCK_HEIGHT / 2);
            }
            paintBlock(g, blocks.get(i), i, x, y);
        }
        if (blocks.isEmpty()) {
            g.setColor(uiColor("Label.disabledForeground", Color.GRAY));
            g.drawString("Add a block to build the processing pipeline.", MARGIN, MARGIN + 20);
        }
    }

    private void paintConnector(Graphics2D g, int x1, int y1, int x2, int y2) {
        Color foreground = uiColor("Label.disabledForeground", Color.GRAY);
        g.setColor(foreground);
        g.setStroke(new BasicStroke(2.0f));
        int arrowX = x2 - 10;
        g.drawLine(x1 + 7, y1, arrowX, y2);
        g.drawLine(arrowX, y2, arrowX - 8, y2 - 6);
        g.drawLine(arrowX, y2, arrowX - 8, y2 + 6);
    }

    private void paintBlock(Graphics2D g, AudioBlockDefinition block, int index, int x, int y) {
        Color background = uiColor("Panel.background", new Color(245, 245, 245));
        Color foreground = uiColor("Label.foreground", Color.DARK_GRAY);
        Color accent = uiColor("Component.accentColor", new Color(70, 120, 190));
        Color disabled = uiColor("Label.disabledForeground", Color.GRAY);

        g.setColor(background.brighter());
        g.fillRoundRect(x, y, BLOCK_WIDTH, BLOCK_HEIGHT, 18, 18);
        g.setStroke(new BasicStroke(index == selectedIndex ? 3.0f : 1.5f));
        g.setColor(index == selectedIndex ? accent : (block.isEnabled() ? foreground : disabled));
        g.drawRoundRect(x, y, BLOCK_WIDTH, BLOCK_HEIGHT, 18, 18);

        paintWaveIcon(g, x + 14, y + 16, block.isEnabled() ? accent : disabled);
        g.setColor(block.isEnabled() ? foreground : disabled);
        FontMetrics metrics = g.getFontMetrics();
        String title = fit(block.getType().getDisplayName(), metrics, BLOCK_WIDTH - 58);
        g.drawString(title, x + 48, y + 30);

        g.setColor(block.isEnabled() ? foreground : disabled);
        String summary = fit(parameterSummary(block), metrics, BLOCK_WIDTH - 24);
        g.drawString(summary, x + 12, y + 58);
        g.setColor(block.isEnabled() ? accent : disabled);
        g.drawString(block.isEnabled() ? "Enabled" : "Bypassed", x + 12, y + 78);
    }

    private void paintWaveIcon(Graphics2D g, int x, int y, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(2.0f));
        int middle = y + 12;
        g.drawLine(x, middle, x + 5, middle);
        g.drawLine(x + 5, middle, x + 10, y + 4);
        g.drawLine(x + 10, y + 4, x + 16, y + 20);
        g.drawLine(x + 16, y + 20, x + 22, y + 8);
        g.drawLine(x + 22, y + 8, x + 28, middle);
    }

    private String parameterSummary(AudioBlockDefinition block) {
        switch (block.getType()) {
            case LOW_PASS:
            case HIGH_PASS:
                return block.getParameter("cutoffHz", "") + " Hz · "
                        + filterDesignLabel(block.getParameter("implementation", ""));
            case BAND_PASS:
            case BAND_STOP:
                return block.getParameter("centerHz", "") + " Hz · width "
                        + block.getParameter("widthHz", "") + " Hz";
            case RESAMPLER:
                return block.getParameter("targetRateHz", "") + " Hz · "
                        + block.getParameter("quality", "BALANCED");
            case CHANNEL_MIXER:
                return "Output: mono";
            case NOISE_GATE:
                return "Threshold " + block.getParameter("threshold", "");
            case COMPRESSOR:
                return block.getParameter("ratio", "") + ":1 above "
                        + block.getParameter("threshold", "");
            case LIMITER:
                return "Ceiling " + block.getParameter("ceiling", "");
            case DC_OFFSET_REMOVAL:
                return "Adaptive offset estimate";
            default:
                return "";
        }
    }

    private static String filterDesignLabel(String value) {
        if ("FIR_65".equals(value)) {
            return "65-tap FIR";
        }
        if ("LEGACY_IIR".equals(value)) {
            return "1st-order IIR";
        }
        if ("BUTTERWORTH".equals(value)) {
            return "Butterworth";
        }
        return value;
    }

    private void installMouseInteraction() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                dragIndex = indexAt(event.getX(), event.getY());
                select(dragIndex);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragIndex < 0) {
                    return;
                }
                int target = indexForX(event.getX());
                if (target != dragIndex && target >= 0 && target < blocks.size()) {
                    AudioBlockDefinition moved = blocks.remove(dragIndex);
                    blocks.add(target, moved);
                    dragIndex = target;
                    selectedIndex = target;
                    updatePreferredSize();
                    repaint();
                    if (listener != null) {
                        listener.orderChanged(new ArrayList<AudioBlockDefinition>(blocks), selectedIndex);
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragIndex = -1;
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    private void select(int index) {
        selectedIndex = index;
        repaint();
        if (listener != null) {
            listener.selectionChanged(selectedIndex);
        }
    }

    private int indexAt(int x, int y) {
        int blockY = Math.max(MARGIN, (getHeight() - BLOCK_HEIGHT) / 2);
        if (y < blockY || y > blockY + BLOCK_HEIGHT) {
            return -1;
        }
        for (int i = 0; i < blocks.size(); i++) {
            int blockX = blockX(i);
            if (x >= blockX && x <= blockX + BLOCK_WIDTH) {
                return i;
            }
        }
        return -1;
    }

    private int indexForX(int x) {
        int slot = (x - MARGIN + (BLOCK_WIDTH + BLOCK_GAP) / 2) / (BLOCK_WIDTH + BLOCK_GAP);
        if (slot < 0) {
            return 0;
        }
        if (slot >= blocks.size()) {
            return blocks.size() - 1;
        }
        return slot;
    }

    private int blockX(int index) {
        return MARGIN + index * (BLOCK_WIDTH + BLOCK_GAP);
    }

    private void updatePreferredSize() {
        int width = Math.max(640, MARGIN * 2 + blocks.size() * BLOCK_WIDTH
                + Math.max(0, blocks.size() - 1) * BLOCK_GAP);
        setPreferredSize(new Dimension(width, 190));
        revalidate();
    }

    private static String fit(String text, FontMetrics metrics, int width) {
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + ellipsis) > width) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }
}
