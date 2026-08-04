package com.aresstack.askai.research.sources;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Structured, writing sources view for the shared artifact area. All persistence goes through
 * {@link ResearchSourceRepository} with optimistic locking — never through the {@code TableModel} directly. A
 * conflict reloads the current record and keeps the user informed instead of silently overwriting. Section
 * links that are no longer in the outline are shown as orphans, never auto-removed.
 */
public final class ResearchSourcesView extends JPanel {

    private final ResearchSourceRepository repository;
    private final Set<String> knownSectionIds;

    private final SourcesTableModel tableModel = new SourcesTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextField filterField = new JTextField();

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
        top.add(new JLabel("Filter:"), BorderLayout.WEST);
        top.add(filterField, BorderLayout.CENTER);
        filterField.addActionListener(e -> reloadTable());

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
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

    private JPanel buildDetail() {
        JPanel form = new JPanel(new GridLayout(0, 2, 4, 2));
        form.add(new JLabel("Title"));
        form.add(titleField);
        form.add(new JLabel("URL / origin"));
        form.add(urlField);
        form.add(new JLabel("Author"));
        form.add(authorField);
        form.add(new JLabel("Linked sections (comma-separated)"));
        form.add(sectionsField);
        form.add(new JLabel("Status"));
        form.add(statusCombo);
        form.add(new JLabel("Relevance"));
        form.add(relevanceCombo);
        form.add(new JLabel("Reliability"));
        form.add(reliabilityCombo);
        form.add(new JLabel("User-relevant (⭐)"));
        form.add(relevantCheck);
        form.add(new JLabel("Rerank score"));
        form.add(scoreField);
        form.add(new JLabel("Search excerpt"));
        form.add(new JScrollPane(excerptArea));
        form.add(new JLabel("Full text (empty = parked)"));
        form.add(new JScrollPane(fullTextArea));
        form.add(new JLabel("Comment"));
        form.add(new JScrollPane(commentArea));

        JButton save = new JButton("Save");
        JButton reload = new JButton("Reload");
        JButton exclude = new JButton("Exclude");
        save.addActionListener(e -> save());
        reload.addActionListener(e -> reloadSelected());
        exclude.addActionListener(e -> {
            statusCombo.setSelectedItem(SourceStatus.EXCLUDED);
            save();
        });
        JPanel actions = new JPanel();
        actions.add(save);
        actions.add(reload);
        actions.add(exclude);
        actions.add(status);

        JPanel detail = new JPanel(new BorderLayout());
        detail.setBorder(BorderFactory.createTitledBorder("Source detail"));
        // The detail grew (score, excerpt, full text): make it vertically scrollable so every field is
        // reachable instead of being clipped at the bottom of the panel.
        JScrollPane formScroll = new JScrollPane(form,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        formScroll.getVerticalScrollBar().setUnitIncrement(16);
        formScroll.setPreferredSize(new java.awt.Dimension(10, 260));
        detail.add(formScroll, BorderLayout.CENTER);
        detail.add(actions, BorderLayout.SOUTH);
        return detail;
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
            table.setRowSelectionInterval(keepRow, keepRow);
        } else if (!rows.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        } else {
            clearDetail();
        }
    }

    private void reloadTable() {
        List<ResearchSourceRecord> rows = repository.find(new SourceQuery(filterField.getText(), null));
        tableModel.setRows(rows);
        if (!rows.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        } else {
            clearDetail();
        }
    }

    private void onRowSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= tableModel.getRowCount()) {
            return;
        }
        loadDetail(tableModel.rowAt(row));
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
        fullTextArea.setText(record.getFullText());
        fullTextArea.setCaretPosition(0);
        statusCombo.setSelectedItem(record.getStatus());
        relevanceCombo.setSelectedItem(record.getRelevance());
        reliabilityCombo.setSelectedItem(record.getReliability());
        relevantCheck.setSelected(record.isUserRelevant());
        status.setText("Loaded " + record.getSourceId() + " (rev " + loadedRevision + ")."
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
            status.setText("Source no longer exists.");
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
                status.setText("Saved (rev " + loadedRevision + ").");
                reloadTable();
                selectById(selectedId);
                break;
            case CONFLICT:
                status.setText("Not saved: " + result.getReason() + " Reloaded rev "
                        + result.getRecord().getRevision() + ".");
                loadDetail(result.getRecord());
                reloadTable();
                break;
            case NOT_FOUND:
            default:
                status.setText("Not saved: " + result.getReason());
                reloadTable();
                break;
        }
    }

    private void selectById(String sourceId) {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (tableModel.rowAt(i).getSourceId().equals(sourceId)) {
                table.setRowSelectionInterval(i, i);
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
                return "  Some links are orphaned.";
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

    // Visible for tests.
    int rowCount() {
        return tableModel.getRowCount();
    }

    // Visible for tests.
    Object cellAt(int row, int col) {
        return tableModel.getValueAt(row, col);
    }

    private static final class SourcesTableModel extends AbstractTableModel {
        private final String[] columns = {"⭐", "Title", "Origin", "Type", "Status", "Score", "Full text",
                "Reliability", "Relevance", "Linked sections", "Revision"};
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

        public Object getValueAt(int rowIndex, int columnIndex) {
            ResearchSourceRecord r = rows.get(rowIndex);
            switch (columnIndex) {
                case 0: return r.isUserRelevant() ? "★" : "";
                case 1: return r.getTitle();
                case 2: return r.getOrigin();
                case 3: return r.getSourceType();
                case 4: return r.getStatus();
                // Score makes gaps visible: a high-scored source with no full text is a promising hit still
                // waiting to be read (parked). "—" when the source carries no reranker score.
                case 5: return r.hasRerankScore() ? String.format(java.util.Locale.ROOT, "%.2f",
                        r.getRerankScore()) : "—";
                case 6: return r.isParked() ? "parked" : "✓";
                case 7: return r.getReliability();
                case 8: return r.getRelevance();
                case 9: return r.getLinkedSectionIds();
                case 10: return r.getRevision();
                default: return "";
            }
        }
    }

    /** Default known outline sections used for orphan detection in the clickdummy. */
    public static Set<String> demoKnownSections() {
        return new java.util.LinkedHashSet<String>(Arrays.asList("s1", "s2", "s2a", "s3", "s4"));
    }
}
