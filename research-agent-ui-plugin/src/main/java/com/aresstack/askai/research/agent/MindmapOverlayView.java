package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.image.BufferedImage;

/**
 * The mindmap OVERLAY content: renders the mechanically built Mermaid source to an image on a
 * background worker (GraalJS is expensive; never on the EDT) and shows it scrollable. A render
 * failure shows the renderer's error PLUS the Mermaid source — honest, never a blank panel. When
 * no source qualifies yet, a plain hint replaces the diagram.
 */
final class MindmapOverlayView extends JPanel {

    private static final int RENDER_WIDTH = 900;

    private MindmapOverlayView() {
        super(new BorderLayout());
        setOpaque(false);
    }

    /** The "nothing to visualize yet" state — an honest hint instead of an empty diagram. */
    static MindmapOverlayView empty() {
        MindmapOverlayView view = new MindmapOverlayView();
        JLabel hint = new JLabel(
                "Noch keine bewerteten Quellen — erst suchen (Websuche), dann visualisieren.");
        hint.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        view.add(hint, BorderLayout.CENTER);
        return view;
    }

    /** Render this Mermaid source asynchronously; the view swaps its content when done. */
    static MindmapOverlayView render(final String mermaid, final UiExecutor uiExecutor) {
        final MindmapOverlayView view = new MindmapOverlayView();
        JLabel busy = new JLabel("Rendere Mindmap…");
        busy.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        view.add(busy, BorderLayout.CENTER);
        Thread worker = new Thread(new Runnable() {
            public void run() {
                BufferedImage image = null;
                String error = null;
                try {
                    com.aresstack.mermaid.JsExecutionResult svg =
                            com.aresstack.Mermaid.renderDetailed(mermaid);
                    if (svg.isSuccessful()) {
                        image = com.aresstack.Mermaid.svgToImage(svg.getOutput(), RENDER_WIDTH);
                    } else {
                        error = svg.getErrorMessage();
                    }
                } catch (RuntimeException failed) {
                    error = failed.getMessage() == null ? failed.toString() : failed.getMessage();
                } catch (LinkageError hostMissing) {
                    error = "Mermaid-Renderer nicht verfügbar: " + hostMissing.getMessage();
                }
                final BufferedImage rendered = image;
                final String renderError = error;
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.removeAll();
                        view.add(rendered != null ? diagram(rendered)
                                : failure(renderError, mermaid), BorderLayout.CENTER);
                        view.revalidate();
                        view.repaint();
                    }
                });
            }
        }, "mindmap-render");
        worker.setDaemon(true);
        worker.start();
        return view;
    }

    private static JScrollPane diagram(BufferedImage image) {
        JLabel canvas = new JLabel(new ImageIcon(image));
        JScrollPane scroll = new JScrollPane(canvas);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private static JScrollPane failure(String error, String mermaid) {
        JTextArea details = new JTextArea("Die Mindmap konnte nicht gerendert werden.\n\nFehler:\n"
                + (error == null || error.trim().isEmpty() ? "(unbekannt)" : error)
                + "\n\nMermaid-Quelle:\n" + mermaid);
        details.setEditable(false);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(details);
        scroll.setBorder(null);
        return scroll;
    }
}
