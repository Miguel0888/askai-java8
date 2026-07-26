package com.aresstack.askai.java8.ui.markdown;

import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/** Render Mermaid off the EDT and paint the result with Java2D. */
public final class MermaidDiagramPanel extends JPanel {

    private static final int PADDING = 12;
    private static final int MIN_RENDER_WIDTH = 480;
    private static final int MAX_RENDER_WIDTH = 1800;

    private final String diagramCode;
    private final MarkdownTheme theme;
    private final MermaidImageRenderer imageRenderer;
    private BufferedImage image;
    private String errorMessage;
    private boolean rendering;

    public MermaidDiagramPanel(String diagramCode, MarkdownTheme theme,
                               MermaidImageRenderer imageRenderer) {
        this.diagramCode = diagramCode == null ? "" : diagramCode;
        this.theme = theme;
        this.imageRenderer = imageRenderer;
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.getSeparatorColor()),
                BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING)));
        setPreferredSize(new Dimension(600, 140));
        setToolTipText("Right-click to copy this diagram as an image or its Mermaid code");
        installContextMenu();
        renderAsync();
    }

    /** @return the raw Mermaid source of this diagram. */
    public String getDiagramCode() {
        return diagramCode;
    }

    private void installContextMenu() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                maybeShowPopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybeShowPopup(event);
            }
        });
    }

    private void maybeShowPopup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();

        JMenuItem copyImage = new JMenuItem("Copy diagram as image");
        copyImage.setEnabled(image != null);
        copyImage.addActionListener(action -> copyImageToClipboard());
        menu.add(copyImage);

        JMenuItem copyCode = new JMenuItem("Copy Mermaid code");
        copyCode.setEnabled(diagramCode.trim().length() > 0);
        copyCode.addActionListener(action -> copyCodeToClipboard());
        menu.add(copyCode);

        menu.show(this, event.getX(), event.getY());
    }

    private void copyImageToClipboard() {
        if (image == null) {
            return;
        }
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(imageTransferable(image), null);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Could not copy the diagram image: " + ex.getMessage(),
                    "Copy failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void copyCodeToClipboard() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(diagramCode), null);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Could not copy the Mermaid code: " + ex.getMessage(),
                    "Copy failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** A clipboard transferable that exposes a rendered diagram as an image (visible for tests). */
    static Transferable imageTransferable(final BufferedImage image) {
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] {DataFlavor.imageFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.imageFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                if (!DataFlavor.imageFlavor.equals(flavor)) {
                    throw new UnsupportedFlavorException(flavor);
                }
                return image;
            }
        };
    }

    private void renderAsync() {
        if (rendering) {
            return;
        }
        if (diagramCode.trim().isEmpty()) {
            errorMessage = "Mermaid diagram is empty.";
            repaint();
            return;
        }
        rendering = true;
        int availableWidth = Math.max(1, getWidth() - PADDING * 2);
        final int targetWidth = Math.max(MIN_RENDER_WIDTH,
                Math.min(MAX_RENDER_WIDTH, availableWidth * 2));
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                return imageRenderer.render(diagramCode, targetWidth);
            }

            @Override
            protected void done() {
                try {
                    image = get();
                    if (image == null) {
                        errorMessage = "Mermaid diagram could not be rendered.";
                    }
                } catch (Exception ex) {
                    errorMessage = "Mermaid error: " + ex.getMessage();
                } finally {
                    rendering = false;
                    updatePreferredSize();
                    revalidate();
                    repaint();
                }
            }
        }.execute();
    }

    private void updatePreferredSize() {
        if (image == null) {
            setPreferredSize(new Dimension(600, 140));
            return;
        }
        int width = Math.max(1, getWidth() - PADDING * 2);
        if (width <= 1) {
            width = Math.min(900, image.getWidth());
        }
        double scale = Math.min(1.0d, width / (double) image.getWidth());
        int height = (int) Math.ceil(image.getHeight() * scale) + PADDING * 2;
        setPreferredSize(new Dimension(width + PADDING * 2, Math.max(80, height)));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(80, height)));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (image != null) {
                paintImage(g2);
                return;
            }
            paintStatus(g2, errorMessage == null ? "Rendering Mermaid…" : errorMessage,
                    errorMessage == null ? theme.getMutedForeground() : theme.getErrorForeground());
        } finally {
            g2.dispose();
        }
    }

    private void paintImage(Graphics2D graphics) {
        int availableWidth = Math.max(1, getWidth() - PADDING * 2);
        int availableHeight = Math.max(1, getHeight() - PADDING * 2);
        double scale = Math.min(1.0d, Math.min(
                availableWidth / (double) image.getWidth(),
                availableHeight / (double) image.getHeight()));
        int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int x = Math.max(PADDING, (getWidth() - drawWidth) / 2);
        int y = Math.max(PADDING, (getHeight() - drawHeight) / 2);
        graphics.drawImage(image, x, y, drawWidth, drawHeight, null);
    }

    private void paintStatus(Graphics2D graphics, String text, Color color) {
        graphics.setFont(theme.getBodyFont());
        graphics.setColor(color);
        FontMetrics metrics = graphics.getFontMetrics();
        int x = Math.max(PADDING, (getWidth() - metrics.stringWidth(text)) / 2);
        int y = Math.max(PADDING + metrics.getAscent(), (getHeight() + metrics.getAscent()) / 2);
        graphics.drawString(text, x, y);
    }
}
