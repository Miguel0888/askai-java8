package com.aresstack.askai.java8.localmodels;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The ready-line contract and the restart-on-boot signal of the local runtime manager. */
public class LocalModelRuntimeManagerTest {

    @Test
    public void parsesExactlyTheMachineReadableReadyLine() {
        LocalModelRuntimeManager.ReadyLine ready = LocalModelRuntimeManager.ReadyLine.parse(
                "{\"event\":\"ready\",\"baseUrl\":\"http://127.0.0.1:49183\","
                        + "\"version\":\"askai-local-1\"}");
        assertEquals("http://127.0.0.1:49183", ready.baseUrl);
        assertEquals("askai-local-1", ready.version);

        assertNull("stderr-style logs are not the ready line",
                LocalModelRuntimeManager.ReadyLine.parse("[local-runtime] loaded model"));
        assertNull(LocalModelRuntimeManager.ReadyLine.parse(
                "{\"event\":\"other\",\"baseUrl\":\"http://127.0.0.1:1\"}"));
        assertNull(LocalModelRuntimeManager.ReadyLine.parse(
                "{\"event\":\"ready\",\"baseUrl\":\"not-a-url\"}"));
        assertNull(LocalModelRuntimeManager.ReadyLine.parse(null));
    }

    @Test
    public void hasInstalledModelsLooksForManifestDirectories() throws Exception {
        File root = Files.createTempDirectory("askai-local-mgr").toFile();
        LocalModelRuntimeManager manager = new LocalModelRuntimeManager(root);
        assertFalse(manager.hasInstalledModels());

        File model = new File(root, "cross-encoder-ms-marco-MiniLM-L-6-v2");
        assertTrue(model.mkdirs());
        assertFalse("a directory without manifest is not an installed model",
                manager.hasInstalledModels());
        Files.write(new File(model, "askai-local-model.json").toPath(),
                "{\"state\":\"RUNNABLE\"}".getBytes(Charset.forName("UTF-8")));
        assertTrue(manager.hasInstalledModels());
    }
}
