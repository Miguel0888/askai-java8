package com.aresstack.askai.java8.localmodels;

import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ManifestValidation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Host JSON I/O round-trips the shared manifest, and the shared catalog rules still hold after a round-trip. */
public class LocalModelManifestCodecTest {

    @Test
    public void jsonRoundTripPreservesFactsAndStaysValid() {
        LocalRuntimeModelDescriptor minilm =
                LocalModelCatalog.findByRepositoryId("sentence-transformers/all-MiniLM-L6-v2");
        String json = LocalModelManifestCodec.toJson(
                InstalledModelManifest.forInstall(minilm, "rev123", 1700000000000L));
        InstalledModelManifest read = LocalModelManifestCodec.parse(json);
        assertEquals(2, read.getSchemaVersion());
        assertEquals(minilm.virtualModelName(), read.getVirtualName());
        assertEquals("rev123", read.getResolvedRevision());
        assertEquals("encoder.wdmlpack", read.getRuntimePackage());
        assertEquals(1700000000000L, read.getInstalledAt());
        assertEquals(ManifestValidation.VALID, read.validate(read.getSchemaVersion()));
    }

    @Test
    public void v1BackendSupportKeyIsStillRead() {
        String v1 = "{\"schemaVersion\":1,"
                + "\"virtualName\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\","
                + "\"huggingFaceRepository\":\"cross-encoder/ms-marco-MiniLM-L6-v2\","
                + "\"runtimeModelId\":\"MS_MARCO_MINILM_L6\","
                + "\"capabilities\":[\"rerank\"],\"backendSupport\":[\"cpu\",\"directml\"],"
                + "\"state\":\"RUNNABLE\"}";
        InstalledModelManifest read = LocalModelManifestCodec.parse(v1);
        assertEquals(1, read.getSchemaVersion());
        assertEquals(java.util.Arrays.asList("cpu", "directml"), read.getSupportedBackends());
        assertEquals(ManifestValidation.VALID, read.validate(read.getSchemaVersion()));
    }

    @Test
    public void unreadableJsonParsesToNull() {
        assertNull(LocalModelManifestCodec.parse("{not json"));
    }
}
