package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;
import com.aresstack.askai.research.backend.ScopingAssistantUpdate;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * The scoping workspace support panel (RA-P6 §2): the exploration map (rendered by the HOST Markdown/Mermaid
 * view — no new renderer, no askai-app dependency) plus the search suggestions and a LOCAL, user-owned query
 * draft. It shows the CURRENT projection (a later turn replaces it, never accumulates), protects a manual
 * query edit from being overwritten, and runs NO search and moves NO workflow. Advice is intentionally not
 * shown. All methods run on the EDT.
 */
public final class ScopingSupportView extends JPanel {

    private static final String MAP_CAPTION =
            "Explorationskarte – dient der Orientierung und ist keine bestätigte Evidenz.";

    private final MarkdownView mapView;
    private final JPanel mapSection;
    private final JLabel suggestionsEmpty = new JLabel("Noch keine Suchvorschläge.");
    private final JPanel suggestionsList = new JPanel();
    private final JTextField queryField = new JTextField();
    private final ScopingQueryDraft draft = new ScopingQueryDraft();

    private boolean programmaticQueryEdit;

    public ScopingSupportView(MarkdownViewFactory markdownViewFactory) {
        super(new BorderLayout());
        this.mapView = markdownViewFactory.create(
                MarkdownViewOptions.builder().renderMermaid(true).selectable(true).build());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        content.add(sectionTitle("Exploration"));
        content.add(caption(MAP_CAPTION));
        mapSection = new JPanel(new BorderLayout());
        mapSection.add(mapView.getComponent(), BorderLayout.CENTER);
        content.add(mapSection);

        content.add(spacer());
        content.add(sectionTitle("Vorgeschlagene Suchanfragen"));
        suggestionsList.setLayout(new BoxLayout(suggestionsList, BoxLayout.Y_AXIS));
        suggestionsList.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(suggestionsList);
        content.add(suggestionsEmpty);

        content.add(spacer());
        content.add(sectionTitle("Search query"));
        queryField.setAlignmentX(Component.LEFT_ALIGNMENT);
        queryField.setMaximumSize(new Dimension(Integer.MAX_VALUE, queryField.getPreferredSize().height));
        queryField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                onUserEdit();
            }

            public void removeUpdate(DocumentEvent e) {
                onUserEdit();
            }

            public void changedUpdate(DocumentEvent e) {
                onUserEdit();
            }
        });
        content.add(queryField);

        add(content, BorderLayout.NORTH);
        renderSuggestions(java.util.Collections.<ScopingAssistantUpdate.Suggestion>emptyList());
    }

    /** Apply the latest projection: refresh the map + suggestions, prefill the query only if the user hasn't. */
    public void apply(ScopingAssistantUpdate projection) {
        if (projection == null) {
            return;
        }
        applyMap(projection);
        renderSuggestions(projection.getSearchSuggestions());
        String best = projection.getSearchSuggestions().isEmpty()
                ? "" : projection.getSearchSuggestions().get(0).getQuery();
        if (draft.adoptFromProjectionIfUnowned(best)) {
            setQueryTextProgrammatically(draft.text());
        }
    }

    private void applyMap(ScopingAssistantUpdate projection) {
        if (!projection.hasExplorationMap()) {
            mapView.setMarkdown("");
            mapSection.setVisible(false);
            return;
        }
        mapSection.setVisible(true);
        String fenced = "```mermaid\n" + projection.getExplorationMapMermaid() + "\n```";
        try {
            mapView.setMarkdown(fenced);
        } catch (RuntimeException renderingFailed) {
            // A broken diagram is a PRESENTATION problem only: the turn, suggestions and query stay usable.
            mapView.setMarkdown("_Die Explorationskarte konnte nicht dargestellt werden._");
        }
    }

    private void renderSuggestions(java.util.List<ScopingAssistantUpdate.Suggestion> suggestions) {
        suggestionsList.removeAll(); // REPLACE, never accumulate old suggestion cards
        suggestionsEmpty.setVisible(suggestions.isEmpty());
        for (final ScopingAssistantUpdate.Suggestion suggestion : suggestions) {
            suggestionsList.add(suggestionRow(suggestion));
        }
        suggestionsList.revalidate();
        suggestionsList.repaint();
    }

    private JComponent suggestionRow(final ScopingAssistantUpdate.Suggestion suggestion) {
        JPanel row = new JPanel(new BorderLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        JButton queryButton = new JButton(suggestion.getQuery());
        queryButton.setHorizontalAlignment(JButton.LEFT);
        queryButton.setToolTipText("Suchanfrage in das Feld übernehmen");
        queryButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                // LOCAL only: adopt the query into the user's field. No search runs and no phase changes.
                draft.chooseSuggestion(suggestion.getQuery());
                setQueryTextProgrammatically(suggestion.getQuery());
            }
        });
        row.add(queryButton, BorderLayout.CENTER);
        if (!suggestion.getPurpose().isEmpty()) {
            JLabel purpose = caption(suggestion.getPurpose());
            row.add(purpose, BorderLayout.SOUTH);
        }
        return row;
    }

    private void onUserEdit() {
        if (programmaticQueryEdit) {
            return; // our own setText, not a user edit
        }
        draft.userTyped(queryField.getText());
    }

    private void setQueryTextProgrammatically(String text) {
        programmaticQueryEdit = true;
        try {
            queryField.setText(text);
        } finally {
            programmaticQueryEdit = false;
        }
    }

    public void dispose() {
        mapView.dispose();
    }

    // ------------------------------------------------------------------ small UI helpers

    private static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static JLabel caption(String text) {
        JLabel label = new JLabel("<html>" + escapeHtml(text) + "</html>");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() - 1f));
        return label;
    }

    private static Component spacer() {
        return javax.swing.Box.createVerticalStrut(10);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
