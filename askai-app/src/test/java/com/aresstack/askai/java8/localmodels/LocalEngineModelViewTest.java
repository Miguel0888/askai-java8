package com.aresstack.askai.java8.localmodels;

import com.aresstack.windirectml.catalog.ModelCapability;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The local-engine card presentation is catalog-driven, family-aware and never a hardcoded rerank/CPU line. */
public class LocalEngineModelViewTest {

    @Test
    public void repositoryIsExtractedFromTheVirtualName() {
        assertEquals("cross-encoder/ms-marco-MiniLM-L6-v2",
                LocalEngineModelView.repositoryOf("local/cross-encoder/ms-marco-MiniLM-L6-v2:latest"));
        assertEquals("", LocalEngineModelView.repositoryOf("llama3:latest"));
    }

    @Test
    public void rerankerCardShowsFamilyRerankCpuDirectmlNotAHardcodedString() {
        String line = LocalEngineModelView.detailLine("local/cross-encoder/ms-marco-MiniLM-L6-v2:latest");
        assertTrue(line, line.contains("Cross-encoder reranker"));
        assertTrue(line, line.contains("Capability: rerank"));
        assertTrue(line, line.contains("Backend: cpu, directml"));
        assertTrue(LocalEngineModelView.hasCapability(
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest", ModelCapability.RERANK));
    }

    @Test
    public void encoderCardShowsEmbeddingCapability() {
        String line = LocalEngineModelView.detailLine("local/intfloat/e5-base-v2:latest");
        assertTrue(line, line.contains("Capability: embedding"));
        assertFalse(LocalEngineModelView.hasCapability(
                "local/intfloat/e5-base-v2:latest", ModelCapability.RERANK));
        assertTrue(LocalEngineModelView.hasCapability(
                "local/intfloat/e5-base-v2:latest", ModelCapability.EMBEDDING));
    }

    @Test
    public void generationFamiliesReadAsRuntimePendingNotRunnable() {
        assertTrue(LocalEngineModelView.isRuntimePending("local/Qwen/Qwen2.5-Coder-0.5B-Instruct:latest"));
        String line = LocalEngineModelView.detailLine("local/google/gemma-3-270m-it:latest");
        assertTrue(line, line.contains("Runtime integration pending"));
        assertFalse(line, line.contains("Backend:"));
        // An encoder is NOT pending.
        assertFalse(LocalEngineModelView.isRuntimePending("local/intfloat/e5-small-v2:latest"));
    }

    @Test
    public void unknownVirtualNameDegradesReadably() {
        String line = LocalEngineModelView.detailLine("local/foo/bar:latest");
        assertTrue(line, line.contains("AskAI Local Engine"));
        assertFalse(LocalEngineModelView.hasCapability("local/foo/bar:latest", ModelCapability.RERANK));
    }
}
