package com.aresstack.askai.java8.hf;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
    public void missingSidecarIsNull() throws Exception {
        File gguf = File.createTempFile("askai-nometa-", ".gguf");
        gguf.deleteOnExit();
        assertNull(HuggingFaceInstallPlan.readSidecar(gguf));
    }
}
