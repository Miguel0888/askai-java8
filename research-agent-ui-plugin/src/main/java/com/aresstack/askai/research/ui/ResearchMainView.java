package com.aresstack.askai.research.ui;

import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.research.domain.ResearchFinding;
import com.aresstack.askai.research.domain.ResearchProblem;
import com.aresstack.askai.research.domain.ResearchSource;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.List;

/** The centre tabs: Document (host Markdown/Mermaid), Sources, Findings, Diff and Problems. */
final class ResearchMainView extends JTabbedPane {

    private final ResearchWorkspaceController controller;
    private final MarkdownView markdownView;
    private final JLabel revisionLabel = new JLabel();
    private final DefaultTableModel sourcesModel = readOnlyModel(
            new String[] {"Title", "Origin", "Type", "Captured", "Linked sections", "Status"});
    private final DefaultTableModel findingsModel = readOnlyModel(
            new String[] {"Statement", "Sources", "Sections", "Confidence", "Contradiction", "Status"});
    private final DefaultTableModel problemsModel = readOnlyModel(
            new String[] {"Kind", "Message", "Section"});
    private final JTextArea diffBefore = new JTextArea();
    private final JTextArea diffAfter = new JTextArea();

    ResearchMainView(ResearchWorkspaceController controller, MarkdownView markdownView) {
        this.controller = controller;
        this.markdownView = markdownView;

        JPanel document = new JPanel(new BorderLayout(0, 4));
        document.add(revisionLabel, BorderLayout.NORTH);
        document.add(new JScrollPane(markdownView.getComponent()), BorderLayout.CENTER);
        addTab("Document", document);
        addTab("Sources", new JScrollPane(new JTable(sourcesModel)));
        addTab("Findings", new JScrollPane(new JTable(findingsModel)));
        addTab("Diff", buildDiff());
        addTab("Problems", new JScrollPane(new JTable(problemsModel)));

        refresh();
    }

    private JPanel buildDiff() {
        diffBefore.setEditable(false);
        diffAfter.setEditable(false);
        JPanel panel = new JPanel(new GridLayout(1, 2, 6, 0));
        panel.add(wrap("Previous revision", diffBefore));
        panel.add(wrap("New revision", diffAfter));
        return panel;
    }

    private static JPanel wrap(String title, JTextArea area) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(new JLabel(title), BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        return panel;
    }

    void refresh() {
        markdownView.setMarkdown(controller.documentMarkdown());
        String scope = controller.getActiveSectionId().isEmpty() ? "whole document"
                : "section " + controller.getActiveSectionId();
        revisionLabel.setText("Revision " + controller.documentRevision() + "  ·  " + scope);

        sourcesModel.setRowCount(0);
        for (ResearchSource s : controller.sourcesForActiveSection()) {
            sourcesModel.addRow(new Object[] {s.getTitle(), s.getOrigin(), s.getSourceType(),
                    String.valueOf(s.getCapturedAt()), s.getLinkedSectionIds().toString(), s.getStatus()});
        }
        findingsModel.setRowCount(0);
        for (ResearchFinding f : controller.findingsForActiveSection()) {
            findingsModel.addRow(new Object[] {f.getStatement(), f.getLinkedSourceIds().toString(),
                    f.getLinkedSectionIds().toString(), String.valueOf(f.getConfidence()),
                    f.isContradiction() ? "Yes" : "No", f.getStatus()});
        }
        problemsModel.setRowCount(0);
        for (ResearchProblem p : controller.getProblems()) {
            problemsModel.addRow(new Object[] {String.valueOf(p.getKind()), p.getMessage(), p.getSectionId()});
        }
        diffBefore.setText("(previous revision preview)\n");
        diffAfter.setText(controller.documentMarkdown());
    }

    private static DefaultTableModel readOnlyModel(String[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    // Visible for tests: current row counts of the section-filtered tables.
    int sourceRowCount() {
        return sourcesModel.getRowCount();
    }

    int findingRowCount() {
        return findingsModel.getRowCount();
    }
}
