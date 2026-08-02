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
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.function.Consumer;

/**
 * The scoping workspace support panel (RA-P6 §2), COMPACT by default so the chat stays the dominant
 * area: a short exploration-map preview (host-rendered Mermaid) and the agent's search suggestions as
 * a JUSTIFIED flow of yellow TAGS (the MCP tool-bubble yellow) — no heading, no query field. Clicking
 * a tag runs the search IMMEDIATELY (via the action the accessory wires in; result depth comes from
 * the configured search settings, default 10). A later projection replaces the tags, so the user can
 * keep broadening the field click by click before the deep, structured phase. The proposed query is
 * surfaced ONLY as the chat composer's placeholder (wired by the accessory), nowhere else. All
 * methods run on the EDT.
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
    private final JPanel tagsPanel;

    private Consumer<String> searchAction;
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
        // The tags speak for themselves — no section heading. BoxLayout must not stretch the panel
        // vertically, so its maximum height tracks the wrapped rows' preferred height.
        tagsPanel = new JPanel(new JustifiedTagLayout(6, 6)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        tagsPanel.setOpaque(false);
        tagsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(tagsPanel);
        content.add(body);

        add(content, BorderLayout.NORTH);
    }

    /** The accessory wires what a tag click runs (an immediate search with the query). */
    public void setSearchAction(Consumer<String> searchAction) {
        this.searchAction = searchAction;
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

    /** Apply the latest projection: refresh the map and REPLACE the tags with the newest knowledge. */
    public void apply(ScopingAssistantUpdate projection) {
        if (projection == null) {
            return;
        }
        applyMap(projection);
        renderTags(projection.getSearchSuggestions());
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
            // A broken diagram is a PRESENTATION problem only: the turn and the tags stay usable.
            mapView.setMarkdown("_Die Explorationskarte konnte nicht dargestellt werden._");
        }
    }

    private void renderTags(java.util.List<ScopingAssistantUpdate.Suggestion> suggestions) {
        tagsPanel.removeAll(); // REPLACE, never accumulate old tags
        for (final ScopingAssistantUpdate.Suggestion suggestion : suggestions) {
            ResearchTagButton tag = new ResearchTagButton(suggestion.getQuery());
            tag.setToolTipText(suggestion.getPurpose().isEmpty()
                    ? "Sofort danach suchen" : suggestion.getPurpose());
            tag.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    if (searchAction != null) {
                        searchAction.accept(suggestion.getQuery());
                    }
                }
            });
            tagsPanel.add(tag);
        }
        tagsPanel.revalidate();
        tagsPanel.repaint();
        revalidate();
        repaint();
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
