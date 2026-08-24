package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContext;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;
import com.aresstack.comiccontrols.control.ComicSearchTag;

import javax.swing.JComponent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * The "Websuche" tag in the workspace top bar: the {@link ComicSearchTag} (yellow chip look, ink
 * magnifier), CENTERED between hamburger/ribbon and the trailing controls — the unfolding ribbon
 * pushes it right and may squeeze it. Firing runs {@link ResearchAgentSession#requestManualWebSearch}
 * — the SAME phase-independent /search path as a suggestion click, so the captured sources flow
 * into the corpus and the bot can review them afterwards. Never a chat turn, never a state command.
 */
public final class ResearchWebSearchToolbarContribution implements AgentToolbarContribution {

    @Override
    public String getId() {
        return "research-web-search";
    }

    @Override
    public Placement getPlacement() {
        return Placement.CENTER;
    }

    @Override
    public boolean supports(AgentSession session) {
        return session instanceof ResearchAgentSession;
    }

    @Override
    public JComponent createComponent(AgentToolbarContext context) {
        final ResearchAgentSession session = (ResearchAgentSession) context.getSession();
        final ComicSearchTag tag = new ComicSearchTag(
                "Websuche…", "Direkt im Web suchen (wie /search)");
        tag.addSearchAction(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                String query = tag.getText().trim();
                if (query.isEmpty()) {
                    return;
                }
                tag.setText(""); // the query lives on as the visible "Websuche:" breadcrumb
                session.requestManualWebSearch(query);
            }
        });
        return tag;
    }
}
