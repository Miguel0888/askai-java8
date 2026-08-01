package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;
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
 * The scoping workspace support panel (RA-P6 §2), COMPACT by default so the chat stays the dominant area: a
 * short exploration-map preview (host-rendered Mermaid, with the host's own zoom/copy controls for detail), a
 * one-line-per-query suggestion list (purpose as tooltip), and a LOCAL, user-owned query draft. The map+
 * suggestions body is collapsible. It shows the CURRENT projection (a later turn replaces it), protects a
 * manual query edit, and runs NO search and moves NO workflow. Advice is not shown. All methods run on the EDT.
 */
public final class ScopingSupportView extends JPanel {

    private static final String MAP_CAPTION =
            "Explorationskarte – dient der Orientierung und ist keine bestätigte Evidenz.";
    /** Keep the map a small preview; the host renderer offers zoom/copy for a detailed view. */
    private static final int MAP_PREVIEW_HEIGHT = 150;

    private final MarkdownView mapView;
    private final JPanel mapSection;
    private final JPanel body = new JPanel();
    private final JButton collapseToggle = new JButton("▾");
    private final JLabel suggestionsEmpty = caption("Noch keine Suchvorschläge.");
    private final JPanel suggestionsList = new JPanel();
    private final JTextField queryField = new JTextField();
    private final ScopingQueryDraft draft = new ScopingQueryDraft();

    private boolean programmaticQueryEdit;
    private boolean collapsed;

    public ScopingSupportView(MarkdownViewFactory markdownViewFactory) {
        super(new BorderLayout());
        this.mapView = markdownViewFactory.create(
                MarkdownViewOptions.builder().renderMermaid(true).selectable(true).build());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        content.add(header());

        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(caption(MAP_CAPTION));
        mapSection = new JPanel(new BorderLayout());
        mapSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        mapSection.add(mapView.getComponent(), BorderLayout.CENTER);
        mapSection.setPreferredSize(new Dimension(0, MAP_PREVIEW_HEIGHT));
        mapSection.setMaximumSize(new Dimension(Integer.MAX_VALUE, MAP_PREVIEW_HEIGHT));
        body.add(mapSection);
        body.add(Box.createVerticalStrut(6));
        body.add(sectionTitle("Suchvorschläge"));
        suggestionsList.setLayout(new BoxLayout(suggestionsList, BoxLayout.Y_AXIS));
        suggestionsList.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(suggestionsList);
        body.add(suggestionsEmpty);
        content.add(body);

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

    private JComponent header() {
        JPanel row = new JPanel(new BorderLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, sectionTitle("X").getPreferredSize().height + 4));
        row.add(sectionTitle("Exploration"), BorderLayout.WEST);
        collapseToggle.setMargin(new java.awt.Insets(0, 4, 0, 4));
        collapseToggle.setToolTipText("Ein-/ausklappen");
        collapseToggle.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                collapsed = !collapsed;
                body.setVisible(!collapsed);
                collapseToggle.setText(collapsed ? "▸" : "▾");
                revalidate();
                repaint();
            }
        });
        row.add(collapseToggle, BorderLayout.EAST);
        return row;
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

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
