package com.aresstack.askai.java8.ui;

import org.junit.Test;

import java.awt.event.MouseEvent;

import static org.junit.Assert.assertTrue;

/** In the title bar the connection widget is only the dot; the address lives in the hover popup. */
public class ConnectionWidgetCompactTest {

    @Test
    public void compactShowsOnlyTheDotAndPutsTheAddressIntoThePopup() {
        ConnectionWidget widget = new ConnectionWidget(null, null);
        // deliberately NO font: the frame calls setCompact before the widget hangs in a window
        widget.setStatus(ConnectionStatus.CONNECTED, "http://127.0.0.1:11434", "version 0.32.3");
        int full = widget.getPreferredSize().width;
        widget.setCompact(true);
        assertTrue("only the dot remains", widget.getPreferredSize().width < full
                && widget.getPreferredSize().width <= 30);
        widget.setSize(widget.getPreferredSize());
        String tip = widget.getToolTipText(new MouseEvent(widget, MouseEvent.MOUSE_MOVED, 0L, 0, 5, 5, 0, false));
        assertTrue(tip, tip.contains("http://127.0.0.1:11434"));
        assertTrue(tip, tip.contains("version 0.32.3"));
    }
}
