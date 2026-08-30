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
 * fade away "behind" the sky, while the cloud chips sit fully visible above the gradient. No
 * permanent caption: the area's meaning lives in tooltips ("Außerhalb des Scopes"). The sky also
 * publishes a transcript TOP INSET (client property) so the first message is fully readable at
 * scroll position 0 instead of hiding behind the covering zone. {@link #contains(int, int)} claims
 * ONLY the content zone; below it the chat stays clickable and scrollable.
 *
 * <p>Height is dynamic: no exclusions → nothing at all; otherwise the sky grows with the wrapped
 * cloud rows up to {@link ResearchUiMetrics#SKY_COLLAPSED_MAX_ROWS}, then a {@code +N weitere}
 * cloud collapses the tail. Expanding (that cloud or the caption chevron) grows the sky itself —
 * no floating popup — capped at {@link ResearchUiMetrics#SKY_EXPANDED_MAX_PERCENT} of the
 * transcript height; past that only the cloud area scrolls internally. Within SCOPING there is NO
 * blank sky state: with zero exclusions the sky still shows the {@code + Hinzufügen} cloud, so the
 * FIRST exclusion can always be added right here; only leaving the phase hides the sky entirely.
 * Pure view as before: renders what {@link #setExclusions} hands it, reports add/remove intents
 * through the injected actions.</p>
 */
final class ResearchOutOfScopeSky extends JPanel {

    /** The area's meaning lives in the tooltip now — no permanent caption anymore. */
    private static final String SEMANTIC_TOOLTIP = "Außerhalb des Scopes";

    private final CloudFlowPanel cloudFlow = new CloudFlowPanel();
    private final JScrollPane cloudScroll;
    private final MoreCloud moreCloud = new MoreCloud();
    private final AddCloud addCloud = new AddCloud();
    private final JTextField addField = new JTextField(16);
    /** Field + the shared comic ✕ ({@link com.aresstack.comiccontrols.control.ComicOverlayPanel.CloseButton}) as ONE flow entry. */
    private final JPanel addFieldRow = new JPanel(new java.awt.BorderLayout(2, 0)) {
        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, ResearchUiMetrics.CLOUD_CHIP_HEIGHT);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    };

    private Consumer<String> addAction;
    private Consumer<String> removeAction;
    private List<String> exclusions = Collections.emptyList();
    private final List<CloudChip> chips = new ArrayList<CloudChip>();
    private boolean expanded;
    private boolean adding;
    /** Bottom of the interactive content zone (bar or clouds); the fade tail hangs below it. */
    private int contentBottom;
    /**
     * UI-only session preference, never domain state: the sky STARTS as the slim status bar
     * (chevron + count, exactly one search-bar height); clicking it opens the full cloud sky.
     * Collapses again with the accessory's lifetime — an app restart starts collapsed again.
     */
    private boolean open;
    private final SkyBar skyBar = new SkyBar();
    private final CollapseChevron collapseChevron = new CollapseChevron();
    /** The OPEN sky's round read-aloud button — right side, vertically centered in the sky. */
    private final SpeakOrb speakOrb = new SpeakOrb();

    ResearchOutOfScopeSky() {
        super(null); // fully manual layout: the sky sizes itself from its cloud rows
        setOpaque(false);
        setToolTipText(SEMANTIC_TOOLTIP);
        cloudScroll = new JScrollPane(cloudFlow,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        cloudScroll.setBorder(BorderFactory.createEmptyBorder());
        cloudScroll.setOpaque(false);
        cloudScroll.getViewport().setOpaque(false);
        cloudScroll.getVerticalScrollBar().setUnitIncrement(16);
        com.aresstack.comiccontrols.control.ComicScrollBarUI.install(
                cloudScroll.getVerticalScrollBar()); // the ONE shared AskAI scrollbar look
        // Wheel priority: an EXPANDED sky with internal overflow scrolls its clouds first; when
        // there is nothing to scroll internally, the event is handed onward so the TRANSCRIPT
        // scrolls — never both areas at once. (The default scroll-pane wheel handling would
        // swallow the event even with nothing to scroll, killing chat scrolling over the sky.)
        cloudScroll.setWheelScrollingEnabled(false);
        cloudScroll.addMouseWheelListener(event -> {
            javax.swing.BoundedRangeModel model = cloudScroll.getVerticalScrollBar().getModel();
            boolean scrollable = model.getExtent() > 0
                    && model.getExtent() < model.getMaximum() - model.getMinimum();
            if (scrollable) {
                int delta = (int) Math.round(event.getPreciseWheelRotation()
                        * event.getScrollAmount() * 16);
                if (delta == 0 && event.getPreciseWheelRotation() != 0) {
                    delta = event.getPreciseWheelRotation() < 0 ? -1 : 1;
                }
                model.setValue(model.getValue() + delta);
                event.consume();
            } else {
                java.awt.Container parent = getParent();
                if (parent != null) {
                    parent.dispatchEvent(javax.swing.SwingUtilities.convertMouseEvent(
                            (java.awt.Component) event.getSource(), event, parent));
                }
            }
        });
        add(cloudScroll);
        cloudFlow.add(moreCloud);
        cloudFlow.add(addCloud);
        styleAddField();
        cloudFlow.add(addFieldRow);
        add(skyBar);
        add(collapseChevron);
        // Folding is a SKY gesture, not an outside-click: pressing the open sky's own air —
        // the transparent background between the clouds or the visible fade below them — tucks
        // it back into the bar. Clicks that land on a cloud, the orb, the chevron or the chat
        // BELOW the fade never arrive here (children and pass-through keep their own meaning).
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                if (open) {
                    setOpen(false);
                }
            }
        });
        add(speakOrb);
    }

    /** Toggle between the slim status bar and the full cloud sky (pure UI preference). */
    private void setOpen(boolean value) {
        if (open != value) {
            open = value;
            revalidate();
            repaint();
        }
    }

    @Override
    public void removeNotify() {
        speech.stop(); // never a voice talking for a torn-down chat
        super.removeNotify();
    }

    // Visible for tests (same package): the collapsed/open UI preference.
    void setOpenForTest(boolean value) {
        setOpen(value);
    }

    boolean isOpenForTest() {
        return open;
    }

    boolean cloudsShownForTest() {
        return cloudScroll.isVisible();
    }

    // ------------------------------------------------------------------ read-aloud (V1: Windows)

    /**
     * Read-aloud, Gemini-style: the bar's right-hand Play/Pause toggle. Play speaks the LATEST
     * assistant answer and STAYS active — every new answer is spoken automatically as it arrives —
     * until Pause stops it. The voice is chosen by {@link ReadAloudVoice}: the host's configured
     * MODEL voice when one is active, otherwise the Windows default.
     */
    private final ReadAloudVoice speech = new ReadAloudVoice();
    private boolean readAloudActive;
    private String latestAnswerId;
    private String latestAnswerText;
    private String lastSpokenAnswerId;

    /** The host's speech-output service (model voice), or null — wired by the accessory. */
    void setModelVoice(com.aresstack.askai.agent.model.speech.SpeechSynthesisPort port) {
        speech.setModelVoice(port);
    }

    /** The newest assistant answer (host truth) — pushed by the accessory on every refresh. */
    void setLatestAnswer(String messageId, String markdown) {
        this.latestAnswerId = messageId;
        this.latestAnswerText = markdown;
        if (readAloudActive && messageId != null && !messageId.isEmpty()
                && !messageId.equals(lastSpokenAnswerId)) {
            speakLatest(); // a NEW answer arrived while reading is active → read it out
        }
        skyBar.repaint();
        speakOrb.repaint();
    }

    private void speakLatest() {
        final String text = latestAnswerText;
        lastSpokenAnswerId = latestAnswerId;
        Thread speaker = new Thread(new Runnable() {
            public void run() {
                speech.speak(text); // process start + stdin write stay off the EDT
            }
        }, "askai-read-aloud");
        speaker.setDaemon(true);
        speaker.start();
    }

    private void toggleReadAloud() {
        if (readAloudActive) {
            readAloudActive = false;
            speech.stop();
        } else {
            readAloudActive = true;
            if (latestAnswerText != null && !latestAnswerText.trim().isEmpty()) {
                speakLatest(); // Play always (re)reads the latest answer immediately
            }
        }
        skyBar.repaint();
        speakOrb.repaint();
    }

    /** Stop the voice and drop the wish state — accessory dispose / tab switch. */
    void shutdownReadAloud() {
        readAloudActive = false;
        speech.stop();
    }

    boolean isReadAloudActiveForTest() {
        return readAloudActive;
    }

    void setAddAction(Consumer<String> action) {
        this.addAction = action;
    }

    /** For tests: the {@code + Hinzufügen} cloud — always present while the sky is visible. */
    JComponent addCloudForTest() {
        return addCloud;
    }

    int contentBottomForTest() {
        return contentBottom;
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
        }
        // NO empty special case beyond that: within SCOPING the sky is never blank — with zero
        // exclusions it still shows the "+ Hinzufügen" cloud, so the first exclusion can always
        // be added right here (the original cloud pass behaved exactly like this).
        revalidate();
        repaint();
    }

    @Override
    public void setVisible(boolean visible) {
        if (!visible) {
            publishTopInset(0); // other phases: the chat gets its full height back immediately
        }
        super.setVisible(visible);
    }

    /**
     * Tell the host how much top room the transcript's SCROLL GEOMETRY needs (client-property
     * convention, see {@code ComposerAccessory.TRANSCRIPT_TOP_INSET_PROPERTY}): scrolled fully up,
     * the first message must sit below the covering zone — inside the transparent fade is fine.
     */
    private void publishTopInset(int pixels) {
        putClientProperty(com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory
                .TRANSCRIPT_TOP_INSET_PROPERTY, Integer.valueOf(pixels));
    }

    // ------------------------------------------------------------------ hit-testing & painting

    /**
     * OPEN, the sky claims everything it visibly IS — the content zone plus the painted fade
     * tail — so a press on "the transparent sky" folds it. Below the fade (and beside the
     * collapsed bar) the chat keeps every click, hover and wheel.
     */
    @Override
    public boolean contains(int x, int y) {
        int claimBottom = open ? contentBottom + ResearchUiMetrics.SKY_FADE_TAIL : contentBottom;
        return isVisible() && y >= 0 && y <= Math.min(claimBottom, getHeight());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (contentBottom <= 0 || !open) {
            return; // collapsed: the status-bar child paints itself, no gradient behind it
        }
        Graphics2D g2 = ResearchUiPainter.prepare(graphics);
        try {
            int skyHeight = Math.min(getHeight(),
                    contentBottom + ResearchUiMetrics.SKY_FADE_TAIL);
            if (skyHeight <= 0) {
                return;
            }
            Color top = ResearchUiPalette.SKY_TOP;
            // Air, not a panel: strongly covering at the very top, bubbles faintly visible through
            // the middle, fully transparent at the bottom — a LONG soft ramp instead of a filled
            // rectangle, so no horizontal edge is ever perceptible.
            float contentStop = Math.max(0.1f, Math.min(0.85f,
                    contentBottom / (float) skyHeight));
            LinearGradientPaint sky = new LinearGradientPaint(0f, 0f, 0f, skyHeight,
                    new float[]{0f, contentStop * 0.55f, contentStop, 1f},
                    new Color[]{
                            new Color(top.getRed(), top.getGreen(), top.getBlue(), 242),
                            new Color(top.getRed(), top.getGreen(), top.getBlue(), 205),
                            new Color(top.getRed(), top.getGreen(), top.getBlue(), 105),
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
        // Within SCOPING the sky is never absent: collapsed it is the slim status bar, open it is
        // the cloud area — which always carries at least the "+ Hinzufügen" cloud.
        cloudScroll.setVisible(open);
        collapseChevron.setVisible(open);
        speakOrb.setVisible(open);
        skyBar.setVisible(!open);
        if (getWidth() <= 0 || getHeight() <= 0) {
            // Not really laid out yet (first pass before the host sized this layer): claim NO
            // chat space — a positive inset without visible sky would leave an invisible dead
            // zone above the first message.
            contentBottom = 0;
            publishTopInset(0);
            return;
        }
        int padH = ResearchUiMetrics.SKY_PADDING_H;
        if (!open) {
            // Collapsed: a SQUARE full-width strip with EXACTLY the search bar's top air and
            // height (both shared metrics) — so its bottom edge lines up with the drawer's
            // search bar; no side margins, no white flanks.
            int barHeight = com.aresstack.comiccontrols.control.ComicSearchBar.standardHeight();
            skyBar.setBounds(0, ResearchUiMetrics.SLIM_BAR_TOP_GAP, getWidth(), barHeight);
            contentBottom = ResearchUiMetrics.SLIM_BAR_TOP_GAP + barHeight;
            publishTopInset(contentBottom + 6);
            return;
        }
        int chevronSize = 22;
        collapseChevron.setBounds(padH, ResearchUiMetrics.SKY_PADDING_TOP
                + (ResearchUiMetrics.CLOUD_CHIP_HEIGHT - chevronSize) / 2,
                chevronSize, chevronSize);
        int cloudLeft = padH + chevronSize + 8;
        int orbSize = 36;
        // The round read-aloud button reserves the right edge; clouds wrap before it.
        int innerWidth = getWidth() - cloudLeft - padH - orbSize - 10;
        int cloudTop = ResearchUiMetrics.SKY_PADDING_TOP;

        applyVisibility(innerWidth);
        int naturalHeight = flowHeight(visibleFlowChildren(), innerWidth);
        int rowStep = ResearchUiMetrics.CLOUD_CHIP_HEIGHT + ResearchUiMetrics.CLOUD_GAP_V;
        int collapsedCap = ResearchUiMetrics.SKY_COLLAPSED_MAX_ROWS * rowStep
                - ResearchUiMetrics.CLOUD_GAP_V;
        int expandedCap = Math.max(collapsedCap,
                getHeight() * ResearchUiMetrics.SKY_EXPANDED_MAX_PERCENT / 100 - cloudTop);
        // Collapsed content always fits its cap by construction; expanded may scroll internally.
        int viewportHeight = Math.min(naturalHeight, expanded ? expandedCap : collapsedCap);
        cloudScroll.setBounds(cloudLeft, cloudTop, innerWidth, viewportHeight);
        contentBottom = cloudTop + viewportHeight + 8;
        // Nicely CENTERED in the open sky's content zone, not glued to its top.
        speakOrb.setBounds(getWidth() - padH - orbSize,
                Math.max(2, (contentBottom - orbSize) / 2), orbSize, orbSize);
        // The chat's scroll geometry follows: at scroll 0 the first bubble starts just below the
        // covering zone, inside the transparent fade — reachable, readable, and it still slides
        // softly behind the sky as soon as the user scrolls.
        publishTopInset(contentBottom + 8);
    }

    /**
     * Which flow children show. Collapsed hides the tail behind {@code +N weitere}; expanded shows
     * every cloud plus the same control as a "weniger anzeigen" way back. When everything fits
     * anyway, the expansion resolves itself.
     */
    private void applyVisibility(int width) {
        addCloud.setVisible(!adding);
        addFieldRow.setVisible(adding);
        int maxRows = ResearchUiMetrics.SKY_COLLAPSED_MAX_ROWS;
        // Largest k so that k clouds + (tail? +N weitere) + the add control fit the row budget.
        int visibleCount = chips.size();
        while (visibleCount >= 0) {
            moreCloud.setCollapsedCount(chips.size() - visibleCount);
            List<JComponent> candidate = new ArrayList<JComponent>();
            for (int index = 0; index < visibleCount; index++) {
                candidate.add(chips.get(index));
            }
            if (visibleCount < chips.size()) {
                candidate.add(moreCloud);
            }
            candidate.add(adding ? (JComponent) addFieldRow : addCloud);
            if (rowsFor(candidate, width) <= maxRows) {
                break;
            }
            visibleCount--;
        }
        visibleCount = Math.max(0, visibleCount);
        boolean overflow = visibleCount < chips.size();
        if (!overflow) {
            expanded = false; // nothing hidden — an expanded state has nothing left to show
        }
        if (expanded) {
            for (CloudChip chip : chips) {
                chip.setVisible(true);
            }
            moreCloud.setExpandedMode();
            moreCloud.setVisible(true); // the way back: "weniger anzeigen"
            return;
        }
        for (int index = 0; index < chips.size(); index++) {
            chips.get(index).setVisible(index < visibleCount);
        }
        moreCloud.setCollapsedCount(chips.size() - visibleCount);
        moreCloud.setVisible(overflow);
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
        addFieldRow.setVisible(false);
        addFieldRow.setOpaque(true);
        addFieldRow.setBackground(Color.WHITE);
        addFieldRow.setBorder(BorderFactory.createLineBorder(ResearchUiPalette.CLOUD_BORDER));
        addField.setFont(ResearchUiTypography.regular(12.5f));
        addField.setOpaque(false);
        addField.setForeground(ResearchUiPalette.CLOUD_TEXT);
        addField.setCaretColor(ResearchUiPalette.CLOUD_TEXT);
        addField.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 4));
        addFieldRow.add(addField, java.awt.BorderLayout.CENTER);
        // The SAME comic ✕ the mindmap overlay closes with — reused, not copied: cancels the add.
        com.aresstack.comiccontrols.control.ComicOverlayPanel.CloseButton cancel =
                new com.aresstack.comiccontrols.control.ComicOverlayPanel.CloseButton(
                        com.aresstack.comiccontrols.theme.ComicPalette.defaultPalette(),
                        this::endInlineAdd);
        cancel.setToolTipText("Cancel");
        JPanel cancelWrap = new JPanel(new java.awt.GridBagLayout());
        cancelWrap.setOpaque(false);
        cancelWrap.add(cancel);
        addFieldRow.add(cancelWrap, java.awt.BorderLayout.EAST);
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
            // The AREA carries the "Außerhalb des Scopes" meaning (the sky's own tooltip); a chip
            // only repeats its full text — useful when the cloud had to ellipsize, never a prefix.
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

    /**
     * The quiet toggle cloud: {@code +N weitere} while collapsed (clicking it grows the sky
     * itself — no popup), {@code weniger anzeigen} while expanded (the way back).
     */
    private final class MoreCloud extends JComponent {

        private int count;
        private boolean expandedMode;
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
                    expanded = !expandedMode;
                    ResearchOutOfScopeSky.this.revalidate();
                    ResearchOutOfScopeSky.this.repaint();
                }
            });
        }

        void setCollapsedCount(int count) {
            if (this.count != count || expandedMode) {
                this.count = count;
                this.expandedMode = false;
                setToolTipText(text() + " anzeigen");
                // No revalidate() here: this runs INSIDE the sky's doLayout — re-invalidating the
                // ancestors mid-validation is a layout-loop hazard; the flow lays out right after.
            }
        }

        void setExpandedMode() {
            if (!expandedMode) {
                expandedMode = true;
                setToolTipText("wieder einklappen");
            }
        }

        private String text() {
            return expandedMode ? "weniger anzeigen"
                    : "+" + count + (count == 1 ? " weiterer" : " weitere");
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

    /** {@code + Add} in the same cloud look (English like the rest); clicking swaps in the field. */
    private final class AddCloud extends JComponent {

        private static final String TEXT = "+ Add";
        private boolean hovered;

        AddCloud() {
            setToolTipText("Add exclusion");
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

    // ------------------------------------------------------------------ collapsed status bar

    /**
     * The COLLAPSED sky: one calm but clearly-visible status bar (firmer sky blue, chevron-down,
     * mini cloud, the exclusion count). The WHOLE bar is clickable and opens the full sky; the
     * "Außerhalb des Scopes" meaning stays in the tooltip — the bar itself shows status only.
     */
    private final class SkyBar extends JComponent {

        private boolean hovered;
        private boolean speakHovered;

        SkyBar() {
            setName("sky.statusBar"); // stable handle for tests and diagnostics
            setToolTipText(SEMANTIC_TOOLTIP);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent event) {
                    hovered = false;
                    speakHovered = false;
                    repaint();
                }

                @Override
                public void mouseMoved(java.awt.event.MouseEvent event) {
                    boolean inSpeak = speakHit().contains(event.getPoint());
                    if (inSpeak != speakHovered) {
                        speakHovered = inSpeak;
                        repaint();
                    }
                }

                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    if (speakHit().contains(event.getPoint())) {
                        toggleReadAloud(); // the reserved right-hand zone, never an open/close
                    } else {
                        setOpen(true);
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        /** The reserved read-aloud zone at the bar's right edge (Play ↔ Pause, Gemini-style). */
        private java.awt.Rectangle speakHit() {
            int size = 24;
            return new java.awt.Rectangle(getWidth() - size - 10,
                    (getHeight() - size) / 2, size, size);
        }

        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            if (speakHit().contains(event.getPoint())) {
                return readAloudActive
                        ? "Vorlesen pausieren"
                        : "Letzte Antwort vorlesen (neue Antworten werden automatisch vorgelesen)";
            }
            return SEMANTIC_TOOLTIP;
        }

        private String countText() {
            int count = exclusions.size();
            if (count == 0) {
                return "Noch keine Ausschlüsse";
            }
            return count == 1 ? "1 Ausschluss" : count + " Ausschlüsse";
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                Color fill = hovered
                        ? ResearchUiPainter.mix(ResearchUiPalette.SKY_BAR_SURFACE,
                                ResearchUiPalette.CLOUD_HOVER_BORDER, 0.22f)
                        : ResearchUiPalette.SKY_BAR_SURFACE;
                // A SQUARE strip filling the whole width — no rounding, no margins, just the
                // firmer sky blue with a quiet bottom hairline towards the chat.
                g2.setColor(fill);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(ResearchUiPainter.mix(ResearchUiPalette.SKY_BAR_SURFACE,
                        ResearchUiPalette.CLOUD_TEXT, 0.25f));
                g2.fillRect(0, getHeight() - 1, getWidth(), 1);

                int centerY = getHeight() / 2;
                g2.setColor(ResearchUiPalette.CLOUD_TEXT);
                ResearchUiPainter.paintChevronDown(g2, 16, centerY, 5,
                        ResearchUiPalette.CLOUD_TEXT);
                int x = 30;
                paintMiniCloud(g2, x, centerY - 6);
                x += 22;
                g2.setFont(ResearchUiTypography.regular(12.5f));
                FontMetrics metrics = g2.getFontMetrics();
                g2.setColor(ResearchUiPalette.CLOUD_TEXT);
                g2.drawString(countText(), x,
                        (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());

                // The read-aloud toggle in the reserved right-hand zone: ▶ while silent, ❚❚
                // while active (it flips back on Pause) — painted, no emoji.
                java.awt.Rectangle speak = speakHit();
                if (speakHovered) {
                    g2.setColor(ResearchUiPainter.mix(ResearchUiPalette.SKY_BAR_SURFACE,
                            java.awt.Color.WHITE, 0.4f));
                    g2.fillOval(speak.x, speak.y, speak.width, speak.height);
                }
                g2.setColor(ResearchUiPalette.CLOUD_TEXT);
                int cx = speak.x + speak.width / 2;
                int cy = speak.y + speak.height / 2;
                if (readAloudActive) {
                    g2.fillRect(cx - 5, cy - 6, 4, 12);
                    g2.fillRect(cx + 2, cy - 6, 4, 12);
                } else {
                    g2.fillPolygon(new int[]{cx - 4, cx - 4, cx + 6},
                            new int[]{cy - 6, cy + 6, cy}, 3);
                }
            } finally {
                g2.dispose();
            }
        }

        /** An 18×12 mini cloud silhouette — the bar's quiet subject marker, no emoji. */
        private void paintMiniCloud(Graphics2D g2, int x, int y) {
            Area cloud = new Area(new RoundRectangle2D.Float(x, y + 5f, 18f, 7f, 7f, 7f));
            cloud.add(new Area(new Ellipse2D.Float(x + 3f, y + 1f, 8f, 8f)));
            cloud.add(new Area(new Ellipse2D.Float(x + 8f, y, 9f, 9f)));
            g2.setColor(ResearchUiPalette.CLOUD_SURFACE);
            g2.fill(cloud);
            g2.setColor(ResearchUiPalette.CLOUD_TEXT);
            g2.setStroke(new BasicStroke(1.1f));
            g2.draw(cloud);
        }
    }

    /**
     * The OPEN sky's read-aloud control: the bar's Play/Pause zone GROWS into this round comic
     * chip at the right edge, vertically centered in the sky — same toggle, same voice.
     */
    private final class SpeakOrb extends JComponent {

        private boolean hovered;

        SpeakOrb() {
            setName("sky.readAloudOrb");
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
                    toggleReadAloud();
                }
            });
        }

        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            return readAloudActive
                    ? "Vorlesen pausieren"
                    : "Letzte Antwort vorlesen (neue Antworten werden automatisch vorgelesen)";
        }

        {
            setToolTipText(" "); // register with the tooltip manager; text comes dynamically
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                g2.setColor(hovered ? ResearchUiPalette.CLOUD_HOVER_SURFACE
                        : ResearchUiPalette.CLOUD_SURFACE);
                g2.fillOval(1, 1, getWidth() - 2, getHeight() - 2);
                g2.setColor(hovered ? ResearchUiPalette.CLOUD_HOVER_BORDER
                        : ResearchUiPalette.CLOUD_BORDER);
                g2.setStroke(new BasicStroke(1.3f));
                g2.drawOval(1, 1, getWidth() - 3, getHeight() - 3);
                g2.setColor(ResearchUiPalette.CLOUD_TEXT);
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                if (readAloudActive) {
                    g2.fillRect(cx - 6, cy - 7, 4, 14);
                    g2.fillRect(cx + 2, cy - 7, 4, 14);
                } else {
                    g2.fillPolygon(new int[]{cx - 4, cx - 4, cx + 7},
                            new int[]{cy - 7, cy + 7, cy}, 3);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** The small chevron-up in the OPEN sky's top-left corner — folds it back into the bar. */
    private final class CollapseChevron extends JComponent {

        private boolean hovered;

        CollapseChevron() {
            setName("sky.collapse");
            setVisible(false);
            setToolTipText("Einklappen");
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
                    setOpen(false);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                if (hovered) {
                    g2.setColor(ResearchUiPainter.mix(ResearchUiPalette.SKY_BAR_SURFACE,
                            java.awt.Color.WHITE, 0.35f));
                    g2.fillOval(0, 0, getWidth(), getHeight());
                }
                ResearchUiPainter.paintChevronUp(g2, getWidth() / 2, getHeight() / 2, 5,
                        hovered ? ResearchUiPalette.CLOUD_TEXT : ResearchUiPalette.SKY_CAPTION);
            } finally {
                g2.dispose();
            }
        }
    }
}
