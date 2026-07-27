package com.aresstack.askai.java8.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;

import javax.swing.Icon;

/** A simple "+" glyph painted with Java2D (no asset), for the icon-only new-chat tab button. */
public final class PlusIcon implements Icon {

    private final int size;

    public PlusIcon(int size) {
        this.size = size;
    }

    public int getIconWidth() {
        return size;
    }

    public int getIconHeight() {
        return size;
    }

    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.isEnabled() ? new Color(0x42, 0x60, 0x77) : new Color(0x9E, 0x9E, 0x9E));
            g.setStroke(new BasicStroke(Math.max(1.6f, size / 8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double pad = size * 0.22;
            double cx = x + size / 2.0;
            double cy = y + size / 2.0;
            g.draw(new Line2D.Double(x + pad, cy, x + size - pad, cy));
            g.draw(new Line2D.Double(cx, y + pad, cx, y + size - pad));
        } finally {
            g.dispose();
        }
    }
}
