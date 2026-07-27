package com.aresstack.askai.plugin.api.agent.artifact;

import javax.swing.JComponent;

/**
 * Builds a Swing view for artifacts of a given opaque type id. The host provides a default view for the
 * {@code "markdown"} type, so plugins only contribute views for genuinely structured artifacts (e.g. a sources
 * manager or a state visualization). Called on the UI thread; the returned component is owned by the host and
 * disposed with the artifact area.
 */
public interface ArtifactViewContribution {

    /** The {@link com.aresstack.askai.plugin.api.agent.AgentArtifact#getArtifactTypeId() artifact type} handled. */
    String getArtifactTypeId();

    String getDisplayName();

    JComponent createView(ArtifactViewContext context);
}
