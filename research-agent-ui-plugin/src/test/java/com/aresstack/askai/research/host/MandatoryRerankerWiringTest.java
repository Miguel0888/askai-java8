package com.aresstack.askai.research.host;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A5e/A5f: the productive backend always hands the agent the mandatory reranker snapshot path, and a
 * session cannot start without a reranker snapshot provider — there is no reranker-less browser run.
 */
public class MandatoryRerankerWiringTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void agentLaunchEnvironmentCarriesBothConfigs() {
        Map<String, String> env = ProductiveResearchBackendFactory.agentLaunchEnvironment(
                "/tmp/search.json", "/tmp/reranker.json", "/tmp/inference.json");
        assertEquals("/tmp/search.json", env.get("ASKAI_BROWSER_SEARCH_CONFIG"));
        assertEquals("the mandatory reranker snapshot reaches the agent launch environment",
                "/tmp/reranker.json", env.get("ASKAI_RERANKER_CONFIG"));
        assertEquals("the optional inference descriptor reaches the agent launch environment",
                "/tmp/inference.json", env.get("ASKAI_INFERENCE_CONFIG"));
    }

    @Test
    public void agentLaunchEnvironmentOmitsInferenceWhenAbsent() {
        Map<String, String> env = ProductiveResearchBackendFactory.agentLaunchEnvironment(
                "/tmp/search.json", "/tmp/reranker.json", "");
        assertTrue("no inference descriptor → the key is omitted entirely (honest fallback)",
                !env.containsKey("ASKAI_INFERENCE_CONFIG"));
    }

    @Test
    public void createSessionWithoutARerankerProviderFailsVisibly() throws Exception {
        File exe = folder.newFile("java");
        File jar = folder.newFile("agent.jar");
        File sidecarJava = folder.newFile("java21");
        File sidecarJar = folder.newFile("sidecar.jar");
        ResearchRuntimeConfig config = new ResearchRuntimeConfig(exe.getAbsolutePath(),
                jar.getAbsolutePath(), sidecarJava.getAbsolutePath(), sidecarJar.getAbsolutePath(),
                "chrome", true, true, null);
        // A null provider models "the host did not publish the mandatory reranker service".
        ProductiveResearchBackendFactory factory = new ProductiveResearchBackendFactory(
                null, null, null, config, 1L, null);
        try {
            factory.createSession("s1", folder.newFolder("session"));
            fail("expected a visible failure without a reranker snapshot provider");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("reranker"));
        }
    }
}
