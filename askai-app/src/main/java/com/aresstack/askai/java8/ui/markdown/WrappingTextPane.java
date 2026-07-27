package com.aresstack.askai.java8.ui.markdown;

import javax.swing.JTextPane;
import java.awt.Dimension;

/** Keep a text pane at its natural wrapped height when placed in a vertical Swing layout. */
final class WrappingTextPane extends JTextPane {

    /** Guards the transient setSize() done while measuring so it does not look like a real width change. */
    private boolean measuring;

    WrappingTextPane() {
        setEditable(false);
        setOpaque(false);
        setBorder(null);
        setFocusable(true);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public Dimension getPreferredSize() {
        // Measure the wrapped height at the width the layout has actually assigned us. Only before the
        // first real layout (width still 0) do we fall back to the parent's width as a best guess.
        int width = getWidth();
        if (width <= 0 && getParent() != null) {
            width = getParent().getWidth();
        }
        if (width > 0 && width < Integer.MAX_VALUE) {
            measuring = true;
            try {
                setSize(new Dimension(width, Short.MAX_VALUE));
            } finally {
                measuring = false;
            }
        }
        Dimension preferred = super.getPreferredSize();
        return new Dimension(Math.max(1, width), preferred.height);
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension preferred = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, preferred.height);
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        // When the layout assigns a new width the text re-wraps, so the height computed for the old width
        // is stale. Ask the ancestors to lay out again; the next getPreferredSize() measures at this width.
        boolean widthChanged = !measuring && width != getWidth();
        super.setBounds(x, y, width, height);
        if (widthChanged) {
            revalidate();
        }
    }
}
