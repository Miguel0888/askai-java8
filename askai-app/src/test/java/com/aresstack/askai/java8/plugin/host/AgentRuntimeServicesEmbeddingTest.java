package com.aresstack.askai.java8.plugin.host;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshotProvider;
import com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The host service map publishes the EMBEDDING snapshot provider whenever a local model runtime is present (the
 * continuous knowledge pipeline resolves its vector world through it), and omits it — rather than a broken stub
 * — when there is none, so a host without a local runtime simply has no embedding capability.
 */
public class AgentRuntimeServicesEmbeddingTest {

    @Test
    public void publishesTheEmbeddingProviderWhenALocalRuntimeIsPresent() {
        AgentRuntimeServices services = new AgentRuntimeServices(
                new LocalModelRuntimeManager(new File("build/tmp/does-not-matter")));
        try {
            assertNotNull("the embedding snapshot provider is published to plugins",
                    services.asServiceMap().get(EmbeddingConfigurationSnapshotProvider.class));
        } finally {
            services.shutdown();
        }
    }

    @Test
    public void omitsTheEmbeddingProviderWithoutALocalRuntime() {
        AgentRuntimeServices services = new AgentRuntimeServices(null);
        try {
            assertNull(services.asServiceMap().get(EmbeddingConfigurationSnapshotProvider.class));
        } finally {
            services.shutdown();
        }
    }
}
