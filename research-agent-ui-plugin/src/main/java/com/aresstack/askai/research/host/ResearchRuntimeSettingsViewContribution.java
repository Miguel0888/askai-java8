package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifacts;

import javax.swing.JComponent;
import javax.swing.JLabel;

/**
 * Contributes the {@link ResearchRuntimeSettingsPanel} as the {@code research.runtime} view — the existing
 * configuration surface of the research workspace. The panel persists through the SAME
 * {@link ResearchRuntimeSettings} mapper the session factory reads; a saved change applies to the NEXT
 * session (the running one keeps its backend).
 */
public final class ResearchRuntimeSettingsViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_RUNTIME;
    }

    @Override
    public String getDisplayName() {
        return "Runtime";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        AgentSession session = context.getSession();
        if (!(session instanceof ResearchAgentSession)) {
            return new JLabel("Runtime settings are only available for research sessions.");
        }
        ResearchAgentSession research = (ResearchAgentSession) session;
        // The AI-model selection (reranker/embeddings) lives centrally in AskAI, not here.
        return new ResearchRuntimeSettingsPanel(research.getHostStateStore());
    }
}
