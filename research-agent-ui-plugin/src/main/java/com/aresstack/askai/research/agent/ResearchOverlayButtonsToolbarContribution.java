package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContext;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;
import com.aresstack.comiccontrols.control.ComicButton;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * The CENTERED comic icon-button strip in the top bar — the spot the phase selector (and before
 * it, the search bar) vacated. Each button opens ITS overlay over the chat; the first resident is
 * the sources MINDMAP (moved here from beside the Websuche tag, action unchanged:
 * {@link ResearchMindmapAction}, the same path {@code /map} runs). Later overlay buttons join
 * this row, one small quiet ComicButton each.
 */
public final class ResearchOverlayButtonsToolbarContribution implements AgentToolbarContribution {

    @Override
    public String getId() {
        return "research-overlay-buttons";
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
    public JComponent createComponent(final AgentToolbarContext context) {
        final ResearchAgentSession session = (ResearchAgentSession) context.getSession();
        ComicButton mindmap = new ComicButton("",
                new ResearchWebSearchToolbarContribution.MindmapIcon());
        mindmap.setToolTipText("Quellen visualisieren");
        mindmap.setFocusable(false);
        mindmap.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        mindmap.setPreferredSize(new Dimension(26, 26));
        mindmap.setMaximumSize(new Dimension(26, 26));
        mindmap.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                ResearchMindmapAction.open(session, new ResearchMindmapAction.OverlayHost() {
                    public void showDiagram(String mermaidSource, String title) {
                        context.showDiagramOverlay(mermaidSource, title);
                    }

                    public void showHint(JComponent content, String title) {
                        context.showTranscriptOverlay(content, title);
                    }
                });
            }
        });

        // The CONCEPT mindmap button (left of the sources one): the same snapshot the Konzept tab
        // renders, as the host's diagram overlay — one truth, one pipeline (K3).
        ComicButton conceptMap = new ComicButton("",
                new ResearchWebSearchToolbarContribution.MindmapIcon());
        conceptMap.setToolTipText("Visualize concept");
        conceptMap.setFocusable(false);
        conceptMap.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        conceptMap.setPreferredSize(new Dimension(26, 26));
        conceptMap.setMaximumSize(new Dimension(26, 26));
        conceptMap.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                ConceptMindmapAction.open(session, new ConceptMindmapAction.OverlayHost() {
                    public void showDiagram(String mermaidSource, String title) {
                        context.showDiagramOverlay(mermaidSource, title);
                    }

                    public void showHint(JComponent content, String title) {
                        context.showTranscriptOverlay(content, title);
                    }
                });
            }
        });

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.add(conceptMap);
        row.add(mindmap); // future overlay buttons line up right here
        return row;
    }
}
