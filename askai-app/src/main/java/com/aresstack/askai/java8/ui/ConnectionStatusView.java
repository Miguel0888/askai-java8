package com.aresstack.askai.java8.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Top-right connection indicator: a coloured status dot followed by the Ollama host:port shown as a
 * link (scheme stripped). The dot's colour carries the semantic state (green = connected, red = not
 * reachable, …); the address itself stays a normal link — link-blue, underlined on hover — and clicking
 * opens the Connections settings (which doubles as "retry"). The full URL, version or error is in the
 * tooltip.
 */
public final class ConnectionStatusView extends JLabel {

    /** The address link colour (matches the previous bare-URL link); never green. */
    private static final int LINK_RGB = 0x0D47A1;

    private ConnectionStatus status = ConnectionStatus.NOT_CHECKED;
    private String url = "";
    private String detail = "";

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

            @Override
            public void mouseEntered(MouseEvent event) {
                render(true);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                render(false);
            }
        });
        render(false);
    }

    /**
     * @param newStatus the semantic state (drives the dot colour)
     * @param url       the Ollama base URL (shown as host:port and, in full, in the tooltip)
     * @param detail    extra tooltip context (e.g. "version 0.1.29" or an error message), may be empty
     */
    public void setStatus(ConnectionStatus newStatus, String url, String detail) {
        this.status = newStatus == null ? ConnectionStatus.NOT_CHECKED : newStatus;
        this.url = url == null ? "" : url;
        this.detail = detail == null ? "" : detail;
        render(false);
    }

    public ConnectionStatus getStatus() {
        return status;
    }

    private void render(boolean hovered) {
        String dot = "<font color='#" + hex(status.getColorRgb()) + "'>●</font>";
        String address = escape(stripScheme(url));
        String linked = hovered ? "<u>" + address + "</u>" : address;
        setText("<html>" + dot + "&nbsp;<font color='#" + hex(LINK_RGB) + "'>" + linked + "</font></html>");

        StringBuilder tip = new StringBuilder("<html>");
        if (url.length() > 0) {
            tip.append("Ollama: ").append(escape(url)).append("<br>");
        }
        tip.append("Status: ").append(status.getLabel()).append("<br>");
        if (detail.length() > 0) {
            tip.append(escape(detail)).append("<br>");
        }
        tip.append("Click to open connection settings").append("</html>");
        setToolTipText(tip.toString());
        // Keep it flush right after the menu-bar glue: an HTML label reports an unbounded maximum
        // size, so bound it to the natural width or the BoxLayout would stretch it left.
        setMaximumSize(getPreferredSize());
    }

    /** @return the URL without the http/https scheme and any trailing slash (e.g. "10.0.0.5:11434"). */
    private static String stripScheme(String value) {
        String result = value == null ? "" : value.trim();
        int scheme = result.indexOf("://");
        if (scheme >= 0) {
            result = result.substring(scheme + 3);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
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
