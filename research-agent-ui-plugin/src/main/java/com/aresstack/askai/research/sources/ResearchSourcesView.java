package com.aresstack.askai.research.sources;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Structured, writing sources view for the shared artifact area. All persistence goes through
 * {@link ResearchSourceRepository} with optimistic locking — never through the {@code TableModel} directly. A
 * conflict reloads the current record and keeps the user informed instead of silently overwriting. Section
 * links that are no longer in the outline are shown as orphans, never auto-removed.
 *
 * <p>UX contract: the TABLE answers "what did the search collect and what is it worth?" at a glance — few,
 * readable columns (star, title, website, status, score, text state), sortable (score descending first),
 * full title/URL as tooltip. Everything else (ratings, sections, texts, comment) lives in the DETAIL below,
 * whose long texts sit in tabs instead of stacked postage-stamp areas. Raw enum names never reach the user;
 * they are rendered as German labels.</p>
 */
public final class ResearchSourcesView extends JPanel {

    private final ResearchSourceRepository repository;
    private final Set<String> knownSectionIds;

    private final SourcesTableModel tableModel = new SourcesTableModel();
    /** Cell tooltips carry what the columns cannot: the full title and URL of the row under the mouse. */
    private final JTable table = new JTable(tableModel) {
        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            int viewRow = rowAtPoint(event.getPoint());
            if (viewRow < 0) {
                return null;
            }
            ResearchSourceRecord record = tableModel.rowAt(convertRowIndexToModel(viewRow));
            String url = record.getUrl().isEmpty() ? record.getOrigin() : record.getUrl();
            return "<html><b>" + escape(displayTitle(record)) + "</b><br>" + escape(url) + "</html>";
        }
    };
    /** The navigation-blue comic search bar replaces the plain "Filter:" field (issue #36 line). */
    private final com.aresstack.comiccontrols.control.ComicSearchBar filterField =
            new com.aresstack.comiccontrols.control.ComicSearchBar(
                    "Titel/URL filtern…", "Filtert nach Titel/URL — Enter wendet an");

    private final JTextField titleField = new JTextField();
    private final JTextField urlField = new JTextField();
    private final JTextField authorField = new JTextField();
    private final JTextField sectionsField = new JTextField();
    private final JTextArea commentArea = new JTextArea(3, 20);
    // Pipeline-filled, read-only: the reranker score, the search excerpt and the visited page's full text.
    private final JTextField scoreField = readOnlyField();
    private final JTextArea excerptArea = readOnlyArea(2);
    private final JTextArea fullTextArea = readOnlyArea(6);
    private final JComboBox<SourceStatus> statusCombo = new JComboBox<SourceStatus>(SourceStatus.values());
    private final JComboBox<SourceRelevance> relevanceCombo = new JComboBox<SourceRelevance>(SourceRelevance.values());
    private final JComboBox<SourceReliability> reliabilityCombo =
            new JComboBox<SourceReliability>(SourceReliability.values());
    /** The user-relevant (⭐) toggle — the same reversible signal the HUD ⭐ sets, editable here for any source. */
    private final javax.swing.JCheckBox relevantCheck = new javax.swing.JCheckBox("Relevant (⭐)");
    private final JLabel status = new JLabel(" ");

    private String selectedId;
    private long loadedRevision;

    private final com.aresstack.comiccontrols.theme.ComicPalette palette =
            com.aresstack.comiccontrols.theme.ComicPalette.defaultPalette();

    public ResearchSourcesView(ResearchSourceRepository repository, Set<String> knownSectionIds) {
        super(new BorderLayout(6, 8));
        this.repository = repository;
        this.knownSectionIds = knownSectionIds;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel top = new JPanel(new BorderLayout(4, 0));
        top.setOpaque(false);
        // No "Filter:" label — the bar's magnifier + placeholder say it; Enter AND ▶ apply.
        top.add(filterField, BorderLayout.CENTER);
        filterField.addSearchAction(e -> reloadTable());

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        // Best first: the score column starts sorted descending, so promising parked hits surface.
        table.getRowSorter().setSortKeys(java.util.Collections.singletonList(
                new javax.swing.RowSorter.SortKey(SourcesTableModel.COLUMN_SCORE,
                        javax.swing.SortOrder.DESCENDING)));
        // The shared comic table dressing (flat header, thin lines, blue selection wash) — the
        // model, sorter, tooltips and column widths below stay exactly as before.
        com.aresstack.comiccontrols.control.ComicTableSupport.style(table, palette);
        configureColumns();
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onRowSelected();
            }
        });

        JScrollPane tableScroll = new com.aresstack.comiccontrols.control.ComicScrollPane(table,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        tableScroll.getViewport().setBackground(java.awt.Color.WHITE);
        // The overview sits on ONE quiet plate (like the State tab's sections) — search on top,
        // table below; no loud extra background panel.
        com.aresstack.comiccontrols.control.ComicSectionPanel tablePlate =
                new com.aresstack.comiccontrols.control.ComicSectionPanel(palette);
        tablePlate.setLayout(new BorderLayout(0, 6));
        tablePlate.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        tablePlate.add(top, BorderLayout.NORTH);
        tablePlate.add(tableScroll, BorderLayout.CENTER);

        add(tablePlate, BorderLayout.CENTER);
        add(buildDetail(), BorderLayout.SOUTH);
        reloadTable();
    }

    /** Few columns, each wide enough to READ: the star and score stay narrow, the title takes the rest. */
    private void configureColumns() {
        javax.swing.table.TableColumnModel columns = table.getColumnModel();
        columns.getColumn(SourcesTableModel.COLUMN_STAR).setMaxWidth(28);
        columns.getColumn(SourcesTableModel.COLUMN_TITLE).setPreferredWidth(240);
        columns.getColumn(SourcesTableModel.COLUMN_SITE).setPreferredWidth(110);
        columns.getColumn(SourcesTableModel.COLUMN_STATUS).setPreferredWidth(80);
        columns.getColumn(SourcesTableModel.COLUMN_SCORE).setPreferredWidth(56);
        columns.getColumn(SourcesTableModel.COLUMN_TEXT).setPreferredWidth(70);

        // The star speaks in the action accent — the user's own reversible signal, not the score's.
        DefaultTableCellRenderer starRenderer = new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable owner, Object value,
                    boolean selected, boolean focused, int row, int column) {
                java.awt.Component component = super.getTableCellRendererComponent(
                        owner, value, selected, focused, row, column);
                component.setForeground(palette.getAccentOrange());
                return component;
            }
        };
        starRenderer.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        columns.getColumn(SourcesTableModel.COLUMN_STAR).setCellRenderer(starRenderer);
        columns.getColumn(SourcesTableModel.COLUMN_SCORE).setCellRenderer(new DefaultTableCellRenderer() {
            {
                setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
            }

            @Override
            protected void setValue(Object value) {
                setText(value instanceof Double
                        ? String.format(java.util.Locale.ROOT, "%.2f", (Double) value) : "—");
            }
        });
        // Text state: read = quiet petrol (the agent did its work), parked = muted (still waiting).
        columns.getColumn(SourcesTableModel.COLUMN_TEXT).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable owner, Object value,
                    boolean selected, boolean focused, int row, int column) {
                java.awt.Component component = super.getTableCellRendererComponent(
                        owner, value, selected, focused, row, column);
                boolean read = String.valueOf(value).startsWith("✓");
                component.setForeground(read ? palette.getAgentPetrol()
                        : com.aresstack.comiccontrols.theme.ResearchUiPainter.mix(
                                palette.getInk(), java.awt.Color.WHITE, 0.45f));
                return component;
            }
        });

        statusCombo.setRenderer(germanEnumRenderer());
        relevanceCombo.setRenderer(germanEnumRenderer());
        reliabilityCombo.setRenderer(germanEnumRenderer());
    }

    /**
     * The detail area: the same quiet plates the State tab speaks in, replacing the old {@code
     * TitledBorder("Quelle")} form. Hierarchy top-down: identity (title/URL) with the metadata
     * (author, sections) on ONE plate; the RATING as one coherent block on its own plate (the
     * read-only pipeline score deliberately quieter than the user-editable values); then the three
     * text views; actions and feedback at the end. Sections GROUP — no per-field comic cards.
     */
    private JPanel buildDetail() {
        styleField(titleField);
        styleField(urlField);
        styleField(authorField);
        styleField(sectionsField);

        com.aresstack.comiccontrols.control.ComicSectionPanel identity = detailPlate();
        identity.setLayout(new GridBagLayout());
        int row = 0;
        addRow(identity, row++, "Titel", titleField);
        JPanel urlRow = new JPanel(new BorderLayout(6, 0));
        urlRow.setOpaque(false);
        urlRow.add(urlField, BorderLayout.CENTER);
        com.aresstack.comiccontrols.control.ComicButton open =
                new com.aresstack.comiccontrols.control.ComicButton("Öffnen");
        open.setToolTipText("URL im Browser öffnen");
        open.addActionListener(e -> openInBrowser());
        urlRow.add(open, BorderLayout.EAST);
        addRow(identity, row++, "URL", urlRow);
        addRow(identity, row++, "Autor", authorField);
        sectionsField.setToolTipText("Verknüpfte Gliederungs-Abschnitte, kommagetrennt; "
                + "(orphan) = Abschnitt existiert nicht mehr");
        addRow(identity, row++, "Abschnitte", sectionsField);

        // Status/Relevanz/Verlässlichkeit/Stern/Score belong together — ONE readable block.
        com.aresstack.comiccontrols.control.ComicSectionPanel ratingPlate = detailPlate();
        ratingPlate.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 2));
        ratingPlate.add(mutedLabel("Status "));
        ratingPlate.add(statusCombo);
        ratingPlate.add(javax.swing.Box.createHorizontalStrut(10));
        ratingPlate.add(mutedLabel("Relevanz "));
        ratingPlate.add(relevanceCombo);
        ratingPlate.add(javax.swing.Box.createHorizontalStrut(10));
        ratingPlate.add(mutedLabel("Verlässlichkeit "));
        ratingPlate.add(reliabilityCombo);
        ratingPlate.add(javax.swing.Box.createHorizontalStrut(10));
        relevantCheck.setOpaque(false);
        ratingPlate.add(relevantCheck);
        ratingPlate.add(javax.swing.Box.createHorizontalStrut(10));
        ratingPlate.add(mutedLabel("Score "));
        // The pipeline score is read-only and shows it: plain quiet text, no editable-looking box.
        scoreField.setColumns(6);
        scoreField.setOpaque(false);
        scoreField.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        scoreField.setForeground(mutedInk());
        ratingPlate.add(scoreField);

        // The long texts share ONE area as tabs instead of three stacked postage stamps. The
        // JTabbedPane stays (no comic tab control exists yet); it is only dressed to blend in.
        fullTextArea.setRows(8);
        styleReadOnlyArea(fullTextArea);
        styleReadOnlyArea(excerptArea);
        commentArea.setBackground(java.awt.Color.WHITE);
        JTabbedPane texts = new JTabbedPane();
        texts.setOpaque(false);
        texts.addTab("Volltext", quietScroll(fullTextArea));
        texts.addTab("Suchausschnitt", quietScroll(excerptArea));
        texts.addTab("Kommentar", quietScroll(commentArea));
        texts.setToolTipTextAt(0, "Der gelesene Seitentext (leer = geparkt, noch nicht gelesen)");
        texts.setToolTipTextAt(1, "Der Fundstellenkontext aus der Suche (nur lesbar)");
        texts.setToolTipTextAt(2, "Eigener Kommentar (editierbar)");

        com.aresstack.comiccontrols.control.ComicButton save =
                new com.aresstack.comiccontrols.control.ComicButton("Speichern");
        com.aresstack.comiccontrols.control.ComicButton reload =
                new com.aresstack.comiccontrols.control.ComicButton("Neu laden");
        com.aresstack.comiccontrols.control.ComicButton exclude =
                new com.aresstack.comiccontrols.control.ComicButton("Ausschließen",
                        com.aresstack.comiccontrols.control.ComicButton.Accent.CRITICAL);
        exclude.setToolTipText(
                "Setzt den Status auf Ausgeschlossen und speichert (kein Löschen, umkehrbar)");
        save.addActionListener(e -> save());
        reload.addActionListener(e -> reloadSelected());
        exclude.addActionListener(e -> {
            statusCombo.setSelectedItem(SourceStatus.EXCLUDED);
            save();
        });
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        actions.setOpaque(false);
        actions.add(save);
        actions.add(reload);
        actions.add(exclude);
        status.setFont(status.getFont().deriveFont(java.awt.Font.PLAIN, 11.5f));
        actions.add(javax.swing.Box.createHorizontalStrut(6));
        actions.add(status);

        JPanel plates = new JPanel();
        plates.setLayout(new javax.swing.BoxLayout(plates, javax.swing.BoxLayout.Y_AXIS));
        plates.setOpaque(false);
        identity.setAlignmentX(LEFT_ALIGNMENT);
        ratingPlate.setAlignmentX(LEFT_ALIGNMENT);
        plates.add(identity);
        plates.add(javax.swing.Box.createVerticalStrut(6));
        plates.add(ratingPlate);
        plates.add(javax.swing.Box.createVerticalStrut(6));

        JPanel detail = new JPanel(new BorderLayout(0, 4));
        detail.setOpaque(false);
        detail.setPreferredSize(new java.awt.Dimension(10, 360));
        detail.add(plates, BorderLayout.NORTH);
        detail.add(texts, BorderLayout.CENTER);
        detail.add(actions, BorderLayout.SOUTH);
        return detail;
    }

    /** One labelled form row: narrow right-aligned muted label, field takes the width. */
    private void addRow(JPanel form, int row, String label, java.awt.Component field) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row;
        l.anchor = GridBagConstraints.EAST;
        l.insets = new java.awt.Insets(2, 0, 2, 8);
        form.add(mutedLabel(label), l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = row;
        f.weightx = 1.0;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.insets = new java.awt.Insets(2, 0, 2, 0);
        form.add(field, f);
    }

    // ------------------------------------------------------------------ quiet detail dressing

    /** A calm white plate for one detail group — grouping only, never per-field cards. */
    private com.aresstack.comiccontrols.control.ComicSectionPanel detailPlate() {
        com.aresstack.comiccontrols.control.ComicSectionPanel plate =
                new com.aresstack.comiccontrols.control.ComicSectionPanel(palette);
        plate.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 10));
        return plate;
    }

    private JLabel mutedLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(java.awt.Font.PLAIN, 11.5f));
        label.setForeground(mutedInk());
        return label;
    }

    private java.awt.Color mutedInk() {
        return com.aresstack.comiccontrols.theme.ResearchUiPainter.mix(
                palette.getInk(), java.awt.Color.WHITE, 0.35f);
    }

    /** Editable fields: a thin derived line + breathing room instead of the LaF bevel. */
    private void styleField(JTextField field) {
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                        com.aresstack.comiccontrols.theme.ResearchUiPainter.mix(
                                palette.getInk(), java.awt.Color.WHITE, 0.75f)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
    }

    /** Read-only text sits on the quiet neutral surface — visibly calmer than editable areas. */
    private void styleReadOnlyArea(JTextArea area) {
        area.setBackground(palette.getSurface());
        area.setForeground(palette.getInk());
        area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
    }

    private JScrollPane quietScroll(java.awt.Component view) {
        JScrollPane scroll = new com.aresstack.comiccontrols.control.ComicScrollPane(view,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        return scroll;
    }

    /** Calm feedback (loaded/saved) — errors and conflicts use {@link #showProblem} instead. */
    private void showQuiet(String text) {
        status.setForeground(mutedInk());
        status.setText(text);
    }

    /** The State tab's problem red for everything that went wrong (conflicts, failures). */
    private void showProblem(String text) {
        status.setForeground(palette.getAccentRed());
        status.setText(text);
    }

    private void openInBrowser() {
        String url = urlField.getText().trim();
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            showProblem("Keine öffenbare URL.");
            return;
        }
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception cannotOpen) {
            showProblem("Konnte die URL nicht öffnen: " + cannotOpen.getMessage());
        }
    }

    /**
     * Re-read the table from the repository — e.g. after a research run accepted new sources. Keeps the
     * current selection (and thereby any in-progress detail edit) when that record still exists.
     */
    public void refresh() {
        String keep = selectedId;
        List<ResearchSourceRecord> rows = repository.find(new SourceQuery(filterField.getText(), null));
        tableModel.setRows(rows);
        int keepRow = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getSourceId().equals(keep)) {
                keepRow = i;
                break;
            }
        }
        if (keepRow >= 0) {
            selectModelRow(keepRow);
        } else if (!rows.isEmpty()) {
            selectModelRow(0);
        } else {
            clearDetail();
        }
    }

    private void reloadTable() {
        List<ResearchSourceRecord> rows = repository.find(new SourceQuery(filterField.getText(), null));
        tableModel.setRows(rows);
        if (!rows.isEmpty()) {
            selectModelRow(0);
        } else {
            clearDetail();
        }
    }

    /** Selection is a VIEW concern: with the sorter active, model row i is not view row i. */
    private void selectModelRow(int modelRow) {
        int viewRow = table.convertRowIndexToView(modelRow);
        if (viewRow >= 0) {
            table.setRowSelectionInterval(viewRow, viewRow);
        }
    }

    private void onRowSelected() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0 || viewRow >= tableModel.getRowCount()) {
            return;
        }
        loadDetail(tableModel.rowAt(table.convertRowIndexToModel(viewRow)));
    }

    private void reloadSelected() {
        if (selectedId != null) {
            ResearchSourceRecord fresh = repository.get(selectedId);
            if (fresh != null) {
                loadDetail(fresh);
            }
        }
    }

    private void loadDetail(ResearchSourceRecord record) {
        selectedId = record.getSourceId();
        loadedRevision = record.getRevision();
        titleField.setText(record.getTitle());
        urlField.setText(record.getUrl().isEmpty() ? record.getOrigin() : record.getUrl());
        authorField.setText(record.getAuthor());
        sectionsField.setText(joinSections(record.getLinkedSectionIds()));
        commentArea.setText(record.getComment());
        scoreField.setText(record.hasRerankScore()
                ? String.format(java.util.Locale.ROOT, "%.4f", record.getRerankScore()) : "—");
        excerptArea.setText(record.getExcerpt());
        excerptArea.setCaretPosition(0);
        fullTextArea.setText(record.isParked()
                ? "(geparkt — die Seite wurde noch nicht gelesen)" : record.getFullText());
        fullTextArea.setCaretPosition(0);
        statusCombo.setSelectedItem(record.getStatus());
        relevanceCombo.setSelectedItem(record.getRelevance());
        reliabilityCombo.setSelectedItem(record.getReliability());
        relevantCheck.setSelected(record.isUserRelevant());
        showQuiet("Geladen: " + record.getSourceId() + " (Rev " + loadedRevision + ")."
                + orphanNote(record.getLinkedSectionIds()));
    }

    private void clearDetail() {
        selectedId = null;
        loadedRevision = 0;
        titleField.setText("");
        urlField.setText("");
        authorField.setText("");
        sectionsField.setText("");
        commentArea.setText("");
        scoreField.setText("");
        excerptArea.setText("");
        fullTextArea.setText("");
        relevantCheck.setSelected(false);
        showQuiet(" ");
    }

    private void save() {
        if (selectedId == null) {
            return;
        }
        ResearchSourceRecord current = repository.get(selectedId);
        if (current == null) {
            showProblem("Die Quelle existiert nicht mehr.");
            reloadTable();
            return;
        }
        SourceUpdate update = SourceUpdate.from(current)
                .title(titleField.getText())
                .url(urlField.getText())
                .author(authorField.getText())
                .comment(commentArea.getText())
                .linkedSectionIds(parseSections(sectionsField.getText()))
                .status((SourceStatus) statusCombo.getSelectedItem())
                .relevance((SourceRelevance) relevanceCombo.getSelectedItem())
                .reliability((SourceReliability) reliabilityCombo.getSelectedItem())
                .userRelevant(relevantCheck.isSelected())
                .build();
        SourceUpdateResult result = repository.update(selectedId, loadedRevision, update);
        switch (result.getStatus()) {
            case UPDATED:
                loadedRevision = result.getRecord().getRevision();
                showQuiet("Gespeichert (Rev " + loadedRevision + ").");
                reloadTable();
                selectById(selectedId);
                break;
            case CONFLICT:
                showProblem("Nicht gespeichert: " + result.getReason() + " Neu geladen: Rev "
                        + result.getRecord().getRevision() + ".");
                loadDetail(result.getRecord());
                reloadTable();
                break;
            case NOT_FOUND:
            default:
                showProblem("Nicht gespeichert: " + result.getReason());
                reloadTable();
                break;
        }
    }

    private void selectById(String sourceId) {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (tableModel.rowAt(i).getSourceId().equals(sourceId)) {
                selectModelRow(i);
                return;
            }
        }
    }

    private String joinSections(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ids.get(i));
            if (knownSectionIds != null && !knownSectionIds.contains(ids.get(i))) {
                sb.append("(orphan)");
            }
        }
        return sb.toString();
    }

    private String orphanNote(List<String> ids) {
        if (knownSectionIds == null) {
            return "";
        }
        for (String id : ids) {
            if (!knownSectionIds.contains(id)) {
                return "  Einige Abschnitts-Verknüpfungen sind verwaist.";
            }
        }
        return "";
    }

    private static List<String> parseSections(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        for (String part : text.split(",")) {
            String id = part.replace("(orphan)", "").trim();
            if (!id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static JTextField readOnlyField() {
        JTextField f = new JTextField();
        f.setEditable(false);
        return f;
    }

    private static JTextArea readOnlyArea(int rows) {
        JTextArea a = new JTextArea(rows, 20);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        return a;
    }

    // ------------------------------------------------------------------ German labels (no raw enum names)

    /** The user never reads raw enum names — every bounded value has a German label. */
    static String germanLabel(Object value) {
        if (value instanceof SourceStatus) {
            switch ((SourceStatus) value) {
                case PARKED: return "Geparkt";
                case NEW: return "Neu";
                case REVIEWED: return "Ausgewertet";
                case ACCEPTED: return "Übernommen";
                case EXCLUDED: return "Ausgeschlossen";
                case DUPLICATE: return "Duplikat";
                case SUPERSEDED: return "Ersetzt";
                default: break;
            }
        }
        if (value instanceof SourceRelevance) {
            switch ((SourceRelevance) value) {
                case UNKNOWN: return "Unbewertet";
                case LOW: return "Niedrig";
                case MEDIUM: return "Mittel";
                case HIGH: return "Hoch";
                default: break;
            }
        }
        if (value instanceof SourceReliability) {
            switch ((SourceReliability) value) {
                case UNKNOWN: return "Unbewertet";
                case LOW: return "Niedrig";
                case MEDIUM: return "Mittel";
                case HIGH: return "Hoch";
                case PRIMARY_SOURCE: return "Primärquelle";
                default: break;
            }
        }
        return String.valueOf(value);
    }

    private static javax.swing.ListCellRenderer<Object> germanEnumRenderer() {
        return new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(germanLabel(value));
                return this;
            }
        };
    }

    /** A row's display title: the title, else the URL — never an empty main column. */
    static String displayTitle(ResearchSourceRecord record) {
        if (!record.getTitle().trim().isEmpty()) {
            return record.getTitle();
        }
        return record.getUrl().isEmpty() ? record.getOrigin() : record.getUrl();
    }

    /** The bare website (host) of a record — "de.wikipedia.org", not a full URL. */
    static String siteOf(ResearchSourceRecord record) {
        String url = record.getUrl().isEmpty() ? record.getOrigin() : record.getUrl();
        int schemeEnd = url.indexOf("://");
        String rest = schemeEnd >= 0 ? url.substring(schemeEnd + 3) : url;
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // Visible for tests.
    int rowCount() {
        return tableModel.getRowCount();
    }

    // Visible for tests.
    Object cellAt(int row, int col) {
        return tableModel.getValueAt(row, col);
    }

    private static final class SourcesTableModel extends AbstractTableModel {
        static final int COLUMN_STAR = 0;
        static final int COLUMN_TITLE = 1;
        static final int COLUMN_SITE = 2;
        static final int COLUMN_STATUS = 3;
        static final int COLUMN_SCORE = 4;
        static final int COLUMN_TEXT = 5;

        private final String[] columns = {"⭐", "Titel", "Website", "Status", "Score", "Text"};
        private List<ResearchSourceRecord> rows = new ArrayList<ResearchSourceRecord>();

        void setRows(List<ResearchSourceRecord> rows) {
            this.rows = new ArrayList<ResearchSourceRecord>(rows);
            fireTableDataChanged();
        }

        ResearchSourceRecord rowAt(int row) {
            return rows.get(row);
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return columns.length;
        }

        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            // A typed score column sorts numerically under the row sorter (a String "0.07" would not).
            return columnIndex == COLUMN_SCORE ? Double.class : String.class;
        }

        public Object getValueAt(int rowIndex, int columnIndex) {
            ResearchSourceRecord r = rows.get(rowIndex);
            switch (columnIndex) {
                case COLUMN_STAR: return r.isUserRelevant() ? "★" : "";
                case COLUMN_TITLE: return displayTitle(r);
                case COLUMN_SITE: return siteOf(r);
                case COLUMN_STATUS: return germanLabel(r.getStatus());
                // Score makes gaps visible: a high-scored source with no full text is a promising hit still
                // waiting to be read (parked). null (rendered "—") when no reranker score exists.
                case COLUMN_SCORE: return r.hasRerankScore() ? Double.valueOf(r.getRerankScore()) : null;
                case COLUMN_TEXT: return r.isParked() ? "geparkt" : "✓ gelesen";
                default: return "";
            }
        }
    }

    /** Default known outline sections used for orphan detection in the clickdummy. */
    public static Set<String> demoKnownSections() {
        return new java.util.LinkedHashSet<String>(Arrays.asList("s1", "s2", "s2a", "s3", "s4"));
    }
}
