package com.aresstack.askai.research.agent;

import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiPainter;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Phase-1 OUT-OF-SCOPE SKY (third pass): exclusions are not a blacklist — they are the themes
 * OUTSIDE the currently examined area, floating as light cloud chips in a soft sky at the TOP of
 * the chat transcript. This component is a see-through layer over the transcript (host layered
 * pane, {@code TRANSCRIPT_OVERLAY} placement): it paints a vertical alpha gradient (near-covering
 * at the very top, fully transparent towards the chat — never a hard edge), so scrolling bubbles
 * fade away "behind" the sky, while the cloud chips and the small caption sit fully visible above
 * the gradient. {@link #contains(int, int)} claims ONLY the content zone; below it the chat stays
 * clickable and scrollable.
 *
 * <p>Height is dynamic: no exclusions → nothing at all; otherwise the sky grows with the wrapped
 * cloud rows up to {@link ResearchUiMetrics#SKY_COLLAPSED_MAX_ROWS}, then a {@code +N weitere}
 * cloud collapses the tail. Expanding (that cloud or the caption chevron) grows the sky itself —
 * no floating popup — capped at {@link ResearchUiMetrics#SKY_EXPANDED_MAX_PERCENT} of the
 * transcript height; past that only the cloud area scrolls internally. Pure view as before:
 * renders what {@link #setExclusions} hands it, reports add/remove intents through the injected
 * actions.</p>
 */
final class ResearchOutOfScopeSky extends JPanel {

    private final CaptionToggle caption = new CaptionToggle();
    private final CloudFlowPanel cloudFlow = new CloudFlowPanel();
    private final JScrollPane cloudScroll;
    private final MoreCloud moreCloud = new MoreCloud();
    private final AddCloud addCloud = new AddCloud();
    private final JTextField addField = new JTextField(16);

    private Consumer<String> addAction;
    private Consumer<String> removeAction;
    private List<String> exclusions = Collections.emptyList();
    private final List<CloudChip> chips = new ArrayList<CloudChip>();
    private boolean expanded;
    private boolean adding;
    /** Bottom of the interactive content zone (caption + clouds); the fade tail hangs below it. */
    private int contentBottom;

    ResearchOutOfScopeSky() {
        super(null); // fully manual layout: the sky sizes itself from its cloud rows
        setOpaque(false);
        add(caption);
        cloudScroll = new JScrollPane(cloudFlow,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        cloudScroll.setBorder(BorderFactory.createEmptyBorder());
        cloudScroll.setOpaque(false);
        cloudScroll.getViewport().setOpaque(false);
        cloudScroll.getVerticalScrollBar().setUnitIncrement(16);
        add(cloudScroll);
        cloudFlow.add(moreCloud);
        cloudFlow.add(addCloud);
        styleAddField();
        cloudFlow.add(addField);
    }

    void setAddAction(Consumer<String> action) {
        this.addAction = action;
    }

    void setRemoveAction(Consumer<String> action) {
        this.removeAction = action;
    }

    /** Re-render from the draft's plain exclusions. EDT only. */
    void setExclusions(List<String> exclusions) {
        this.exclusions = new ArrayList<String>(exclusions);
        for (CloudChip chip : chips) {
            cloudFlow.remove(chip);
        }
        chips.clear();
        int insertAt = 0;
        for (String exclusion : this.exclusions) {
            CloudChip chip = new CloudChip(exclusion);
            chips.add(chip);
            cloudFlow.add(chip, insertAt++); // clouds first; more/add/field stay at the tail
        }
        if (this.exclusions.isEmpty()) {
            expanded = false; // an emptied sky starts over collapsed next time
            adding = false;
        }
        revalidate();
        repaint();
    }

    // ------------------------------------------------------------------ hit-testing & painting

    /** Claim ONLY the content zone; the fade tail and everything below stays the chat's. */
    @Override
    public boolean contains(int x, int y) {
        return isVisible() && !exclusions.isEmpty() && y >= 0 && y <= contentBottom;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (exclusions.isEmpty()) {
            return;
        }
        Graphics2D g2 = ResearchUiPainter.prepare(graphics);
        try {
            int skyHeight = Math.min(getHeight(),
                    contentBottom + ResearchUiMetrics.SKY_FADE_TAIL);
            if (skyHeight <= 0) {
                return;
            }
            Color top = ResearchUiPalette.SKY_TOP;
            // Air, not a panel: nearly covering at the very top, fully transparent at the bottom —
            // bubbles scrolling up fade smoothly away behind the sky, with NO hard edge anywhere.
            LinearGradientPaint sky = new LinearGradientPaint(0f, 0f, 0f, skyHeight,
                    new float[]{0f, Math.max(0.05f, Math.min(0.9f,
                            contentBottom / (float) skyHeight)), 1f},
                    new Color[]{
                            new Color(top.getRed(), top.getGreen(), top.getBlue(), 252),
                            new Color(top.getRed(), top.getGreen(), top.getBlue(), 236),
                            new Color(top.getRed(), top.getGreen(), top.getBlue(), 0)});
            g2.setPaint(sky);
            g2.fillRect(0, 0, getWidth(), skyHeight);

            // Polish: two very faint white cloud forms drifting in the background.
            g2.setColor(new Color(255, 255, 255, 30));
            int w = getWidth();
            g2.fill(new Ellipse2D.Float(w * 0.08f, contentBottom * 0.15f, w * 0.28f,
                    Math.max(18f, contentBottom * 0.4f)));
            g2.fill(new Ellipse2D.Float(w * 0.58f, contentBottom * 0.45f, w * 0.34f,
                    Math.max(16f, contentBottom * 0.35f)));
        } finally {
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ layout

    @Override
    public void doLayout() {
        boolean any = !exclusions.isEmpty();
        caption.setVisible(any);
        cloudScroll.setVisible(any);
        if (!any) {
            contentBottom = 0;
            return;
        }
        int padH = ResearchUiMetrics.SKY_PADDING_H;
        int innerWidth = getWidth() - 2 * padH;
        Dimension captionPref = caption.getPreferredSize();
        caption.setBounds(padH, ResearchUiMetrics.SKY_PADDING_TOP,
                captionPref.width, captionPref.height);
        int cloudTop = ResearchUiMetrics.SKY_PADDING_TOP + captionPref.height + 8;

        applyVisibility(innerWidth);
        int naturalHeight = flowHeight(visibleFlowChildren(), innerWidth);
        int rowStep = ResearchUiMetrics.CLOUD_CHIP_HEIGHT + ResearchUiMetrics.CLOUD_GAP_V;
        int collapsedCap = ResearchUiMetrics.SKY_COLLAPSED_MAX_ROWS * rowStep
                - ResearchUiMetrics.CLOUD_GAP_V;
        int expandedCap = Math.max(collapsedCap,
                getHeight() * ResearchUiMetrics.SKY_EXPANDED_MAX_PERCENT / 100 - cloudTop);
        // Collapsed content always fits its cap by construction; expanded may scroll internally.
        int viewportHeight = Math.min(naturalHeight, expanded ? expandedCap : collapsedCap);
        cloudScroll.setBounds(padH, cloudTop, innerWidth, viewportHeight);
        contentBottom = cloudTop + viewportHeight + 8;
    }

    /** Which flow children show: collapsed hides the tail behind {@code +N weitere}. */
    private void applyVisibility(int width) {
        addCloud.setVisible(!adding);
        addField.setVisible(adding);
        if (expanded) {
            for (CloudChip chip : chips) {
                chip.setVisible(true);
            }
            moreCloud.setVisible(false);
            return;
        }
        int maxRows = ResearchUiMetrics.SKY_COLLAPSED_MAX_ROWS;
        // Largest k so that k clouds + (tail? +N weitere) + the add control fit the row budget.
        int visibleCount = chips.size();
        while (visibleCount >= 0) {
            moreCloud.setCount(chips.size() - visibleCount);
            List<JComponent> candidate = new ArrayList<JComponent>();
            for (int index = 0; index < visibleCount; index++) {
                candidate.add(chips.get(index));
            }
            if (visibleCount < chips.size()) {
                candidate.add(moreCloud);
            }
            candidate.add(adding ? (JComponent) addField : addCloud);
            if (rowsFor(candidate, width) <= maxRows) {
                break;
            }
            visibleCount--;
        }
        visibleCount = Math.max(0, visibleCount);
        for (int index = 0; index < chips.size(); index++) {
            chips.get(index).setVisible(index < visibleCount);
        }
        moreCloud.setCount(chips.size() - visibleCount);
        moreCloud.setVisible(visibleCount < chips.size());
    }

    private List<JComponent> visibleFlowChildren() {
        List<JComponent> visible = new ArrayList<JComponent>();
        for (java.awt.Component child : cloudFlow.getComponents()) {
            if (child.isVisible()) {
                visible.add((JComponent) child);
            }
        }
        return visible;
    }

    /** Simulated left-aligned wrap: row count for these children at this width. */
    private static int rowsFor(List<JComponent> children, int width) {
        int rows = children.isEmpty() ? 0 : 1;
        int x = 0;
        for (JComponent child : children) {
            int childWidth = child.getPreferredSize().width;
            int needed = (x > 0 ? x + ResearchUiMetrics.CLOUD_GAP_H : 0) + childWidth;
            if (x > 0 && needed > width) {
                rows++;
                x = childWidth;
            } else {
                x = needed;
            }
        }
        return rows;
    }

    private static int flowHeight(List<JComponent> children, int width) {
        int rows = rowsFor(children, width);
        return rows == 0 ? 0
                : rows * ResearchUiMetrics.CLOUD_CHIP_HEIGHT
                        + (rows - 1) * ResearchUiMetrics.CLOUD_GAP_V;
    }

    // ------------------------------------------------------------------ inline add

    private void styleAddField() {
        addField.setVisible(false);
        addField.setFont(ResearchUiTypography.regular(12.5f));
        addField.setBackground(Color.WHITE);
        addField.setForeground(ResearchUiPalette.CLOUD_TEXT);
        addField.setCaretColor(ResearchUiPalette.CLOUD_TEXT);
        addField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ResearchUiPalette.CLOUD_BORDER),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        addField.addActionListener(event -> {
            String value = addField.getText().trim();
            endInlineAdd();
            if (!value.isEmpty() && addAction != null) {
                addAction.accept(value); // the state listener re-renders the sky
            }
        });
        addField.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent event) {
                if (event.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    endInlineAdd();
                }
            }
        });
    }

    private void beginInlineAdd() {
        adding = true;
        addField.setText("");
        revalidate();
        repaint();
        addField.requestFocusInWindow();
    }

    private void endInlineAdd() {
        adding = false;
        revalidate();
        repaint();
    }

    // ------------------------------------------------------------------ shared cloud painting

    /** The soft cloud silhouette: rounded body with a gently irregular top contour — no emoji. */
    private static Area cloudShape(int width, int height) {
        float bodyTop = 7f;
        Area cloud = new Area(new RoundRectangle2D.Float(0.6f, bodyTop, width - 1.2f,
                height - bodyTop - 0.6f, height - bodyTop, height - bodyTop));
        if (width > 46) {
            cloud.add(new Area(new Ellipse2D.Float(width * 0.16f, 2.5f, 15f, 15f)));
            cloud.add(new Area(new Ellipse2D.Float(width * 0.42f, 0.8f, 18f, 18f)));
            cloud.add(new Area(new Ellipse2D.Float(width * 0.66f, 3.2f, 13f, 13f)));
        }
        return cloud;
    }

    private static void paintCloudBase(Graphics2D g2, int width, int height, boolean hovered) {
        Area shape = cloudShape(width, height);
        g2.setColor(hovered ? ResearchUiPalette.CLOUD_HOVER_SURFACE
                : ResearchUiPalette.CLOUD_SURFACE);
        g2.fill(shape);
        g2.setColor(hovered ? ResearchUiPalette.CLOUD_HOVER_BORDER
                : ResearchUiPalette.CLOUD_BORDER);
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(shape);
    }

    /** Text baseline for the cloud body (the lower, calm part of the silhouette). */
    private static int cloudTextY(FontMetrics metrics, int height) {
        float bodyTop = 7f;
        return Math.round(bodyTop + (height - bodyTop - metrics.getHeight()) / 2f)
                + metrics.getAscent();
    }

    // ------------------------------------------------------------------ caption

    /** {@code Außerhalb des Scopes} with a chevron when there is something to expand/collapse. */
    private final class CaptionToggle extends JComponent {

        private static final String TEXT = "Außerhalb des Scopes";
        private boolean hovered;

        CaptionToggle() {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent event) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    if (expandable()) {
                        expanded = !expanded;
                        ResearchOutOfScopeSky.this.revalidate();
                        ResearchOutOfScopeSky.this.repaint();
                    }
                }
            });
        }

        private boolean expandable() {
            return expanded || moreCloud.isVisible();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                g2.setFont(ResearchUiTypography.semiBold(11.5f));
                FontMetrics metrics = g2.getFontMetrics();
                g2.setColor(hovered && expandable()
                        ? ResearchUiPalette.CLOUD_TEXT : ResearchUiPalette.SKY_CAPTION);
                int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g2.drawString(TEXT, 0, textY);
                if (expandable()) {
                    int chevronX = metrics.stringWidth(TEXT) + 12;
                    if (expanded) {
                        ResearchUiPainter.paintChevronUp(g2, chevronX, getHeight() / 2, 4,
                                g2.getColor());
                    } else {
                        ResearchUiPainter.paintChevronDown(g2, chevronX, getHeight() / 2, 4,
                                g2.getColor());
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(ResearchUiTypography.semiBold(11.5f));
            return new Dimension(metrics.stringWidth(TEXT) + 12 + 10, 18);
        }
    }

    // ------------------------------------------------------------------ the wrap panel

    /** The cloud flow: left-aligned wrap, width follows the viewport (vertical scroll only). */
    private static final class CloudFlowPanel extends JPanel implements Scrollable {

        CloudFlowPanel() {
            super(new JustifiedTagLayout(ResearchUiMetrics.CLOUD_GAP_H,
                    ResearchUiMetrics.CLOUD_GAP_V, false));
            setOpaque(false);
        }

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation,
                                              int direction) {
            return 16;
        }

        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation,
                                               int direction) {
            return visibleRect.height;
        }

        public boolean getScrollableTracksViewportWidth() {
            return true; // wrap against the viewport width; only the height may overflow
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // ------------------------------------------------------------------ clouds

    /** One out-of-scope theme: cloud silhouette, text, and its own ✕ hit area. */
    private final class CloudChip extends JComponent {

        private final String text;
        private boolean hovered;
        private boolean closeHovered;

        CloudChip(String text) {
            this.text = text;
            setToolTipText(text);
            java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent event) {
                    hovered = false;
                    closeHovered = false;
                    repaint();
                }

                @Override
                public void mouseMoved(java.awt.event.MouseEvent event) {
                    boolean inClose = closeHit().contains(event.getPoint());
                    if (inClose != closeHovered) {
                        closeHovered = inClose;
                        setCursor(Cursor.getPredefinedCursor(
                                inClose ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                        repaint();
                    }
                }

                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    if (closeHit().contains(event.getPoint()) && removeAction != null) {
                        removeAction.accept(CloudChip.this.text);
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private Rectangle closeHit() {
            int hit = ResearchUiMetrics.CLOUD_CLOSE_HIT;
            int x = getWidth() - ResearchUiMetrics.CLOUD_CHIP_PADDING_H - hit + 4;
            return new Rectangle(x, (getHeight() + 7 - hit) / 2, hit, hit);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                paintCloudBase(g2, getWidth(), getHeight(), hovered);
                g2.setFont(ResearchUiTypography.regular(12.5f));
                FontMetrics metrics = g2.getFontMetrics();
                g2.setColor(ResearchUiPalette.CLOUD_TEXT);
                g2.drawString(text, ResearchUiMetrics.CLOUD_CHIP_PADDING_H,
                        cloudTextY(metrics, getHeight()));

                Rectangle close = closeHit();
                g2.setColor(closeHovered ? ResearchUiPalette.CLOUD_TEXT
                        : ResearchUiPalette.CLOUD_CLOSE);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = close.x + close.width / 2;
                int cy = close.y + close.height / 2;
                g2.drawLine(cx - 3, cy - 3, cx + 3, cy + 3);
                g2.drawLine(cx + 3, cy - 3, cx - 3, cy + 3);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(ResearchUiTypography.regular(12.5f));
            int width = ResearchUiMetrics.CLOUD_CHIP_PADDING_H + metrics.stringWidth(text) + 8
                    + ResearchUiMetrics.CLOUD_CLOSE_HIT - 4
                    + ResearchUiMetrics.CLOUD_CHIP_PADDING_H;
            return new Dimension(width, ResearchUiMetrics.CLOUD_CHIP_HEIGHT);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    }

    /** The quiet {@code +N weitere} cloud — clicking it grows the sky itself (no popup). */
    private final class MoreCloud extends JComponent {

        private int count;
        private boolean hovered;

        MoreCloud() {
            setVisible(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent event) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    expanded = true;
                    ResearchOutOfScopeSky.this.revalidate();
                    ResearchOutOfScopeSky.this.repaint();
                }
            });
        }

        void setCount(int count) {
            if (this.count != count) {
                this.count = count;
                setToolTipText(text() + " anzeigen");
            }
        }

        private String text() {
            return "+" + count + (count == 1 ? " weiterer" : " weitere");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                paintCloudBase(g2, getWidth(), getHeight(), hovered);
                g2.setFont(ResearchUiTypography.regular(12.5f));
                FontMetrics metrics = g2.getFontMetrics();
                g2.setColor(hovered ? ResearchUiPalette.CLOUD_TEXT
                        : ResearchUiPalette.CLOUD_CLOSE);
                g2.drawString(text(), ResearchUiMetrics.CLOUD_CHIP_PADDING_H,
                        cloudTextY(metrics, getHeight()));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(ResearchUiTypography.regular(12.5f));
            return new Dimension(metrics.stringWidth(text())
                    + 2 * ResearchUiMetrics.CLOUD_CHIP_PADDING_H,
                    ResearchUiMetrics.CLOUD_CHIP_HEIGHT);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    }

    /** {@code + Hinzufügen} in the same cloud look; clicking swaps in the inline field. */
    private final class AddCloud extends JComponent {

        private static final String TEXT = "+ Hinzufügen";
        private boolean hovered;

        AddCloud() {
            setToolTipText("Ausschluss hinzufügen");
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent event) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    beginInlineAdd();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                paintCloudBase(g2, getWidth(), getHeight(), hovered);
                g2.setFont(ResearchUiTypography.regular(12.5f));
                FontMetrics metrics = g2.getFontMetrics();
                g2.setColor(hovered ? ResearchUiPalette.CLOUD_TEXT
                        : ResearchUiPalette.CLOUD_CLOSE);
                g2.drawString(TEXT, ResearchUiMetrics.CLOUD_CHIP_PADDING_H,
                        cloudTextY(metrics, getHeight()));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(ResearchUiTypography.regular(12.5f));
            return new Dimension(metrics.stringWidth(TEXT)
                    + 2 * ResearchUiMetrics.CLOUD_CHIP_PADDING_H,
                    ResearchUiMetrics.CLOUD_CHIP_HEIGHT);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    }
}
