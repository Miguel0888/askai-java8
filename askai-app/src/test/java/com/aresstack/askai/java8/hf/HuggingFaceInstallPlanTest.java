package com.aresstack.askai.java8.hf;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** The install contract survives the async download as a sidecar next to the GGUF. */
public class HuggingFaceInstallPlanTest {

    @Test
    public void sidecarRoundTripsRepoAndCapabilities() throws Exception {
        File gguf = File.createTempFile("askai-model-", ".gguf");
        gguf.deleteOnExit();

        HuggingFaceInstallPlan plan = new HuggingFaceInstallPlan(
                "owner/model-GGUF", "main", "model:q4_k_m",
                Arrays.asList("TEXT", "AUDIO"), Arrays.asList("completion", "audio"));
        plan.writeSidecar(gguf);

        HuggingFaceInstallPlan loaded = HuggingFaceInstallPlan.readSidecar(gguf);
        assertEquals("owner/model-GGUF", loaded.getRepositoryId());
        assertEquals("main", loaded.getRevision());
        assertEquals("model:q4_k_m", loaded.getTargetModelName());
        assertEquals(Arrays.asList("TEXT", "AUDIO"), loaded.getDeclaredCapabilities());
        assertEquals(Arrays.asList("completion", "audio"), loaded.getRequiredOllamaCapabilities());

        new File(gguf.getParentFile(), gguf.getName() + ".askai-install.json").deleteOnExit();
    }

    @Test
    public void sidecarCarriesModelTypeForFamilyDerivation() throws Exception {
        File gguf = File.createTempFile("askai-typed-", ".gguf");
        gguf.deleteOnExit();

        HuggingFaceInstallPlan plan = new HuggingFaceInstallPlan(
                "owner/model-GGUF", "main", "model:q4_k_m",
                Arrays.asList("TEXT"), Arrays.asList("completion"), "qwen3");
        plan.writeSidecar(gguf);

        HuggingFaceInstallPlan loaded = HuggingFaceInstallPlan.readSidecar(gguf);
        assertEquals("qwen3", loaded.getModelType());
        new File(gguf.getParentFile(), gguf.getName() + ".askai-install.json").deleteOnExit();
    }

    @Test
    public void legacyV1SidecarWithoutModelTypeReadsAsEmpty() throws Exception {
        File gguf = File.createTempFile("askai-v1-", ".gguf");
        gguf.deleteOnExit();
        File sidecar = new File(gguf.getParentFile(), gguf.getName() + ".askai-install.json");
        sidecar.deleteOnExit();
        FileOutputStream out = new FileOutputStream(sidecar);
        out.write(("{\"repositoryId\":\"o/m\",\"revision\":\"main\",\"targetModelName\":\"m\","
                + "\"declaredCapabilities\":[\"TEXT\"],\"requiredOllamaCapabilities\":[\"completion\"]}")
                .getBytes("UTF-8"));
        out.close();

        HuggingFaceInstallPlan loaded = HuggingFaceInstallPlan.readSidecar(gguf);
        assertEquals("o/m", loaded.getRepositoryId());
        assertEquals("", loaded.getModelType());
        assertEquals(Arrays.asList("completion"), loaded.getRequiredOllamaCapabilities());
    }

    @Test
    public void missingSidecarIsNull() throws Exception {
        File gguf = File.createTempFile("askai-nometa-", ".gguf");
        gguf.deleteOnExit();
        assertNull(HuggingFaceInstallPlan.readSidecar(gguf));
    }

    @Test
    public void emptyObjectIsRejected() throws Exception {
        assertInvalidSidecar("{}");
    }

    @Test
    public void missingRepositoryIdIsRejected() throws Exception {
        assertInvalidSidecar("{\"schemaVersion\":2,\"revision\":\"main\"}");
    }

    @Test
    public void unknownFutureSchemaVersionIsRejected() throws Exception {
        assertInvalidSidecar("{\"schemaVersion\":999,\"repositoryId\":\"o/m\"}");
    }

    @Test
    public void wrongFieldTypeIsRejected() throws Exception {
        assertInvalidSidecar("{\"schemaVersion\":2,\"repositoryId\":\"o/m\","
                + "\"requiredOllamaCapabilities\":\"completion\"}"); // must be an array
    }

    @Test
    public void writeThenReadRoundTripsThroughOllamaJson() throws Exception {
        File gguf = File.createTempFile("askai-rt-", ".gguf");
        gguf.deleteOnExit();
        new HuggingFaceInstallPlan("owner/model-GGUF", "abc123", "m:q4",
                Arrays.asList("TEXT", "AUDIO"), Arrays.asList("completion", "audio"), "qwen3")
                .writeSidecar(gguf);
        sidecarOf(gguf).deleteOnExit();

        HuggingFaceInstallPlan loaded = HuggingFaceInstallPlan.readSidecar(gguf);
        assertEquals("owner/model-GGUF", loaded.getRepositoryId());
        assertEquals("abc123", loaded.getRevision());
        assertEquals("qwen3", loaded.getModelType());
        assertEquals(Arrays.asList("completion", "audio"), loaded.getRequiredOllamaCapabilities());
    }

    private static void assertInvalidSidecar(String json) throws Exception {
        File gguf = File.createTempFile("askai-bad-", ".gguf");
        gguf.deleteOnExit();
        File sidecar = sidecarOf(gguf);
        sidecar.deleteOnExit();
        FileOutputStream out = new FileOutputStream(sidecar);
        out.write(json.getBytes("UTF-8"));
        out.close();
        try {
            HuggingFaceInstallPlan.readSidecar(gguf);
            fail("expected an IOException for invalid sidecar: " + json);
        } catch (IOException expected) {
            // strict validation must reject it rather than accept an empty/partial plan
        }
    }

    private static File sidecarOf(File gguf) {
        return new File(gguf.getParentFile(), gguf.getName() + ".askai-install.json");
    }

    @Test
    public void invalidSidecarThrowsInsteadOfSilentEmptyPlan() throws Exception {
        File gguf = File.createTempFile("askai-badmeta-", ".gguf");
        gguf.deleteOnExit();
        File sidecar = new File(gguf.getParentFile(), gguf.getName() + ".askai-install.json");
        sidecar.deleteOnExit();
        FileOutputStream out = new FileOutputStream(sidecar);
        out.write("this is not json".getBytes("UTF-8"));
        out.close();

        try {
            HuggingFaceInstallPlan.readSidecar(gguf);
            fail("expected an IOException for an invalid sidecar");
        } catch (IOException expected) {
            // a present-but-invalid sidecar must not be treated as an empty plan
        }
    }
}
