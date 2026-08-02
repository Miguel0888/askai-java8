package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;

import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * The read-only "Fragestellung" view: renders the research brief working copy with the HOST Markdown renderer
 * (no plugin-side renderer). It is a projection of the {@code FileResearchBriefStore} — not editable and not a
 * second source of truth. All methods run on the EDT.
 */
public final class ResearchBriefView extends JPanel {

    private static final String EMPTY =
            "_Noch keine Fragestellung. Beschreibe im Chat, was du erforschen möchtest._";

    private final MarkdownView markdownView;

    public ResearchBriefView(MarkdownViewFactory markdownViewFactory) {
        super(new BorderLayout());
        this.markdownView = markdownViewFactory.create(
                MarkdownViewOptions.builder().renderMermaid(false).selectable(true).build());
        add(markdownView.getComponent(), BorderLayout.CENTER);
    }

    /** Show the current brief working copy (or a friendly placeholder when there is none yet). */
    public void render(String briefMarkdown) {
        markdownView.setMarkdown(briefMarkdown == null || briefMarkdown.trim().isEmpty()
                ? EMPTY : briefMarkdown);
    }

    public void dispose() {
        markdownView.dispose();
    }
}
