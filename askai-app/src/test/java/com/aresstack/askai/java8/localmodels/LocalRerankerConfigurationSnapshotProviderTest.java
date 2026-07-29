package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A5e/A5g: the reranker model is an EXPLICIT selection — never a first-match guess. A missing selection
 * fails even when models are installed (the ONE-TIME initial selection lives in the settings layer, not
 * here), and a removed or incompatible selection fails visibly BEFORE the runtime is even started (so no
 * process is spawned in these cases).
 */
public class LocalRerankerConfigurationSnapshotProviderTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private void installModel(File root, String dir, String virtualName, String capability)
            throws Exception {
        installModel(root, dir, virtualName, capability, null);
    }

    private void installModel(File root, String dir, String virtualName, String capability,
                              String state) throws Exception {
        File modelDir = new File(root, dir);
        assertTrue(modelDir.mkdirs());
        Files.write(new File(modelDir, "askai-local-model.json").toPath(),
                ("{\"virtualName\":\"" + virtualName + "\",\"capabilities\":[\"" + capability + "\"]"
                        + (state == null ? "" : ",\"state\":\"" + state + "\"")
                        + "}").getBytes(UTF_8));
    }

    private LocalRerankerConfigurationSnapshotProvider provider(File root) {
        return new LocalRerankerConfigurationSnapshotProvider(new LocalModelRuntimeManager(root));
    }

    @Test
    public void failsWithoutASelectionWhenNoRerankModelIsInstalled() throws Exception {
        File root = folder.newFolder("models");
        installModel(root, "chatty", "local/chat-model:latest", "chat");
        try {
            provider(root).prepareForSession("s", folder.newFolder("session"), "");
            fail("expected a visible failure with no rerank-capable model");
        } catch (RerankerConfigurationException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("no rerank-capable"));
        }
    }

    @Test
    public void failsWithoutASelectionEvenWhenExactlyOneRerankModelIsInstalled() throws Exception {
        // No silent single-model fallback here: the one-time initial selection is a persisted settings
        // migration; the provider itself only honours the explicit selection.
        File root = folder.newFolder("models");
        installModel(root, "r1", "local/cross-encoder/a:latest", "rerank");
        try {
            provider(root).prepareForSession("s", folder.newFolder("session"), null);
            fail("expected a visible failure without an explicit selection");
        } catch (RerankerConfigurationException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("no reranker model is selected"));
            assertTrue("the installed candidates are named: " + expected.getMessage(),
                    expected.getMessage().contains("local/cross-encoder/a:latest"));
        }
    }

    @Test
    public void failsWhenTheSelectedModelIsNotInstalled() throws Exception {
        File root = folder.newFolder("models");
        installModel(root, "r1", "local/cross-encoder/a:latest", "rerank");
        try {
            provider(root).prepareForSession("s", folder.newFolder("session"),
                    "local/cross-encoder/removed:latest");
            fail("expected a visible failure for a removed selection");
        } catch (RerankerConfigurationException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("local/cross-encoder/removed:latest"));
            assertTrue("the installed candidates are named: " + expected.getMessage(),
                    expected.getMessage().contains("local/cross-encoder/a:latest"));
        }
    }

    @Test
    public void failsWhenTheSelectedModelLacksTheRerankCapability() throws Exception {
        File root = folder.newFolder("models");
        installModel(root, "chatty", "local/chat-model:latest", "chat");
        installModel(root, "r1", "local/cross-encoder/a:latest", "rerank");
        try {
            provider(root).prepareForSession("s", folder.newFolder("session"),
                    "local/chat-model:latest");
            fail("expected a visible failure for a rerank-incapable selection");
        } catch (RerankerConfigurationException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("local/chat-model:latest"));
        }
    }

    @Test
    public void failsWhenTheSelectedModelIsNotInAUsableState() throws Exception {
        File root = folder.newFolder("models");
        installModel(root, "r1", "local/cross-encoder/a:latest", "rerank", "BROKEN");
        try {
            provider(root).prepareForSession("s", folder.newFolder("session"),
                    "local/cross-encoder/a:latest");
            fail("expected a visible failure for an unusable installed state");
        } catch (RerankerConfigurationException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("local/cross-encoder/a:latest"));
        }
    }

    @Test
    public void resolvesTheExplicitSelectionEvenAmongSeveralInstalledRerankers() throws Exception {
        // The former ambiguity failure is GONE for an explicit selection: with several installed
        // rerankers the persisted choice decides — deterministically, without any first-match scan.
        File root = folder.newFolder("models");
        installModel(root, "r1", "local/cross-encoder/a:latest", "rerank");
        installModel(root, "r2", "local/cross-encoder/b:latest", "rerank", "RUNNABLE");
        assertEquals("local/cross-encoder/b:latest",
                provider(root).resolveSelectedModel("local/cross-encoder/b:latest"));
        assertEquals("local/cross-encoder/a:latest",
                provider(root).resolveSelectedModel(" local/cross-encoder/a:latest "));
    }

    @Test
    public void catalogListsOnlyUsableRerankModelsSorted() throws Exception {
        File root = folder.newFolder("models");
        installModel(root, "r2", "local/cross-encoder/b:latest", "rerank");
        installModel(root, "r1", "local/cross-encoder/a:latest", "rerank", "RUNNABLE");
        installModel(root, "chatty", "local/chat-model:latest", "chat");
        installModel(root, "broken", "local/cross-encoder/broken:latest", "rerank", "BROKEN");
        File corrupt = new File(root, "corrupt");
        assertTrue(corrupt.mkdirs());
        Files.write(new File(corrupt, "askai-local-model.json").toPath(),
                "{not json".getBytes(UTF_8));
        assertEquals(java.util.Arrays.asList(
                        "local/cross-encoder/a:latest", "local/cross-encoder/b:latest"),
                new LocalRerankerModelCatalog(new LocalModelRuntimeManager(root))
                        .listInstalledRerankModels());
    }
}
