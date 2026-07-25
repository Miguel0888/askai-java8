package com.aresstack.askai.java8.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Top-right connection indicator: a coloured dot plus a semantic status word ("Connected",
 * "Not reachable", …). The Ollama base URL — and, when available, the server version or the error —
 * stays reachable through the tooltip, and clicking runs the supplied action (open Connections
 * settings, which doubles as "retry"). Replaces the previous bare-URL label in the menu bar.
 */
public final class ConnectionStatusView extends JLabel {

    private ConnectionStatus status = ConnectionStatus.NOT_CHECKED;

    public ConnectionStatusView(final Runnable onClick) {
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setHorizontalAlignment(SwingConstants.RIGHT);
        setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 10));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (onClick != null) {
                    onClick.run();
                }
            }
        });
        render(ConnectionStatus.NOT_CHECKED, "", "");
    }

    /**
     * @param newStatus the semantic state to display
     * @param url       the Ollama base URL (shown in the tooltip)
     * @param detail    extra tooltip context (e.g. "version 0.1.29" or an error message), may be empty
     */
    public void setStatus(ConnectionStatus newStatus, String url, String detail) {
        this.status = newStatus;
        render(newStatus, url == null ? "" : url, detail == null ? "" : detail);
    }

    public ConnectionStatus getStatus() {
        return status;
    }

    private void render(ConnectionStatus state, String url, String detail) {
        String dot = "<font color='#" + hex(state.getColorRgb()) + "'>●</font>";
        setText("<html>" + dot + "&nbsp;" + state.getLabel() + "</html>");
        setForeground(new Color(state.getColorRgb()));
        StringBuilder tip = new StringBuilder("<html>");
        if (url.length() > 0) {
            tip.append("Ollama: ").append(escape(url)).append("<br>");
        }
        if (detail.length() > 0) {
            tip.append(escape(detail)).append("<br>");
        }
        tip.append("Click to open connection settings").append("</html>");
        setToolTipText(tip.toString());
        // Keep it flush right after the menu-bar glue: an HTML label reports an unbounded maximum
        // size, so bound it to the natural width or the BoxLayout would stretch it left.
        setMaximumSize(getPreferredSize());
    }

    private static String hex(int rgb) {
        String hex = Integer.toHexString(rgb & 0xFFFFFF);
        while (hex.length() < 6) {
            hex = "0" + hex;
        }
        return hex;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
