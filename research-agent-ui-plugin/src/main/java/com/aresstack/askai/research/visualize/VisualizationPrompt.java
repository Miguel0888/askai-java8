package com.aresstack.askai.research.visualize;

/**
 * The visualizer's prompt. Its hard rule is what keeps a visualization honest: depict ONLY structure that is
 * explicitly in the artifact, never an invented taxonomy, and return NONE when nothing is justified — which
 * solves the "a mindmap of a one-word topic fakes a taxonomy" problem.
 */
final class VisualizationPrompt {

    private VisualizationPrompt() {
    }

    static String system() {
        return "You visualize a SINGLE supplied artifact for a research tool. Turn its EXISTING structure "
                + "into a small, valid Mermaid diagram — or decide there is nothing useful to visualize yet.\n\n"
                + "Hard rules:\n"
                + "- Use ONLY structure and relationships EXPLICITLY present in the supplied artifact. Do NOT "
                + "add factual claims, an inferred taxonomy, causal relations, or evidence.\n"
                + "- If the artifact does not justify a useful visualization (e.g. it is a one-line topic), "
                + "return NONE. NONE is a good, valid answer — never invent a diagram to fill space.\n"
                + "- Choose the diagram type that fits the artifact's ACTUAL structure: FLOWCHART, MINDMAP, "
                + "TIMELINE, SEQUENCE, CLASS_DIAGRAM or GRAPH. Use MINDMAP only when the artifact genuinely "
                + "contains a hierarchy.\n"
                + "- Output only the Mermaid body (no code fences), and make it syntactically valid.\n\n"
                + "Answer with a SINGLE JSON object and nothing else:\n"
                + "{\n"
                + "  \"decision\": \"DIAGRAM\" | \"NONE\",\n"
                + "  \"diagramType\": \"FLOWCHART|MINDMAP|TIMELINE|SEQUENCE|CLASS_DIAGRAM|GRAPH\",  // when "
                + "DIAGRAM\n"
                + "  \"title\": string,    // short, when DIAGRAM\n"
                + "  \"mermaid\": string,  // the diagram body, when DIAGRAM\n"
                + "  \"reason\": string    // why nothing is visualized, when NONE\n"
                + "}";
    }

    static String user(ArtifactSnapshot snapshot) {
        return "Artifact id: " + snapshot.getArtifactId() + "\nPhase: " + snapshot.getPhaseId()
                + "\n\nArtifact content:\n" + snapshot.getContent();
    }
}
