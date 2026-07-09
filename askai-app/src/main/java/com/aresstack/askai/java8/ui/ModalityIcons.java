package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.HuggingFaceSearchSuggestion.Modality;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Set;

/**
 * Small, programmatically painted icons showing which input modalities a model accepts. Painted
 * with Java2D instead of image resources so they need no assets, scale with the look and feel
 * background, and stay crisp. {@link #forModalities(Set)} returns a fixed-width composite with one
 * slot per modality (text, audio, vision, in that order) so the icons form an aligned column in
 * the dropdown; absent modalities leave their slot empty.
 */
final class ModalityIcons {

    private static final int SLOT_SIZE = 16;
    private static final int GAP = 2;

    private static final Color TEXT_COLOR = new Color(0x60, 0x7D, 0x8B);
    private static final Color AUDIO_COLOR = new Color(0x2E, 0x7D, 0x32);
    private static final Color VISION_COLOR = new Color(0x15, 0x65, 0xC0);

    private ModalityIcons() {
    }

    /** @return a fixed-width icon strip for the given modalities (aligned three-slot column). */
    static Icon forModalities(Set<Modality> modalities) {
        return new CompositeIcon(modalities);
    }

    private static final class CompositeIcon implements Icon {

        private final Set<Modality> modalities;

        CompositeIcon(Set<Modality> modalities) {
            this.modalities = modalities;
        }

        public int getIconWidth() {
            return 3 * SLOT_SIZE + 2 * GAP;
        }

        public int getIconHeight() {
            return SLOT_SIZE;
        }

        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int slotX = x;
                if (modalities.contains(Modality.TEXT)) {
                    paintText(g, slotX, y);
                }
                slotX += SLOT_SIZE + GAP;
                if (modalities.contains(Modality.AUDIO)) {
                    paintAudio(g, slotX, y);
                }
                slotX += SLOT_SIZE + GAP;
                if (modalities.contains(Modality.VISION)) {
                    paintVision(g, slotX, y);
                }
            } finally {
                g.dispose();
            }
        }

        /** Text input: three text lines, the last one shorter. */
        private void paintText(Graphics2D g, int x, int y) {
            g.setColor(TEXT_COLOR);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x + 2, y + 4, x + 13, y + 4);
            g.drawLine(x + 2, y + 8, x + 13, y + 8);
            g.drawLine(x + 2, y + 12, x + 9, y + 12);
        }

        /** Audio input: a speaker with two sound waves. */
        private void paintAudio(Graphics2D g, int x, int y) {
            g.setColor(AUDIO_COLOR);
            // Speaker body: small rectangle plus cone.
            g.fillRect(x + 1, y + 6, 3, 5);
            int[] xs = {x + 4, x + 8, x + 8, x + 4};
            int[] ys = {y + 6, y + 2, y + 15, y + 11};
            g.fillPolygon(xs, ys, 4);
            // Sound waves.
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawArc(x + 8, y + 4, 5, 9, -55, 110);
            g.drawArc(x + 9, y + 1, 8, 15, -55, 110);
        }

        /** Vision/image input: an eye with a pupil. */
        private void paintVision(Graphics2D g, int x, int y) {
            g.setColor(VISION_COLOR);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // Eye outline: two arcs forming a lens shape.
            g.drawArc(x + 1, y + 2, 14, 12, 25, 130);
            g.drawArc(x + 1, y + 2, 14, 12, 205, 130);
            // Pupil.
            g.fillOval(x + 6, y + 6, 4, 4);
        }
    }
}
