package com.aresstack.askai.java8.ui.markdown;

import javax.swing.JTextPane;
import java.awt.Dimension;

/** Keep a text pane at its natural wrapped height when placed in a vertical Swing layout. */
final class WrappingTextPane extends JTextPane {

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
        int width = getParent() == null ? getWidth() : getParent().getWidth();
        if (width > 0 && width < Integer.MAX_VALUE) {
            setSize(new Dimension(width, Short.MAX_VALUE));
        }
        Dimension preferred = super.getPreferredSize();
        return new Dimension(Math.max(1, width), preferred.height);
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension preferred = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, preferred.height);
    }
}
