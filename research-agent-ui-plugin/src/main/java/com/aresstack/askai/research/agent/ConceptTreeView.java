package com.aresstack.askai.research.agent;

import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The concept as a Java2D COMIC TREE (tree-editor slice 1+2): one plate per card — no braces,
 * no JSON — ink connector lines parent→child, suppressed cards dimmed subtree-deep (the
 * effective-mindmap truth made visible), and hover actions behind each plate: ✏ rename inline,
 * ✕ delete (a leaf directly; anything with children only after an explicit confirmation — the
 * deliberate user-owned destructive action the safety slice promised), ＋ add a child card.
 *
 * <p>Every gesture is ONE {@link Actions} call = one ConceptBranchService operation = one
 * revision — the tree never keeps a dirty state and never bypasses the store; ◀▶-rollback in
 * the JSON mode covers missteps. Selection-free by design: rows re-derive from every snapshot,
 * hit-testing runs over the freshly laid-out rectangles. All methods EDT.</p>
 */
public final class ConceptTreeView extends JComponent {

    /** The owner's bridge into ConceptBranchService; every method returns null or the error. */
    public interface Actions {
        String rename(List<String> path, String newName);

        /** Leaf or terminal branch — the guarded delete. */
        String deleteLeafOrTerminal(List<String> path);

        /** A DEEP branch — the host-authorized removal behind the confirmation dialog. */
        String deleteBranch(List<String> path);

        String addChild(List<String> parentPath, String name);
    }

    /** Errors surface in the owner's comic overlay — the tree paints, it does not toast. */
    public interface ErrorSink {
        void error(String message);
    }

    /** The session's live blacklist terms — read per render, never cached as a second truth. */
    public interface BlacklistSource {
        List<String> terms();
    }

    /** One laid-out card row — the static model is unit-tested without any Swing. */
    static final class Row {
        final List<String> path;
        final String name;
        final int depth;
        final boolean leaf;
        final boolean terminal;
        final int subtreeCards;
        final boolean suppressed;
        Rectangle plate = new Rectangle();

        Row(List<String> path, int depth, boolean leaf, boolean terminal, int subtreeCards,
            boolean suppressed) {
            this.path = path;
            this.name = path.get(path.size() - 1);
            this.depth = depth;
            this.leaf = leaf;
            this.terminal = terminal;
            this.subtreeCards = subtreeCards;
            this.suppressed = suppressed;
        }
    }

    private static final int ROW_HEIGHT = 34;
    private static final int INDENT = 26;
    private static final int MARGIN = 14;
    private static final int PLATE_ARC = 12;
    private static final int PLATE_PAD_H = 12;
    private static final int PLATE_HEIGHT = 26;
    private static final int GLYPH_SIZE = 20;
    private static final int GLYPH_GAP = 6;

    private final ComicPalette palette = ComicPalette.defaultPalette();
    private final JTextField inlineEditor = new JTextField();

    private List<Row> rows = new ArrayList<Row>();
    private String documentJson = "";
    private Actions actions;
    private ErrorSink errorSink;

    /** The trailing ghost plate that adds a TOP-LEVEL card. */
    private final Rectangle rootAddPlate = new Rectangle();
    private boolean rootAddHovered;

    private int hoverRow = -1;
    /** 0 = none, 1 = rename, 2 = delete, 3 = add child. */
    private int hoverGlyph;
    /** While the inline editor is open: the edited row (-1 with editingParent = add child). */
    private int editingRow = -1;
    private List<String> editingParentPath;
    /** The click that closed the editor via focus loss must not trigger a row action too. */
    private boolean suppressNextClick;

    /** The painted cancel affordance beside the open inline editor. */
    private final Rectangle inlineCancelRect = new Rectangle();

    public ConceptTreeView() {
        setLayout(null); // the inline editor is the only child, placed by hand
        setOpaque(false);
        inlineEditor.setFont(ResearchUiTypography.semiBold(13f));
        inlineEditor.setOpaque(false);
        inlineEditor.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 10, 2, 10));
        inlineEditor.setForeground(palette.getInk());
        inlineEditor.setCaretColor(palette.getInk());
        inlineEditor.setVisible(false);
        add(inlineEditor);
        inlineEditor.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                commitInlineEdit();
            }
        });
        inlineEditor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    closeInlineEditor();
                }
            }
        });
        inlineEditor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                // Leaving the field CANCELS (never a silent commit); Enter is the only commit.
                closeInlineEditor();
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                updateHover(event.getX(), event.getY());
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if (hoverRow != -1) {
                    hoverRow = -1;
                    hoverGlyph = 0;
                    repaint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                handleClick(event.getX(), event.getY());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void setActions(Actions actions, ErrorSink errorSink) {
        this.actions = actions;
        this.errorSink = errorSink;
    }

    /** Re-derive the rows from one atomic snapshot; an open inline edit survives the refresh. */
    public void render(String documentJson, List<String> blacklistTerms) {
        this.documentJson = documentJson == null ? "" : documentJson;
        this.rows = rowsOf(this.documentJson,
                blacklistTerms == null ? java.util.Collections.<String>emptyList()
                        : blacklistTerms);
        layoutRows();
        revalidate();
        repaint();
    }

    // ------------------------------------------------------------------ model (unit-tested)

    /**
     * The document's card rows in paint order: depth-first, document order, with the structural
     * classes the actions need (leaf / terminal / deep via subtree size) and the suppression
     * truth (exact blacklist name match, SUBTREE-DEEP — exactly the effective-mindmap rule).
     */
    static List<Row> rowsOf(String documentJson, List<String> blacklistTerms) {
        List<Row> rows = new ArrayList<Row>();
        try {
            com.google.gson.JsonElement root =
                    com.google.gson.JsonParser.parseString(documentJson);
            com.google.gson.JsonElement concept = root.isJsonObject()
                    ? root.getAsJsonObject().get("concept") : null;
            if (concept != null && concept.isJsonArray()) {
                collectRows(concept.getAsJsonArray(), new ArrayList<String>(), 0, false,
                        blacklistTerms, rows);
            }
        } catch (RuntimeException unreadable) {
            return new ArrayList<Row>(); // the JSON mode shows and repairs broken documents
        }
        return rows;
    }

    private static void collectRows(com.google.gson.JsonArray level, List<String> prefix,
                                    int depth, boolean parentSuppressed,
                                    List<String> blacklistTerms, List<Row> rows) {
        for (com.google.gson.JsonElement element : level) {
            if (!element.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, com.google.gson.JsonElement> entry
                    : element.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue; // sealed value properties carry no card
                }
                com.google.gson.JsonArray children = entry.getValue().getAsJsonArray();
                List<String> path = new ArrayList<String>(prefix);
                path.add(entry.getKey());
                boolean suppressed = parentSuppressed
                        || isBlacklisted(entry.getKey(), blacklistTerms);
                int subtree = countCards(children);
                rows.add(new Row(path, depth, subtree == 0,
                        subtree > 0 && allChildrenAreLeaves(children), subtree, suppressed));
                collectRows(children, path, depth + 1, suppressed, blacklistTerms, rows);
            }
        }
    }

    private static boolean isBlacklisted(String name, List<String> terms) {
        for (String term : terms) {
            if (term != null && name.trim().equalsIgnoreCase(term.trim())) {
                return true;
            }
        }
        return false;
    }

    private static int countCards(com.google.gson.JsonArray level) {
        int count = 0;
        for (com.google.gson.JsonElement element : level) {
            if (!element.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, com.google.gson.JsonElement> entry
                    : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonArray()) {
                    count += 1 + countCards(entry.getValue().getAsJsonArray());
                }
            }
        }
        return count;
    }

    private static boolean allChildrenAreLeaves(com.google.gson.JsonArray children) {
        for (com.google.gson.JsonElement element : children) {
            if (!element.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, com.google.gson.JsonElement> entry
                    : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonArray()
                        && entry.getValue().getAsJsonArray().size() > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ layout & painting

    private void layoutRows() {
        FontMetrics metrics = getFontMetrics(ResearchUiTypography.semiBold(13f));
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            int x = MARGIN + row.depth * INDENT;
            int y = MARGIN + index * ROW_HEIGHT;
            int width = metrics.stringWidth(row.name) + 2 * PLATE_PAD_H;
            row.plate.setBounds(x, y, Math.max(48, width), PLATE_HEIGHT);
        }
        rootAddPlate.setBounds(MARGIN, MARGIN + rows.size() * ROW_HEIGHT + 4, 110,
                PLATE_HEIGHT);
    }

    @Override
    public Dimension getPreferredSize() {
        int width = 320;
        for (Row row : rows) {
            width = Math.max(width, row.plate.x + row.plate.width
                    + 3 * (GLYPH_SIZE + GLYPH_GAP) + MARGIN);
        }
        return new Dimension(width,
                2 * MARGIN + Math.max(1, rows.size()) * ROW_HEIGHT + ROW_HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (rows.isEmpty()) {
                g2.setFont(ResearchUiTypography.regular(12.5f));
                g2.setColor(new Color(0x888888));
                g2.drawString("No concept cards yet — describe in the chat what you want to "
                        + "research, or add the first card below.", MARGIN, MARGIN + 16);
            }
            paintConnectors(g2);
            for (int index = 0; index < rows.size(); index++) {
                paintRow(g2, index);
            }
            paintRootAddPlate(g2);
            if (inlineEditor.isVisible()) {
                paintInlineEditorPlate(g2);
            }
        } finally {
            g2.dispose();
        }
    }

    /** Ink elbows: down from the parent plate's left edge, then across to the child plate. */
    private void paintConnectors(Graphics2D g2) {
        g2.setColor(palette.getInk());
        g2.setStroke(new BasicStroke(1.2f));
        for (int index = 0; index < rows.size(); index++) {
            Row child = rows.get(index);
            if (child.depth == 0) {
                continue;
            }
            for (int parentIndex = index - 1; parentIndex >= 0; parentIndex--) {
                Row parent = rows.get(parentIndex);
                if (parent.depth == child.depth - 1) {
                    int x = parent.plate.x + 10;
                    int childMid = child.plate.y + child.plate.height / 2;
                    g2.drawLine(x, parent.plate.y + parent.plate.height, x, childMid);
                    g2.drawLine(x, childMid, child.plate.x - 2, childMid);
                    break;
                }
            }
        }
    }

    private void paintRow(Graphics2D g2, int index) {
        Row row = rows.get(index);
        boolean hovered = index == hoverRow;
        Color plateFill = hovered ? new Color(0xFFF8E1) : Color.WHITE;
        Color border = row.suppressed ? new Color(0xBBBBBB) : palette.getInk();
        Color text = row.suppressed ? new Color(0x999999) : palette.getInk();

        g2.setColor(plateFill);
        g2.fillRoundRect(row.plate.x, row.plate.y, row.plate.width, row.plate.height,
                PLATE_ARC, PLATE_ARC);
        g2.setColor(border);
        g2.setStroke(new BasicStroke(row.suppressed ? 1.0f : 1.4f));
        g2.drawRoundRect(row.plate.x, row.plate.y, row.plate.width, row.plate.height,
                PLATE_ARC, PLATE_ARC);
        g2.setFont(ResearchUiTypography.semiBold(13f));
        g2.setColor(text);
        FontMetrics metrics = g2.getFontMetrics();
        int baseline = row.plate.y + (row.plate.height + metrics.getAscent()
                - metrics.getDescent()) / 2;
        g2.drawString(row.name, row.plate.x + PLATE_PAD_H, baseline);
        if (row.suppressed) {
            int mid = row.plate.y + row.plate.height / 2;
            g2.drawLine(row.plate.x + PLATE_PAD_H, mid,
                    row.plate.x + row.plate.width - PLATE_PAD_H, mid);
        }
        if (hovered && editingRow < 0 && editingParentPath == null) {
            paintGlyph(g2, glyphRect(row, 1), 1, hoverGlyph == 1);
            paintGlyph(g2, glyphRect(row, 2), 2, hoverGlyph == 2);
            paintGlyph(g2, glyphRect(row, 3), 3, hoverGlyph == 3);
        }
    }

    /** The open inline editor rides a comic plate of its own, with a painted cancel ✕. */
    private void paintInlineEditorPlate(Graphics2D g2) {
        Rectangle b = inlineEditor.getBounds();
        g2.setColor(Color.WHITE);
        g2.fillRoundRect(b.x - 2, b.y - 2, b.width + 4, b.height + 4, PLATE_ARC, PLATE_ARC);
        g2.setColor(palette.getInk());
        g2.setStroke(new BasicStroke(1.4f));
        g2.drawRoundRect(b.x - 2, b.y - 2, b.width + 4, b.height + 4, PLATE_ARC, PLATE_ARC);
        inlineCancelRect.setBounds(b.x + b.width + GLYPH_GAP + 2,
                b.y + (b.height - GLYPH_SIZE) / 2, GLYPH_SIZE, GLYPH_SIZE);
        paintGlyph(g2, inlineCancelRect, 2, false);
    }

    /** The dashed ghost plate: click → inline field → a new TOP-LEVEL card (addChild([])). */
    private void paintRootAddPlate(Graphics2D g2) {
        g2.setColor(rootAddHovered ? new Color(0xFFF8E1) : new Color(0xFAFAFA));
        g2.fillRoundRect(rootAddPlate.x, rootAddPlate.y, rootAddPlate.width,
                rootAddPlate.height, PLATE_ARC, PLATE_ARC);
        g2.setColor(rootAddHovered ? palette.getInk() : new Color(0x999999));
        g2.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                1f, new float[] {4f, 3f}, 0f));
        g2.drawRoundRect(rootAddPlate.x, rootAddPlate.y, rootAddPlate.width,
                rootAddPlate.height, PLATE_ARC, PLATE_ARC);
        g2.setFont(ResearchUiTypography.semiBold(12.5f));
        FontMetrics metrics = g2.getFontMetrics();
        g2.drawString("+ card", rootAddPlate.x + (rootAddPlate.width
                        - metrics.stringWidth("+ card")) / 2,
                rootAddPlate.y + (rootAddPlate.height + metrics.getAscent()
                        - metrics.getDescent()) / 2);
    }

    /**
     * Hand-drawn Java2D ink icons (1 = pencil, 2 = cross, 3 = plus) — never OS font glyphs,
     * whose shape and metrics vary per platform (the live screenshot showed tofu boxes).
     */
    private void paintGlyph(Graphics2D g2, Rectangle rect, int icon, boolean hot) {
        g2.setColor(hot ? palette.getAccentYellow() : Color.WHITE);
        g2.fillOval(rect.x, rect.y, rect.width, rect.height);
        g2.setColor(palette.getInk());
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawOval(rect.x, rect.y, rect.width, rect.height);
        int cx = rect.x + rect.width / 2;
        int cy = rect.y + rect.height / 2;
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (icon == 1) { // pencil: shaft + tip
            g2.drawLine(cx + 3, cy - 4, cx - 2, cy + 1);
            g2.drawLine(cx - 2, cy + 1, cx - 4, cy + 4);
            g2.drawLine(cx - 4, cy + 4, cx - 1, cy + 2);
        } else if (icon == 2) { // cross
            g2.drawLine(cx - 3, cy - 3, cx + 3, cy + 3);
            g2.drawLine(cx + 3, cy - 3, cx - 3, cy + 3);
        } else { // plus
            g2.drawLine(cx - 4, cy, cx + 4, cy);
            g2.drawLine(cx, cy - 4, cx, cy + 4);
        }
    }

    private Rectangle glyphRect(Row row, int slot) {
        int x = row.plate.x + row.plate.width + GLYPH_GAP
                + (slot - 1) * (GLYPH_SIZE + GLYPH_GAP);
        int y = row.plate.y + (row.plate.height - GLYPH_SIZE) / 2;
        return new Rectangle(x, y, GLYPH_SIZE, GLYPH_SIZE);
    }

    // ------------------------------------------------------------------ interaction

    private void updateHover(int x, int y) {
        suppressNextClick = false; // any mouse travel re-arms normal clicking
        boolean overRootAdd = rootAddPlate.contains(x, y);
        if (overRootAdd != rootAddHovered) {
            rootAddHovered = overRootAdd;
            repaint();
        }
        int newRow = -1;
        int newGlyph = 0;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            Rectangle band = new Rectangle(0, row.plate.y - (ROW_HEIGHT - PLATE_HEIGHT) / 2,
                    getWidth(), ROW_HEIGHT);
            if (band.contains(x, y)) {
                newRow = index;
                for (int slot = 1; slot <= 3; slot++) {
                    if (glyphRect(row, slot).contains(x, y)) {
                        newGlyph = slot;
                    }
                }
                break;
            }
        }
        if (newRow != hoverRow || newGlyph != hoverGlyph) {
            hoverRow = newRow;
            hoverGlyph = newGlyph;
            setToolTipText(newGlyph == 1 ? "Rename card"
                    : newGlyph == 2 ? "Delete card"
                    : newGlyph == 3 ? "Add a child card" : null);
            repaint();
        }
    }

    private void handleClick(int x, int y) {
        if (inlineEditor.isVisible()) {
            // The canvas is NOT focusable, so a click beside the field never triggers the
            // text field's focusLost — the painted cancel ✕ (and any click outside the
            // field) must close the editor explicitly here.
            closeInlineEditor();
            return;
        }
        if (suppressNextClick) {
            suppressNextClick = false;
            return;
        }
        if (actions == null) {
            return;
        }
        if (rootAddHovered) {
            openInlineEditor(new Rectangle(rootAddPlate.x, rootAddPlate.y, 200, PLATE_HEIGHT),
                    "", -1, new ArrayList<String>());
            return;
        }
        if (hoverRow < 0 || hoverRow >= rows.size() || hoverGlyph == 0) {
            return;
        }
        Row row = rows.get(hoverRow);
        if (hoverGlyph == 1) {
            openInlineEditor(row.plate, row.name, hoverRow, null);
        } else if (hoverGlyph == 2) {
            deleteRow(row);
        } else if (hoverGlyph == 3) {
            Rectangle below = new Rectangle(row.plate.x + INDENT,
                    row.plate.y + ROW_HEIGHT - 2, Math.max(160, row.plate.width), PLATE_HEIGHT);
            openInlineEditor(below, "", -1, row.path);
        }
    }

    private void deleteRow(Row row) {
        String error;
        if (row.leaf) {
            error = actions.deleteLeafOrTerminal(row.path);
        } else {
            // Anything WITH children is a deliberate, confirmed user decision — never a
            // silent subtree wipe (the safety-slice promise, now as the user's own control).
            int choice = JOptionPane.showConfirmDialog(this,
                    "Delete \"" + row.name + "\" with " + row.subtreeCards + " sub-card"
                            + (row.subtreeCards == 1 ? "" : "s") + "?\n"
                            + "The revision history (◀ in JSON mode) can restore it.",
                    "Delete branch", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            error = row.terminal ? actions.deleteLeafOrTerminal(row.path)
                    : actions.deleteBranch(row.path);
        }
        if (error != null && errorSink != null) {
            errorSink.error(error);
        }
    }

    private void openInlineEditor(Rectangle where, String initialText, int renameRow,
                                  List<String> addChildParent) {
        editingRow = renameRow;
        editingParentPath = addChildParent;
        inlineEditor.setText(initialText);
        inlineEditor.setBounds(where.x, where.y, Math.max(160, where.width + 40),
                where.height);
        inlineEditor.setVisible(true);
        inlineEditor.requestFocusInWindow();
        inlineEditor.selectAll();
        repaint();
    }

    private void commitInlineEdit() {
        String value = inlineEditor.getText().trim();
        if (actions == null) {
            closeInlineEditor();
            return;
        }
        if (value.isEmpty()) {
            if (errorSink != null) {
                errorSink.error("A card name must not be empty — Escape cancels.");
            }
            return; // the field stays open, the user decides
        }
        String error = null;
        if (editingRow >= 0 && editingRow < rows.size()) {
            Row row = rows.get(editingRow);
            if (!value.equals(row.name)) {
                error = actions.rename(row.path, value);
            }
        } else if (editingParentPath != null) {
            error = actions.addChild(editingParentPath, value);
        }
        if (error != null) {
            if (errorSink != null) {
                errorSink.error(error); // the typed text survives a service rejection
            }
            inlineEditor.requestFocusInWindow();
            return;
        }
        closeInlineEditor();
    }

    private void closeInlineEditor() {
        if (inlineEditor.isVisible()) {
            suppressNextClick = true; // a focus-loss click lands right after this close
        }
        editingRow = -1;
        editingParentPath = null;
        inlineEditor.setVisible(false);
        repaint();
    }

    /** For the owner's tests/diagnostics: the current row count. */
    int rowCount() {
        return rows.size();
    }
}
