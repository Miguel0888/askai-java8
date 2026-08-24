package com.aresstack.askai.research.visualize;

import com.aresstack.askai.agent.model.inference.AgentInferencePort;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The render-error feedback loop (issue #36 line): Mermaid that does not render is sent BACK to
 * the model with the renderer's concrete error; a second failure degrades honestly to FAILED —
 * broken diagrams never reach the UI.
 */
public class ModelArtifactVisualizerRetryTest {

    private static final String BROKEN = "mindmap\n  root((x\n    unbalanced";
    private static final String FIXED = "mindmap\n  root((x))";

    /** Answers a scripted JSON per call and records every user prompt. */
    private static final class ScriptedPort implements AgentInferencePort {
        final List<String> prompts = new ArrayList<String>();
        final List<String> answers;

        ScriptedPort(String... answers) {
            this.answers = java.util.Arrays.asList(answers);
        }

        public Cancellable generate(InferenceRequest request, Listener listener) {
            prompts.add(request.getUserPrompt());
            listener.onCompleted(answers.get(Math.min(prompts.size() - 1, answers.size() - 1)));
            return new Cancellable() {
                public void cancel() {
                }
            };
        }
    }

    private static String diagramJson(String mermaid) {
        return "{\"decision\":\"DIAGRAM\",\"diagramType\":\"MINDMAP\",\"title\":\"t\","
                + "\"mermaid\":\"" + mermaid.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n") + "\"}";
    }

    private static final MermaidValidator ONLY_FIXED_RENDERS = new MermaidValidator() {
        public String validate(String mermaid) {
            return FIXED.equals(mermaid) ? null : "Parse error on line 3: unbalanced node";
        }
    };

    @Test
    public void theRendererErrorIsFedBackAndTheFixedRetryWins() {
        ScriptedPort port = new ScriptedPort(diagramJson(BROKEN), diagramJson(FIXED));
        ModelArtifactVisualizer visualizer = new ModelArtifactVisualizer(port, ONLY_FIXED_RENDERS);

        VisualizationResult result = visualizer.visualize(
                new ArtifactSnapshot("research-brief", "# Thema\nInhalt", "scoping"));

        assertEquals(VisualizationResult.Kind.DIAGRAM, result.getKind());
        assertEquals(FIXED, result.getMermaid());
        assertEquals("exactly one retry", 2, port.prompts.size());
        assertTrue("the retry carries the renderer's concrete error",
                port.prompts.get(1).contains("Parse error on line 3: unbalanced node"));
        assertTrue("the retry carries the broken source to fix",
                port.prompts.get(1).contains("unbalanced"));
    }

    @Test
    public void aSecondRenderFailureDegradesHonestlyToFailed() {
        ScriptedPort port = new ScriptedPort(diagramJson(BROKEN), diagramJson(BROKEN));
        ModelArtifactVisualizer visualizer = new ModelArtifactVisualizer(port, ONLY_FIXED_RENDERS);

        VisualizationResult result = visualizer.visualize(
                new ArtifactSnapshot("research-brief", "# Thema\nInhalt", "scoping"));

        assertEquals(VisualizationResult.Kind.FAILED, result.getKind());
        assertTrue(result.getReason().contains("Parse error"));
        assertEquals("no endless retry loop", 2, port.prompts.size());
    }

    @Test
    public void aRenderingDiagramNeedsNoRetry() {
        ScriptedPort port = new ScriptedPort(diagramJson(FIXED));
        ModelArtifactVisualizer visualizer = new ModelArtifactVisualizer(port, ONLY_FIXED_RENDERS);

        VisualizationResult result = visualizer.visualize(
                new ArtifactSnapshot("research-brief", "# Thema\nInhalt", "scoping"));

        assertEquals(VisualizationResult.Kind.DIAGRAM, result.getKind());
        assertEquals(1, port.prompts.size());
    }
}
