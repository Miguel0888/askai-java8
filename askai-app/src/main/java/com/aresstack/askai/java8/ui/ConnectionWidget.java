package com.aresstack.askai.java8.ui;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The top-right CONNECTION widget: status dot + Ollama host:port fused into ONE control, so the
 * menu bar needs no separate always-visible refresh button and the dot never pretends to be live.
 * Two in-place actions for now: hovering the STATUS turns the dot into a comic refresh glyph
 * (circular arrow) — clicking it refreshes connection, models and audio profiles; clicking the
 * ADDRESS opens the connection settings (link-blue, underlined on hover). Deliberately its own
 * class with self-contained zones and painting, so a later slice can grow a hover/click DROPDOWN
 * with more information and actions without touching the menu bar again.
 */
public final class ConnectionWidget extends JComponent {

    /** The address link colour (matches the previous bare-URL link); never green. */
    private static final Color LINK = new Color(0x0D47A1);
    private static final int DOT_ZONE = 20;
    private static final int GAP = 4;
    private static final int PAD_LEFT = 8;
    private static final int PAD_RIGHT = 10;

    private final Runnable openSettings;
    private final Runnable refresh;

    private ConnectionStatus status = ConnectionStatus.NOT_CHECKED;
    private String url = "";
    private String detail = "";
    private boolean refreshEnabled = true;
    private boolean statusHovered;
    private boolean addressHovered;

    public ConnectionWidget(Runnable openSettings, Runnable refresh) {
        this.openSettings = openSettings;
        this.refresh = refresh;
        setOpaque(false);
        setToolTipText(" "); // register with the tooltip manager; text comes per zone
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                boolean inStatus = statusZone().contains(event.getPoint());
                boolean inAddress = addressZone().contains(event.getPoint());
                if (inStatus != statusHovered || inAddress != addressHovered) {
                    statusHovered = inStatus;
                    addressHovered = inAddress;
                    setCursor(Cursor.getPredefinedCursor(inStatus || inAddress
                            ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                statusHovered = false;
                addressHovered = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent event) {
                if (statusZone().contains(event.getPoint())) {
                    if (refreshEnabled && ConnectionWidget.this.refresh != null) {
                        ConnectionWidget.this.refresh.run();
                    }
                } else if (addressZone().contains(event.getPoint())
                        && ConnectionWidget.this.openSettings != null) {
                    ConnectionWidget.this.openSettings.run();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /**
     * @param newStatus the semantic state (drives the dot colour)
     * @param url       the Ollama base URL (shown as host:port and, in full, in the tooltip)
     * @param detail    extra tooltip context (e.g. "version 0.1.29" or an error), may be empty
     */
    public void setStatus(ConnectionStatus newStatus, String url, String detail) {
        this.status = newStatus == null ? ConnectionStatus.NOT_CHECKED : newStatus;
        this.url = url == null ? "" : url;
        this.detail = detail == null ? "" : detail;
        revalidate();
        repaint();
        setMaximumSize(getPreferredSize()); // flush right after the menu-bar glue, never stretched
    }

    public ConnectionStatus getStatus() {
        return status;
    }

    /** Disabled while a global refresh runs — the glyph dims and clicks are ignored. */
    public void setRefreshEnabled(boolean enabled) {
        this.refreshEnabled = enabled;
        repaint();
    }

    private Rectangle statusZone() {
        return new Rectangle(PAD_LEFT, 0, DOT_ZONE, getHeight());
    }

    private Rectangle addressZone() {
        int start = PAD_LEFT + DOT_ZONE + GAP;
        return new Rectangle(start, 0, Math.max(0, getWidth() - start - PAD_RIGHT), getHeight());
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (statusZone().contains(event.getPoint())) {
            return "Status: " + status.getLabel()
                    + (detail.isEmpty() ? "" : " — " + detail)
                    + ". Click to refresh connection, models and audio profiles";
        }
        return (url.isEmpty() ? "" : "Ollama: " + url + " — ")
                + "click to open connection settings";
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int cy = getHeight() / 2;
            int cx = PAD_LEFT + DOT_ZONE / 2;
            if (statusHovered) {
                // The dot GROWS a purpose on hover: the comic refresh glyph — a circular arrow
                // in the status colour, dimmed while a refresh is already running.
                Color color = new Color(status.getColorRgb());
                g2.setColor(refreshEnabled ? color
                        : new Color(color.getRed(), color.getGreen(), color.getBlue(), 110));
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int r = 6;
                g2.drawArc(cx - r, cy - r, 2 * r, 2 * r, 30, 290); // open circle
                // arrow head at the arc's start (top right), pointing clockwise
                int hx = cx + (int) (r * Math.cos(Math.toRadians(30)));
                int hy = cy - (int) (r * Math.sin(Math.toRadians(30)));
                g2.drawLine(hx, hy, hx + 4, hy - 1);
                g2.drawLine(hx, hy, hx - 1, hy - 5);
            } else {
                g2.setColor(new Color(status.getColorRgb()));
                g2.fillOval(cx - 4, cy - 4, 9, 9);
            }

            String address = stripScheme(url);
            g2.setFont(getFont());
            FontMetrics metrics = g2.getFontMetrics();
            int textX = PAD_LEFT + DOT_ZONE + GAP;
            int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.setColor(LINK);
            g2.drawString(address, textX, textY);
            if (addressHovered) {
                int width = metrics.stringWidth(address);
                g2.drawLine(textX, textY + 2, textX + width, textY + 2);
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        int width = PAD_LEFT + DOT_ZONE + GAP + metrics.stringWidth(stripScheme(url)) + PAD_RIGHT;
        return new Dimension(width, Math.max(20, metrics.getHeight() + 4));
    }

    /** @return the URL without the http/https scheme and any trailing slash. */
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
}
