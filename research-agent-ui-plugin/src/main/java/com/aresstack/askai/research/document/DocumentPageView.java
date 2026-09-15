package com.aresstack.askai.research.document;

import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.JComponent;
import javax.swing.Scrollable;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #43 slice 9 — the Document PAGE canvas (Java2D spike ahead of #41): the growing document
 * renders as a paper page (margins, chapter hierarchy, readable flowing text) instead of one
 * big markdown block. Inline source markers {@code [n]} are FIRST-CLASS: painted as accent
 * badges, hover shows the resolved source title, a click navigates to the source. The view
 * defines the CONTRACT (#41 couples the real writing/citation logic to it): a
 * {@link ReferenceResolver} answers what a number means, a {@link ReferenceListener} handles
 * the navigation — the view itself owns neither.
 */
public final class DocumentPageView extends JComponent implements Scrollable {

    /** Resolves a marker number to a short source label, or {@code null} when unknown. */
    public interface ReferenceResolver {
        String describeReference(int number);
    }

    /** Receives clicks on a {@code [n]} badge (navigate to the source). */
    public interface ReferenceListener {
        void referenceClicked(int number);
    }

    private static final int PAGE_MARGIN = 28;
    private static final int PAGE_PADDING = 26;
    private static final int LINE_GAP = 5;
    private static final int BLOCK_GAP = 12;

    /** One clickable painted badge. */
    private static final class Badge {
        final Rectangle bounds;
        final int number;

        Badge(Rectangle bounds, int number) {
            this.bounds = bounds;
            this.number = number;
        }
    }

    private List<DocumentPageModel.Block> blocks = Collections.emptyList();
    private final List<Badge> badges = new ArrayList<Badge>();
    private ReferenceResolver resolver;
    private ReferenceListener listener;
    private int layoutWidth = 640;
    private int contentHeight = 200;

    public DocumentPageView() {
        setOpaque(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                Badge badge = badgeAt(event.getPoint());
                if (badge != null && listener != null) {
                    listener.referenceClicked(badge.number);
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                Badge badge = badgeAt(event.getPoint());
                if (badge == null) {
                    setToolTipText(null);
                    setCursor(java.awt.Cursor.getDefaultCursor());
                    return;
                }
                String described = resolver == null ? null
                        : resolver.describeReference(badge.number);
                // No resolved citation → say so honestly; never show a guessed source.
                setToolTipText(described == null
                        ? "[" + badge.number + "] unresolved citation"
                        : "[" + badge.number + "] " + described);
                setCursor(listener == null ? java.awt.Cursor.getDefaultCursor()
                        : java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void setReferenceResolver(ReferenceResolver resolver) {
        this.resolver = resolver;
    }

    public void setReferenceListener(ReferenceListener listener) {
        this.listener = listener;
    }

    public void setDocumentMarkdown(String markdown) {
        this.blocks = DocumentPageModel.parse(markdown);
        revalidate();
        repaint();
    }

    public boolean hasContent() {
        return !blocks.isEmpty();
    }

    private Badge badgeAt(java.awt.Point point) {
        for (Badge badge : badges) {
            if (badge.bounds.contains(point)) {
                return badge;
            }
        }
        return null;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(Math.max(360, layoutWidth), contentHeight);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            layoutWidth = getWidth();
            badges.clear();

            int pageX = PAGE_MARGIN;
            int pageWidth = Math.max(300, getWidth() - 2 * PAGE_MARGIN);
            int textX = pageX + PAGE_PADDING;
            int textWidth = pageWidth - 2 * PAGE_PADDING;
            int y = PAGE_MARGIN + PAGE_PADDING;

            // First pass paints text and collects the height; the paper is drawn beneath by
            // painting it FIRST with last pass's height (stable after one relayout).
            g2.setColor(java.awt.Color.WHITE);
            g2.fillRoundRect(pageX, PAGE_MARGIN, pageWidth,
                    Math.max(120, contentHeight - 2 * PAGE_MARGIN), 14, 14);
            g2.setColor(ResearchUiPalette.TEXT_PRIMARY);
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(pageX, PAGE_MARGIN, pageWidth,
                    Math.max(120, contentHeight - 2 * PAGE_MARGIN), 14, 14);

            for (DocumentPageModel.Block block : blocks) {
                Font font = fontFor(block.kind);
                g2.setFont(font);
                FontMetrics metrics = g2.getFontMetrics();
                if (block.kind != DocumentPageModel.Kind.PARAGRAPH) {
                    y += BLOCK_GAP / 2;
                }
                y = paintWrapped(g2, block.text, textX, y, textWidth, metrics);
                y += BLOCK_GAP;
            }
            int newHeight = y + PAGE_PADDING + PAGE_MARGIN;
            if (newHeight != contentHeight) {
                contentHeight = newHeight;
                revalidate();
            }
        } finally {
            g2.dispose();
        }
    }

    private Font fontFor(DocumentPageModel.Kind kind) {
        switch (kind) {
            case HEADING_1:
                return ResearchUiTypography.semiBold(19f);
            case HEADING_2:
                return ResearchUiTypography.semiBold(16f);
            case HEADING_3:
                return ResearchUiTypography.semiBold(14f);
            default:
                return ResearchUiTypography.regular(13.5f);
        }
    }

    /** Greedy word wrap; {@code [n]} tokens paint as clickable accent badges. Returns next y. */
    private int paintWrapped(Graphics2D g2, String text, int x, int y, int width,
                             FontMetrics metrics) {
        int lineHeight = metrics.getHeight() + LINE_GAP;
        int cursorX = x;
        int baseline = y + metrics.getAscent();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            int wordWidth = metrics.stringWidth(word + " ");
            if (cursorX + wordWidth > x + width && cursorX > x) {
                cursorX = x;
                baseline += lineHeight;
            }
            Integer reference = referenceNumberOf(word);
            if (reference != null) {
                // The badge: accent pill around the marker — visible, hoverable, clickable.
                int badgeWidth = metrics.stringWidth(word) + 6;
                int badgeTop = baseline - metrics.getAscent() - 1;
                Rectangle bounds = new Rectangle(cursorX - 3, badgeTop,
                        badgeWidth, metrics.getHeight() + 2);
                g2.setColor(new Color(0xB0, 0x52, 0x18, 28));
                g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8);
                g2.setColor(new Color(0xB0, 0x52, 0x18));
                g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8);
                g2.drawString(word, cursorX, baseline);
                badges.add(new Badge(bounds, reference.intValue()));
                g2.setColor(ResearchUiPalette.TEXT_PRIMARY);
            } else {
                g2.setColor(ResearchUiPalette.TEXT_PRIMARY);
                g2.drawString(word, cursorX, baseline);
            }
            cursorX += wordWidth;
        }
        return baseline + metrics.getDescent() + LINE_GAP;
    }

    /** {@code [7]} or {@code [7].}/{@code [7],} → 7; anything else → null. */
    private static Integer referenceNumberOf(String word) {
        String core = word;
        while (core.length() > 0 && ".,;:".indexOf(core.charAt(core.length() - 1)) >= 0) {
            core = core.substring(0, core.length() - 1);
        }
        if (core.length() >= 3 && core.charAt(0) == '[' && core.endsWith("]")) {
            try {
                return Integer.valueOf(core.substring(1, core.length() - 1));
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Scrollable

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == javax.swing.SwingConstants.VERTICAL
                ? visible.height : visible.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    // ------------------------------------------------------------------ test accessors

    int badgeCountForTest() {
        return badges.size();
    }
}
