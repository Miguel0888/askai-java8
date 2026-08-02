package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.backend.ScopingAssistantUpdate;

import javax.swing.BorderFactory;
import javax.swing.Box;
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
 * The scoping composer accessory: ACTIONS of the active phase only — the search suggestions and a LOCAL,
 * user-owned query draft. The research brief is NOT here (it is the phase artifact, shown in its own
 * "Fragestellung" tab) and there is no visualization (a separate visualizer owns that). Compact so the chat
 * stays dominant: one line per suggestion (purpose as tooltip), a query field prefilled only while untouched.
 * Runs NO search and moves NO workflow. All methods run on the EDT.
 */
public final class ScopingSupportView extends JPanel {

    private final JLabel suggestionsEmpty = caption("Noch keine Suchvorschläge.");
    private final JPanel suggestionsList = new JPanel();
    private final JTextField queryField = new JTextField();
    private final ScopingQueryDraft draft = new ScopingQueryDraft();

    private boolean programmaticQueryEdit;

    public ScopingSupportView() {
        super(new BorderLayout());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        content.add(sectionTitle("Suchvorschläge"));
        suggestionsList.setLayout(new BoxLayout(suggestionsList, BoxLayout.Y_AXIS));
        suggestionsList.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(suggestionsList);
        content.add(suggestionsEmpty);

        content.add(Box.createVerticalStrut(6));
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

    /** Apply the latest projection: refresh suggestions and prefill the query only if the user hasn't. */
    public void apply(ScopingAssistantUpdate projection) {
        if (projection == null) {
            return;
        }
        renderSuggestions(projection.getSearchSuggestions());
        String best = projection.getSearchSuggestions().isEmpty()
                ? "" : projection.getSearchSuggestions().get(0).getQuery();
        if (draft.adoptFromProjectionIfUnowned(best)) {
            setQueryTextProgrammatically(draft.text());
        }
    }

    /** The current, user-owned query (what a later "search" action will run — the edited text, not the AI's). */
    public String currentQuery() {
        return queryField.getText();
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

    /** One compact, single-line clickable query; the purpose is a tooltip, not a second line. */
    private JComponent suggestionRow(final ScopingAssistantUpdate.Suggestion suggestion) {
        JButton queryButton = new JButton("▸ " + suggestion.getQuery());
        queryButton.setHorizontalAlignment(JButton.LEFT);
        queryButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        queryButton.setMargin(new java.awt.Insets(1, 4, 1, 4));
        queryButton.setToolTipText(suggestion.getPurpose().isEmpty()
                ? "Suchanfrage in das Feld übernehmen" : suggestion.getPurpose());
        queryButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, queryButton.getPreferredSize().height));
        queryButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                // LOCAL only: adopt the query into the user's field. No search runs and no phase changes.
                draft.chooseSuggestion(suggestion.getQuery());
                setQueryTextProgrammatically(suggestion.getQuery());
            }
        });
        return queryButton;
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
        // Nothing host-owned to release now that the map view is gone; kept for the accessory lifecycle.
    }

    // ------------------------------------------------------------------ small UI helpers

    private static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static JLabel caption(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() - 1f));
        return label;
    }
}
