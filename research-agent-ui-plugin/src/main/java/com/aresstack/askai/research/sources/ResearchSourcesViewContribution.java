package com.aresstack.askai.research.sources;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifacts;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/** Contributes the structured {@link ResearchSourcesView} for the {@code research.sources} artifact. */
public final class ResearchSourcesViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_SOURCES;
    }

    @Override
    public String getDisplayName() {
        return "Sources";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        AgentSession session = context.getSession();
        if (session instanceof ResearchAgentSession) {
            ResearchAgentSession research = (ResearchAgentSession) session;
            ResearchSourceRepository repository = research.getSourceRepository();
            final ResearchSourcesView view =
                    new ResearchSourcesView(repository, ResearchSourcesView.demoKnownSections());
            // Keep the table live: every session state change (run finished, approval, continuation)
            // re-reads the repository, so freshly accepted sources appear without manual filtering.
            research.addStateListener(new Runnable() {
                public void run() {
                    view.refresh();
                }
            });
            // #43 slice 9: a Document [n] badge reveals this tab and focuses the source.
            research.setSourcesFocusHandler(new ResearchAgentSession.SourcesFocusHandler() {
                public void focusSource(String sourceId) {
                    view.focusSource(sourceId);
                }
            });
            // #28/#39.7: the Passages tab reads the derived generation for the selected source.
            view.setPassagesProvider(new ResearchSourcesView.PassagesProvider() {
                public java.util.List<com.aresstack.askai.research.domain.Passage>
                        passagesForSource(String sourceId) {
                    return research.passagesForSource(sourceId);
                }
            });
            // #39: the Add-source dialog imports through the session's canonical boundary.
            view.setImportHandler(new ResearchSourcesView.ImportHandler() {
                public String importSource(com.aresstack.askai.research.capture
                        .SourceImportService.SourceInput input) {
                    return research.importUserSource(input);
                }
            });
            return view;
        }
        JPanel placeholder = new JPanel(new BorderLayout());
        placeholder.add(new JLabel("No source repository available."), BorderLayout.NORTH);
        return placeholder;
    }
}
