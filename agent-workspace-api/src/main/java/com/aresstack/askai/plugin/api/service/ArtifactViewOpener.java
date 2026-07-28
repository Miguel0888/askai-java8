package com.aresstack.askai.plugin.api.service;

/**
 * Host service (looked up via {@code AgentHostContext.getService}) that reveals an artifact tab of the active
 * agent workspace — e.g. the sources view from a result card's "open sources" action. Hosts without an
 * artifact area provide no instance; callers must degrade visibly, never silently.
 */
public interface ArtifactViewOpener {

    void openArtifact(String artifactId);
}
