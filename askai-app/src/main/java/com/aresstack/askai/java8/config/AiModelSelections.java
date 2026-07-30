package com.aresstack.askai.java8.config;

/**
 * The centrally-managed AI model selections that AskAI owns for ALL plugins. Three sekret-free model
 * identifiers:
 * <ul>
 *   <li>the <b>main</b> (chat/generation) model — the one the user picks in the chat window, shared by
 *       every plugin as its generation/inference model,</li>
 *   <li>the <b>reranker</b> model (cross-encoder used for relevance ranking), and</li>
 *   <li>the <b>embeddings</b> model.</li>
 * </ul>
 *
 * <p>Only model NAMES live here — never endpoints or secrets. The host resolves the actual endpoint
 * (local sidecar port or remote Ollama base URL) when it publishes a descriptor to a plugin. An empty
 * value means "not explicitly selected"; consumers fall back to their existing default behaviour.</p>
 */
public final class AiModelSelections {

    private final String mainModel;
    private final String rerankerModel;
    private final String embeddingsModel;

    public AiModelSelections(String mainModel, String rerankerModel, String embeddingsModel) {
        this.mainModel = normalize(mainModel);
        this.rerankerModel = normalize(rerankerModel);
        this.embeddingsModel = normalize(embeddingsModel);
    }

    /** All-empty selections: nothing explicitly chosen yet. */
    public static AiModelSelections defaults() {
        return new AiModelSelections("", "", "");
    }

    public String getMainModel() {
        return mainModel;
    }

    public String getRerankerModel() {
        return rerankerModel;
    }

    public String getEmbeddingsModel() {
        return embeddingsModel;
    }

    public AiModelSelections withMainModel(String value) {
        return new AiModelSelections(value, rerankerModel, embeddingsModel);
    }

    public AiModelSelections withRerankerModel(String value) {
        return new AiModelSelections(mainModel, value, embeddingsModel);
    }

    public AiModelSelections withEmbeddingsModel(String value) {
        return new AiModelSelections(mainModel, rerankerModel, value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AiModelSelections)) {
            return false;
        }
        AiModelSelections that = (AiModelSelections) other;
        return mainModel.equals(that.mainModel)
                && rerankerModel.equals(that.rerankerModel)
                && embeddingsModel.equals(that.embeddingsModel);
    }

    @Override
    public int hashCode() {
        int result = mainModel.hashCode();
        result = 31 * result + rerankerModel.hashCode();
        result = 31 * result + embeddingsModel.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "AiModelSelections{main='" + mainModel + "', reranker='" + rerankerModel
                + "', embeddings='" + embeddingsModel + "'}";
    }
}
