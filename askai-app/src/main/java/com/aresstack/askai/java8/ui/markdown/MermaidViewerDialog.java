package com.aresstack.askai.java8.ui.markdown;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;

/**
 * A minimal, modeless viewer that shows one Mermaid diagram enlarged with real (re-rendered) zoom.
 *
 * <p>Zooming re-renders the diagram from its Mermaid source at the target pixel width through the
 * shared {@link MermaidImageRenderer} chain, so the result stays crisp instead of being a scaled
 * bitmap. While a re-render runs, the current image is bitmap-scaled as a placeholder. {@code Esc}
 * closes the viewer; {@code Ctrl}+mouse-wheel and the toolbar buttons change the zoom.</p>
 */
public final class MermaidViewerDialog extends JDialog {

    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 4.0;
    private static final double ZOOM_STEP = 0.25;
    private static final int MIN_RENDER_WIDTH = 200;
    private static final int MAX_RENDER_WIDTH = 6000;

    private final String diagramCode;
    private final MermaidImageRenderer imageRenderer;
    private final int baseWidth;
    private final DiagramView view;
    private final JLabel zoomLabel;

    private double zoom = 1.0;
    private BufferedImage image;
    private long renderToken;

    private MermaidViewerDialog(Window owner, String diagramCode, MarkdownTheme theme,
                                MermaidImageRenderer imageRenderer, int baseWidth, BufferedImage initial) {
        super(owner, "Mermaid diagram", ModalityType.MODELESS);
        this.diagramCode = diagramCode;
        this.imageRenderer = imageRenderer;
        this.baseWidth = Math.max(MIN_RENDER_WIDTH, baseWidth);
        this.image = initial;
        this.view = new DiagramView();
        this.zoomLabel = new JLabel("100%", SwingConstants.CENTER);
        this.zoomLabel.setPreferredSize(new Dimension(56, 20));

        JScrollPane scroll = new JScrollPane(view);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        setLayout(new BorderLayout());
        add(buildToolbar(theme), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        installKeyBindings();
        view.addMouseWheelListener(event -> {
            if (event.isControlDown()) {
                changeZoom(event.getWheelRotation() < 0 ? ZOOM_STEP : -ZOOM_STEP);
            } else {
                view.getParent().dispatchEvent(event);
            }
        });

        view.updateForImage();
        rerender();
    }

    /** Open the viewer for {@code diagramCode}, anchored to {@code owner}. */
    public static void open(Window owner, String diagramCode, MarkdownTheme theme,
                            MermaidImageRenderer imageRenderer, int baseWidth, BufferedImage initial) {
        MermaidViewerDialog dialog = new MermaidViewerDialog(
                owner, diagramCode == null ? "" : diagramCode, theme, imageRenderer, baseWidth, initial);
        dialog.setSize(new Dimension(900, 640));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private JComponent buildToolbar(MarkdownTheme theme) {
        JButton zoomOut = new JButton("−"); // minus sign
        zoomOut.setToolTipText("Zoom out");
        zoomOut.addActionListener(event -> changeZoom(-ZOOM_STEP));
        JButton zoomIn = new JButton("+");
        zoomIn.setToolTipText("Zoom in");
        zoomIn.addActionListener(event -> changeZoom(ZOOM_STEP));
        JButton reset = new JButton("100%");
        reset.setToolTipText("Reset zoom");
        reset.addActionListener(event -> setZoom(1.0));

        JPanel toolbar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
        toolbar.setBackground(theme.getSeparatorColor());
        toolbar.add(zoomOut);
        toolbar.add(zoomLabel);
        toolbar.add(zoomIn);
        toolbar.add(reset);
        return toolbar;
    }

    private void installKeyBindings() {
        JComponent root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        root.getActionMap().put("close", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                dispose();
            }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("control PLUS"), "zoomIn");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("control EQUALS"), "zoomIn");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("control MINUS"), "zoomOut");
        root.getActionMap().put("zoomIn", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                changeZoom(ZOOM_STEP);
            }
        });
        root.getActionMap().put("zoomOut", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                changeZoom(-ZOOM_STEP);
            }
        });
    }

    private void changeZoom(double delta) {
        setZoom(zoom + delta);
    }

    private void setZoom(double newZoom) {
        double clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Math.round(newZoom / ZOOM_STEP) * ZOOM_STEP));
        if (Math.abs(clamped - zoom) < 0.001) {
            return;
        }
        zoom = clamped;
        zoomLabel.setText(Math.round(zoom * 100) + "%");
        rerender();
    }

    /** Re-render the diagram at the current zoom width; keep the old image visible meanwhile. */
    private void rerender() {
        final int targetWidth = Math.max(MIN_RENDER_WIDTH,
                Math.min(MAX_RENDER_WIDTH, (int) Math.round(baseWidth * zoom)));
        final long token = ++renderToken;
        view.updateForImage(); // reflect the new zoom immediately via bitmap scaling
        if (diagramCode.trim().isEmpty()) {
            return;
        }
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                return imageRenderer.render(diagramCode, targetWidth);
            }

            @Override
            protected void done() {
                if (token != renderToken) {
                    return; // a newer zoom superseded this render
                }
                try {
                    BufferedImage rendered = get();
                    if (rendered != null) {
                        image = rendered;
                    }
                } catch (Exception ignored) {
                    // Keep the previous image on a failed re-render.
                } finally {
                    view.updateForImage();
                }
            }
        }.execute();
    }

    /** Canvas painting the diagram at the current zoom, sized so the scroll pane can pan it. */
    private final class DiagramView extends JComponent {

        DiagramView() {
            setOpaque(true);
            setBackground(Color.WHITE);
        }

        void updateForImage() {
            if (image != null) {
                int w = (int) Math.round(baseWidth * zoom);
                int h = (int) Math.round(image.getHeight() * (w / (double) image.getWidth()));
                setPreferredSize(new Dimension(Math.max(1, w), Math.max(1, h)));
            }
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
            } finally {
                g2.dispose();
            }
        }
    }
}
