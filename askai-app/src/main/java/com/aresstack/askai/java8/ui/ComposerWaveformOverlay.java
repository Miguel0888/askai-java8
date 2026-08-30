package com.aresstack.askai.java8.ui;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The ChatGPT-style recording sampler ABOVE the composer editor: no background at all — only the
 * live waveform bars (comic dark green, newest right) float over the text, so the typed text and
 * the caret stay fully visible and editable. Plus the Skype-style SIGNAL THRESHOLD: a horizontal
 * level line the user drags up/down DURING the recording; everything below it counts as
 * background noise (dimmed bars), everything above as speech — the auto-stop silence detection
 * uses exactly this line. Hit-testing claims ONLY the line's grab band; every other pixel is
 * click-through to the editor.
 */
public final class ComposerWaveformOverlay extends JComponent {

    /** Comic dark green — clearly "recording", distinct from the blue/red accent families. */
    private static final Color WAVE = new Color(0x2E7D32);
    private static final Color WAVE_BELOW = new Color(0x2E7D32);
    private static final int BAR_WIDTH = 3;
    private static final int BAR_GAP = 3;
    private static final int PAD_V = 6;
    private static final int GRAB_BAND = 5;

    /** Live threshold changes (percent 0-100) while the user drags the line. */
    public interface ThresholdListener {
        void thresholdChanged(int percent);
    }

    private final int[] levels = new int[120]; // ring buffer of recent level samples (0-100)
    private int levelCount;
    private int levelHead;
    private boolean active;
    private int thresholdPercent = 8;
    private ThresholdListener thresholdListener;
    private boolean dragging;

    public ComposerWaveformOverlay() {
        setOpaque(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                dragging = true;
                applyDrag(event.getY());
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragging) {
                    applyDrag(event.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragging = false;
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        setToolTipText("Signal threshold — drag: everything below counts as background noise"
                + " (silence auto-stop listens above this line).");
    }

    /** Recording lifecycle: activating clears the history; deactivating hides everything. */
    public void setActive(boolean value) {
        if (this.active != value) {
            this.active = value;
            levelCount = 0;
            levelHead = 0;
            repaint();
        }
    }

    /** Append one live level sample (0-100, the level-bar scale). */
    public void pushLevel(int level) {
        levels[levelHead] = Math.max(0, Math.min(100, level));
        levelHead = (levelHead + 1) % levels.length;
        levelCount = Math.min(levelCount + 1, levels.length);
        if (active) {
            repaint();
        }
    }

    public void setThresholdPercent(int percent) {
        this.thresholdPercent = clampPercent(percent);
        repaint();
    }

    public int getThresholdPercent() {
        return thresholdPercent;
    }

    public void setThresholdListener(ThresholdListener listener) {
        this.thresholdListener = listener;
    }

    /** ONLY the threshold line's grab band is interactive — the rest belongs to the editor. */
    @Override
    public boolean contains(int x, int y) {
        return active && Math.abs(y - thresholdY()) <= GRAB_BAND;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (!active || getHeight() <= 0) {
            return; // NO background ever — only the floating bars while recording
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int usable = Math.max(1, getHeight() - 2 * PAD_V);
            int centerY = getHeight() / 2;
            int slots = Math.max(1, (getWidth() - 8) / (BAR_WIDTH + BAR_GAP));
            int shown = Math.min(levelCount, slots);
            for (int i = 0; i < shown; i++) {
                // newest sample at the RIGHT edge, history flowing left — like the reference UI
                int sampleIndex = (levelHead - shown + i + levels.length * 2) % levels.length;
                int level = levels[sampleIndex];
                int barHeight = Math.max(3, level * usable / 100);
                int x = getWidth() - 8 - (shown - i) * (BAR_WIDTH + BAR_GAP);
                // Bars ABOVE the threshold are speech (full green); below = noise floor (faint).
                boolean aboveThreshold = level >= thresholdPercent;
                g2.setColor(withAlpha(aboveThreshold ? WAVE : WAVE_BELOW, aboveThreshold ? 205 : 70));
                g2.fillRoundRect(x, centerY - barHeight / 2, BAR_WIDTH, barHeight,
                        BAR_WIDTH, BAR_WIDTH);
            }
            // The draggable signal-threshold line, mirrored above/below the center like the bars.
            int offset = thresholdPercent * usable / 200; // half-height, bars are center-mirrored
            g2.setColor(withAlpha(WAVE, dragging ? 235 : 150));
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{5f, 4f}, 0f));
            g2.drawLine(4, centerY - offset, getWidth() - 4, centerY - offset);
            g2.drawLine(4, centerY + offset, getWidth() - 4, centerY + offset);
        } finally {
            g2.dispose();
        }
    }

    /** The UPPER threshold line's y — the grab band and drag math anchor to it. */
    private int thresholdY() {
        int usable = Math.max(1, getHeight() - 2 * PAD_V);
        return getHeight() / 2 - thresholdPercent * usable / 200;
    }

    private void applyDrag(int y) {
        int usable = Math.max(1, getHeight() - 2 * PAD_V);
        int centerY = getHeight() / 2;
        int percent = clampPercent(Math.abs(centerY - y) * 200 / usable);
        if (percent != thresholdPercent) {
            thresholdPercent = percent;
            repaint();
            ThresholdListener listener = thresholdListener;
            if (listener != null) {
                listener.thresholdChanged(percent);
            }
        }
    }

    private static int clampPercent(int percent) {
        return Math.max(1, Math.min(95, percent));
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
