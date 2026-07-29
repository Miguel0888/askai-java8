package com.aresstack.askai.java8.localmodels;

import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ModelCapability;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Manifest v2 round-trips the catalog facts and still reads the v1 reranker manifest. */
public class LocalModelManifestTest {

    @Test
    public void v2RoundTripsForAReranker() {
        LocalRuntimeModelDescriptor reranker =
                LocalModelCatalog.findByRepositoryId("cross-encoder/ms-marco-MiniLM-L6-v2");
        LocalModelManifest written = LocalModelManifest.forInstall(reranker,
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest", "abc123", 1700000000000L);
        LocalModelManifest read = LocalModelManifest.parse(written.toJson());
        assertEquals(2, read.getSchemaVersion());
        assertEquals("local/cross-encoder/ms-marco-MiniLM-L6-v2:latest", read.getVirtualName());
        assertEquals("abc123", read.getResolvedRevision());
        assertEquals("cross_encoder", read.getRuntimeFamily());
        assertEquals("reranker.wdmlpack", read.getRuntimePackage());
        assertEquals(Arrays.asList("rerank"), read.getCapabilities());
        assertEquals(Arrays.asList("cpu", "directml"), read.getSupportedBackends());
        assertEquals("safetensors", read.getSourceFormat());
        assertEquals(1700000000000L, read.getInstalledAt());
        assertTrue(read.isRunnable());
        assertTrue(read.hasCapability(ModelCapability.RERANK));
        assertFalse(read.hasCapability(ModelCapability.EMBEDDING));
    }

    @Test
    public void v2CarriesEmbeddingCapabilityForEncoders() {
        LocalRuntimeModelDescriptor minilm =
                LocalModelCatalog.findByRepositoryId("sentence-transformers/all-MiniLM-L6-v2");
        LocalModelManifest read = LocalModelManifest.parse(LocalModelManifest.forInstall(minilm,
                minilm.virtualModelName(), "rev", 42L).toJson());
        assertEquals(Arrays.asList("embedding"), read.getCapabilities());
        assertTrue(read.hasCapability(ModelCapability.EMBEDDING));
        assertEquals("encoder.wdmlpack", read.getRuntimePackage());
    }

    @Test
    public void v1RerankerManifestStillReads() {
        String v1 = "{\"schemaVersion\":1,"
                + "\"virtualName\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\","
                + "\"huggingFaceRepository\":\"cross-encoder/ms-marco-MiniLM-L6-v2\","
                + "\"resolvedRevision\":\"deadbeef\",\"runtimeModelId\":\"MS_MARCO_MINILM_L6\","
                + "\"capabilities\":[\"rerank\"],\"backendSupport\":[\"cpu\",\"directml\"],"
                + "\"state\":\"RUNNABLE\"}";
        LocalModelManifest read = LocalModelManifest.parse(v1);
        assertEquals(1, read.getSchemaVersion());
        assertEquals("MS_MARCO_MINILM_L6", read.getRuntimeModelId());
        assertEquals(Arrays.asList("rerank"), read.getCapabilities());
        // v1 used backendSupport rather than supportedBackends.
        assertEquals(Arrays.asList("cpu", "directml"), read.getSupportedBackends());
        assertTrue(read.isRunnable());
        assertTrue(read.hasCapability(ModelCapability.RERANK));
    }

    @Test
    public void unreadableOrIncompleteManifestParsesToNull() {
        assertNull(LocalModelManifest.parse("{not json"));
        assertNull(LocalModelManifest.parse("{\"schemaVersion\":2}")); // no virtualName
    }
}
