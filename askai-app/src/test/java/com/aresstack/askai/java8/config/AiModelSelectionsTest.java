package com.aresstack.askai.java8.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * The centrally-managed AI model selections value object plus its threading through
 * {@link AppConfiguration}: with-copies, normalization, defaults, and carry-over across the other
 * config withers (so a save site that rebuilds an AppConfiguration never silently drops the models).
 */
public class AiModelSelectionsTest {

    @Test
    public void defaultsAreEmpty() {
        AiModelSelections defaults = AiModelSelections.defaults();
        assertEquals("", defaults.getMainModel());
        assertEquals("", defaults.getRerankerModel());
        assertEquals("", defaults.getEmbeddingsModel());
    }

    @Test
    public void constructorNormalizesNullAndTrims() {
        AiModelSelections selections = new AiModelSelections(null, "  qwen3-reranker  ", "\tnomic-embed\t");
        assertEquals("", selections.getMainModel());
        assertEquals("qwen3-reranker", selections.getRerankerModel());
        assertEquals("nomic-embed", selections.getEmbeddingsModel());
    }

    @Test
    public void withersReplaceOnlyTheTargetedField() {
        AiModelSelections base = AiModelSelections.defaults()
                .withMainModel("gpt-oss-20b")
                .withRerankerModel("reranker-1")
                .withEmbeddingsModel("embed-1");
        assertEquals("gpt-oss-20b", base.getMainModel());
        assertEquals("reranker-1", base.getRerankerModel());
        assertEquals("embed-1", base.getEmbeddingsModel());

        AiModelSelections changed = base.withMainModel("llama-3.1-8b");
        assertEquals("llama-3.1-8b", changed.getMainModel());
        assertEquals("reranker-1", changed.getRerankerModel());
        assertEquals("embed-1", changed.getEmbeddingsModel());
    }

    @Test
    public void valueEquality() {
        assertEquals(new AiModelSelections("a", "b", "c"), new AiModelSelections("a", "b", "c"));
        assertNotEquals(new AiModelSelections("a", "b", "c"), new AiModelSelections("a", "b", "x"));
    }

    @Test
    public void appConfigurationDefaultsCarryEmptySelections() {
        assertEquals(AiModelSelections.defaults(), AppConfiguration.defaults().getAiModelSelections());
    }

    @Test
    public void rerankerAndEmbeddingsPersistComposesWithoutTouchingMainModel() {
        // Mirrors AskAiModel.persistRerankerAndEmbeddingsModels: chaining the two withers must set both
        // and leave the chat-window main model untouched.
        AiModelSelections start = AiModelSelections.defaults().withMainModel("gpt-oss-20b");
        AiModelSelections after = start.withRerankerModel("rr").withEmbeddingsModel("emb");
        assertEquals("gpt-oss-20b", after.getMainModel());
        assertEquals("rr", after.getRerankerModel());
        assertEquals("emb", after.getEmbeddingsModel());
    }

    @Test
    public void appConfigurationCarriesSelectionsAcrossOtherWithers() {
        AiModelSelections models = new AiModelSelections("main-x", "rerank-x", "embed-x");
        AppConfiguration configuration = AppConfiguration.defaults()
                .withAiModelSelections(models)
                // any subsequent unrelated wither must preserve the selections
                .withChatColors(ChatColorSettings.defaults())
                .withHuggingFaceSearchFilters("something");
        assertEquals(models, configuration.getAiModelSelections());
    }
}
