package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A5e: resolving the rerank-capable local model is explicit — no silent "first found" fallback. Zero
 * usable rerank models, or an ambiguous set of several, fails visibly BEFORE the runtime is even
 * started (so no process is spawned in these cases).
 */
public class LocalRerankerConfigurationSnapshotProviderTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private void installModel(File root, String dir, String virtualName, String capability)
            throws Exception {
        File modelDir = new File(root, dir);
        assertTrue(modelDir.mkdirs());
        Files.write(new File(modelDir, "askai-local-model.json").toPath(),
                ("{\"virtualName\":\"" + virtualName + "\",\"capabilities\":[\"" + capability
                        + "\"]}").getBytes(UTF_8));
    }

    private LocalRerankerConfigurationSnapshotProvider provider(File root) {
        return new LocalRerankerConfigurationSnapshotProvider(new LocalModelRuntimeManager(root));
    }

    @Test
    public void failsWhenNoRerankModelIsInstalled() throws Exception {
        File root = folder.newFolder("models");
        installModel(root, "chatty", "local/chat-model:latest", "chat");
        try {
            provider(root).prepareForSession("s", folder.newFolder("session"));
            fail("expected a visible failure with no rerank-capable model");
        } catch (RerankerConfigurationException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("no rerank-capable"));
        }
    }

    @Test
    public void failsWhenSeveralRerankModelsAreInstalled() throws Exception {
        File root = folder.newFolder("models");
        installModel(root, "r1", "local/cross-encoder/a:latest", "rerank");
        installModel(root, "r2", "local/cross-encoder/b:latest", "rerank");
        try {
            provider(root).prepareForSession("s", folder.newFolder("session"));
            fail("expected a visible failure with an ambiguous rerank model set");
        } catch (RerankerConfigurationException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("several"));
        }
    }
}
