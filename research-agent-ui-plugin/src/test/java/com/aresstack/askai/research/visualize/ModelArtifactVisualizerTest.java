package com.aresstack.askai.research.visualize;

import com.aresstack.askai.agent.model.inference.AgentInferencePort;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The model-backed visualizer bridges the streaming port to a result; a failure degrades to NONE. */
public class ModelArtifactVisualizerTest {

    private static ArtifactSnapshot brief() {
        return new ArtifactSnapshot("research-brief", "# Brief\n\n## Fragestellung\n\nWearables?", "scoping");
    }

    @Test
    public void aCompletedGenerationIsParsedIntoADiagram() {
        AgentInferencePort port = new AgentInferencePort() {
            public Cancellable generate(InferenceRequest request, Listener listener) {
                listener.onCompleted("{\"decision\":\"DIAGRAM\",\"diagramType\":\"flowchart\","
                        + "\"title\":\"Struktur\",\"mermaid\":\"flowchart TD\\n A --> B\"}");
                return new Cancellable() {
                    public void cancel() {
                    }
                };
            }
        };

        VisualizationResult r = new ModelArtifactVisualizer(port).visualize(brief());
        assertTrue(r.isPresent());
        assertEquals(VisualizationType.FLOWCHART, r.getType());
    }

    @Test
    public void aFailedGenerationDegradesToNone() {
        AgentInferencePort port = new AgentInferencePort() {
            public Cancellable generate(InferenceRequest request, Listener listener) {
                listener.onFailed("no model selected");
                return new Cancellable() {
                    public void cancel() {
                    }
                };
            }
        };

        VisualizationResult r = new ModelArtifactVisualizer(port).visualize(brief());
        assertFalse(r.isPresent());
        assertEquals("no model selected", r.getReason());
    }
}
