package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;
import com.aresstack.askai.research.concept.ConceptProjection;
import com.aresstack.askai.research.concept.ConceptTopicScanner;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicSearchBar;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.Locale;

/**
 * The Konzept tab's content — since K4 the ONE scoping work surface: a PURE view of the concept
 * store state, no second UI model. The legacy [Brief] card and its toggles are gone (gates 1-9c
 * proved ConceptStore + ResearchScopeDraft are the whole truth; the brief was a competing old
 * projection). The MINDMAP stays behind the "Visualize concept" toolbar button; the JSON view is
 * READ-ONLY by design.
 *
 * <p>Comic dress: content takes the full height; the footer strip carries the concept SEARCH BAR
 * (the "Chats durchsuchen…" idiom — Enter filters the cards by name, an empty Enter restores the
 * JSON) plus the quiet revision witness and the manual reload. All methods EDT.</p>
 */
public final class ConceptPaperView extends JPanel {

    private static final String EMPTY =
            "_No concept yet. Describe in the chat what you want to research._";

    private final MarkdownView jsonView;
    private final JLabel revisionLabel = new JLabel(" ");
    private final ComicButton refreshButton = new ComicButton("⟳");
    private final ComicSearchBar searchBar =
            new ComicSearchBar("Search concept…", "Filter the concept cards by name (Enter; "
                    + "empty Enter shows the full JSON again)");

    private ConceptProjection lastProjection;
    private String filterQuery = "";

    /** Wire the manual ⟳ button to the owner's re-read (the same runnable the listeners use). */
    public void setRefreshAction(final Runnable refresh) {
        for (ActionListener old : refreshButton.getActionListeners()) {
            refreshButton.removeActionListener(old);
        }
        refreshButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                refresh.run();
            }
        });
    }

    public ConceptPaperView(MarkdownViewFactory markdownViewFactory) {
        super(new BorderLayout());
        setOpaque(false);
        this.jsonView = markdownViewFactory.create(
                MarkdownViewOptions.builder().renderMermaid(false).selectable(true).build());
        add(jsonView.getComponent(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        searchBar.addSearchAction(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                filterQuery = searchBar.getText().trim();
                renderCurrent();
            }
        });
    }

    /** The footer strip: concept search left, revision witness + manual reload right. */
    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H,
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H));
        footer.add(searchBar, BorderLayout.CENTER);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        status.setOpaque(false);
        revisionLabel.setFont(ResearchUiTypography.regular(11.5f));
        revisionLabel.setEnabled(false); // quiet gray, diagnostic value only
        status.add(revisionLabel);
        refreshButton.setFocusable(false);
        refreshButton.setToolTipText("Reload view");
        refreshButton.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        status.add(refreshButton);
        footer.add(status, BorderLayout.EAST);
        return footer;
    }

    /**
     * Render one atomic snapshot. {@code projection} may be {@code null} (clickdummy without a
     * concept service).
     */
    public void render(ConceptProjection projection) {
        lastProjection = projection;
        renderCurrent();
    }

    private void renderCurrent() {
        ConceptProjection projection = lastProjection;
        if (projection == null) {
            jsonView.setMarkdown(EMPTY);
            revisionLabel.setText(" ");
            return;
        }
        revisionLabel.setText("rev " + projection.getWorkingRevision());
        if (!projection.isReadable()) {
            // Never creative repair: the honest diagnosis leads, the raw text stays visible.
            jsonView.setMarkdown("**Concept not readable**\n\n```\n"
                    + projection.getDiagnosticText() + "\n```\n\n```\n"
                    + projection.getPrettyJson() + "\n```");
            return;
        }
        if (filterQuery.isEmpty()) {
            jsonView.setMarkdown("```json\n" + projection.getPrettyJson() + "\n```");
            return;
        }
        jsonView.setMarkdown(filterMarkdown(projection.getPrettyJson(), filterQuery));
    }

    /** The filter result: every card path whose NAME contains the query, case-insensitive. */
    static String filterMarkdown(String documentJson, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int matches = 0;
        for (List<String> path : ConceptTopicScanner.collectCardPaths(documentJson)) {
            if (!path.get(path.size() - 1).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            matches++;
            sb.append("- ");
            for (int index = 0; index < path.size(); index++) {
                if (index > 0) {
                    sb.append(" › ");
                }
                sb.append(path.get(index));
            }
            sb.append('\n');
        }
        String header = matches == 0
                ? "**No card matches \"" + query + "\"**"
                : "**" + matches + " card" + (matches == 1 ? "" : "s") + " matching \""
                        + query + "\"**";
        return header + "\n\n" + sb
                + "\n_Press Enter with an empty search field to show the full JSON again._";
    }

    public void dispose() {
        jsonView.dispose();
    }
}
