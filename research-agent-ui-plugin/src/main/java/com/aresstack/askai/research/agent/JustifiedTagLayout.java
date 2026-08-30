package com.aresstack.askai.research.agent;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.ArrayList;
import java.util.List;

/**
 * A wrapping tag layout with BLOCKSATZ (justification): tags flow left-to-right over as many rows as
 * needed, and per row the free space that would remain on the right is distributed EVENLY into the
 * gaps between the tags (tags keep their preferred size; a single-tag row stays left-aligned).
 * Preferred height follows the container's current width, so the panel grows with more rows.
 */
final class JustifiedTagLayout implements LayoutManager {

    private final int hgap;
    private final int vgap;
    private final boolean justify;

    JustifiedTagLayout(int hgap, int vgap) {
        this(hgap, vgap, true);
    }

    /** {@code justify=false} keeps the wrap behavior but leaves rows LEFT-aligned (chip drawers). */
    JustifiedTagLayout(int hgap, int vgap, boolean justify) {
        this.hgap = hgap;
        this.vgap = vgap;
        this.justify = justify;
    }

    public void addLayoutComponent(String name, Component comp) {
    }

    public void removeLayoutComponent(Component comp) {
    }

    public Dimension preferredLayoutSize(Container parent) {
        synchronized (parent.getTreeLock()) {
            Insets insets = parent.getInsets();
            int width = availableWidth(parent);
            if (width <= 0) {
                // No width yet (first layout pass): a single row of all tags.
                int w = 0;
                int h = 0;
                for (Component child : visibleChildren(parent)) {
                    Dimension pref = child.getPreferredSize();
                    w += (w > 0 ? hgap : 0) + pref.width;
                    h = Math.max(h, pref.height);
                }
                return new Dimension(insets.left + w + insets.right, insets.top + h + insets.bottom);
            }
            int height = 0;
            for (List<Component> row : wrapIntoRows(parent, width)) {
                int rowHeight = 0;
                for (Component child : row) {
                    rowHeight = Math.max(rowHeight, child.getPreferredSize().height);
                }
                height += (height > 0 ? vgap : 0) + rowHeight;
            }
            return new Dimension(insets.left + width + insets.right,
                    insets.top + height + insets.bottom);
        }
    }

    public Dimension minimumLayoutSize(Container parent) {
        return preferredLayoutSize(parent);
    }

    public void layoutContainer(Container parent) {
        synchronized (parent.getTreeLock()) {
            Insets insets = parent.getInsets();
            int width = availableWidth(parent);
            if (width <= 0) {
                return;
            }
            int y = insets.top;
            for (List<Component> row : wrapIntoRows(parent, width)) {
                int rowHeight = 0;
                int tagsWidth = 0;
                for (Component child : row) {
                    Dimension pref = child.getPreferredSize();
                    rowHeight = Math.max(rowHeight, pref.height);
                    tagsWidth += pref.width;
                }
                int gaps = row.size() - 1;
                // Blocksatz: spread the right-side leftover evenly across the inter-tag gaps.
                int free = Math.max(0, width - tagsWidth - gaps * hgap);
                int extraPerGap = justify && gaps > 0 ? free / gaps : 0;
                int remainder = justify && gaps > 0 ? free % gaps : 0;
                int x = insets.left;
                for (int i = 0; i < row.size(); i++) {
                    Component child = row.get(i);
                    Dimension pref = child.getPreferredSize();
                    child.setBounds(x, y, pref.width, rowHeight);
                    x += pref.width;
                    if (i < gaps) {
                        x += hgap + extraPerGap + (i < remainder ? 1 : 0);
                    }
                }
                y += rowHeight + vgap;
            }
        }
    }

    private List<List<Component>> wrapIntoRows(Container parent, int width) {
        List<List<Component>> rows = new ArrayList<List<Component>>();
        List<Component> row = new ArrayList<Component>();
        int rowWidth = 0;
        for (Component child : visibleChildren(parent)) {
            int childWidth = child.getPreferredSize().width;
            int needed = (row.isEmpty() ? 0 : rowWidth + hgap) + childWidth;
            if (!row.isEmpty() && needed > width) {
                rows.add(row);
                row = new ArrayList<Component>();
                rowWidth = 0;
                needed = childWidth;
            }
            row.add(child);
            rowWidth = needed;
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    private static List<Component> visibleChildren(Container parent) {
        List<Component> children = new ArrayList<Component>();
        for (Component child : parent.getComponents()) {
            if (child.isVisible()) {
                children.add(child);
            }
        }
        return children;
    }

    private static int availableWidth(Container parent) {
        Insets insets = parent.getInsets();
        int width = parent.getWidth() - insets.left - insets.right;
        if (width <= 0 && parent.getParent() != null) {
            width = parent.getParent().getWidth() - insets.left - insets.right;
        }
        return width;
    }
}
