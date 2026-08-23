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

    public ResearchSourcesView(ResearchSourceRepository repository, Set<String> knownSectionIds) {
        super(new BorderLayout(6, 6));
        this.repository = repository;
        this.knownSectionIds = knownSectionIds;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel top = new JPanel(new BorderLayout(4, 0));
        // No "Filter:" label — the bar's magnifier + placeholder say it; Enter AND ▶ apply.
        top.add(filterField, BorderLayout.CENTER);
        filterField.addSearchAction(e -> reloadTable());

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(table.getRowHeight() + 4);
        table.setAutoCreateRowSorter(true);
        // Best first: the score column starts sorted descending, so promising parked hits surface.
        table.getRowSorter().setSortKeys(java.util.Collections.singletonList(
                new javax.swing.RowSorter.SortKey(SourcesTableModel.COLUMN_SCORE,
                        javax.swing.SortOrder.DESCENDING)));
        configureColumns();
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onRowSelected();
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.add(top, BorderLayout.NORTH);
        left.add(tableScroll, BorderLayout.CENTER);

        add(left, BorderLayout.CENTER);
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

        DefaultTableCellRenderer centered = new DefaultTableCellRenderer();
        centered.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        columns.getColumn(SourcesTableModel.COLUMN_STAR).setCellRenderer(centered);
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

        statusCombo.setRenderer(germanEnumRenderer());
        relevanceCombo.setRenderer(germanEnumRenderer());
        reliabilityCombo.setRenderer(germanEnumRenderer());
    }

    private JPanel buildDetail() {
        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addRow(form, row++, "Titel:", titleField);

        JPanel urlRow = new JPanel(new BorderLayout(4, 0));
        urlRow.add(urlField, BorderLayout.CENTER);
        JButton open = new JButton("Öffnen");
        open.setToolTipText("URL im Browser öffnen");
        open.addActionListener(e -> openInBrowser());
        urlRow.add(open, BorderLayout.EAST);
        addRow(form, row++, "URL:", urlRow);

        addRow(form, row++, "Autor:", authorField);

        JPanel rating = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        rating.add(new JLabel("Status "));
        rating.add(statusCombo);
        rating.add(javax.swing.Box.createHorizontalStrut(8));
        rating.add(new JLabel("Relevanz "));
        rating.add(relevanceCombo);
        rating.add(javax.swing.Box.createHorizontalStrut(8));
        rating.add(new JLabel("Verlässlichkeit "));
        rating.add(reliabilityCombo);
        rating.add(javax.swing.Box.createHorizontalStrut(8));
        rating.add(relevantCheck);
        rating.add(javax.swing.Box.createHorizontalStrut(8));
        rating.add(new JLabel("Score "));
        scoreField.setColumns(6);
        rating.add(scoreField);
        addRow(form, row++, "Bewertung:", rating);

        sectionsField.setToolTipText("Verknüpfte Gliederungs-Abschnitte, kommagetrennt; "
                + "(orphan) = Abschnitt existiert nicht mehr");
        addRow(form, row++, "Abschnitte:", sectionsField);

        // The long texts share ONE area as tabs instead of three stacked postage stamps.
        fullTextArea.setRows(8);
        JTabbedPane texts = new JTabbedPane();
        texts.addTab("Volltext", new JScrollPane(fullTextArea));
        texts.addTab("Suchausschnitt", new JScrollPane(excerptArea));
        texts.addTab("Kommentar", new JScrollPane(commentArea));
        texts.setToolTipTextAt(0, "Der gelesene Seitentext (leer = geparkt, noch nicht gelesen)");
        GridBagConstraints tabs = new GridBagConstraints();
        tabs.gridx = 0;
        tabs.gridy = row;
        tabs.gridwidth = 2;
        tabs.weightx = 1.0;
        tabs.weighty = 1.0;
        tabs.fill = GridBagConstraints.BOTH;
        tabs.insets = new java.awt.Insets(4, 0, 0, 0);
        form.add(texts, tabs);

        JButton save = new JButton("Speichern");
        JButton reload = new JButton("Neu laden");
        JButton exclude = new JButton("Ausschließen");
        exclude.setToolTipText("Setzt den Status auf Ausgeschlossen und speichert (kein Löschen)");
        save.addActionListener(e -> save());
        reload.addActionListener(e -> reloadSelected());
        exclude.addActionListener(e -> {
            statusCombo.setSelectedItem(SourceStatus.EXCLUDED);
            save();
        });
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        actions.add(save);
        actions.add(reload);
        actions.add(exclude);
        actions.add(status);

        JPanel detail = new JPanel(new BorderLayout());
        detail.setBorder(BorderFactory.createTitledBorder("Quelle"));
        detail.setPreferredSize(new java.awt.Dimension(10, 320));
        detail.add(form, BorderLayout.CENTER);
        detail.add(actions, BorderLayout.SOUTH);
        return detail;
    }

    /** One labelled form row: narrow right-aligned label, field takes the width. */
    private static void addRow(JPanel form, int row, String label, java.awt.Component field) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row;
        l.anchor = GridBagConstraints.EAST;
        l.insets = new java.awt.Insets(2, 0, 2, 6);
        JLabel jLabel = new JLabel(label);
        form.add(jLabel, l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = row;
        f.weightx = 1.0;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.insets = new java.awt.Insets(2, 0, 2, 0);
        form.add(field, f);
    }

    private void openInBrowser() {
        String url = urlField.getText().trim();
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            status.setText("Keine öffenbare URL.");
            return;
        }
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception cannotOpen) {
            status.setText("Konnte die URL nicht öffnen: " + cannotOpen.getMessage());
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
        status.setText("Geladen: " + record.getSourceId() + " (Rev " + loadedRevision + ")."
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
        status.setText(" ");
    }

    private void save() {
        if (selectedId == null) {
            return;
        }
        ResearchSourceRecord current = repository.get(selectedId);
        if (current == null) {
            status.setText("Die Quelle existiert nicht mehr.");
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
                status.setText("Gespeichert (Rev " + loadedRevision + ").");
                reloadTable();
                selectById(selectedId);
                break;
            case CONFLICT:
                status.setText("Nicht gespeichert: " + result.getReason() + " Neu geladen: Rev "
                        + result.getRecord().getRevision() + ".");
                loadDetail(result.getRecord());
                reloadTable();
                break;
            case NOT_FOUND:
            default:
                status.setText("Nicht gespeichert: " + result.getReason());
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
