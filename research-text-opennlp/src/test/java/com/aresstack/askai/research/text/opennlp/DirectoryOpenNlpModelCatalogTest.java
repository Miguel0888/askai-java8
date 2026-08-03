package com.aresstack.askai.research.text.opennlp;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The productive catalog resolves sentence models by LOCAL FILE convention only ({@code sentence-<key>.bin}
 * under a configurable directory) — proving there is no network access and no random default: an installed
 * artifact is found, everything else is an expected empty.
 */
public class DirectoryOpenNlpModelCatalogTest {

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("askai-opennlp-models").toFile();
    }

    @Test
    public void findsAnInstalledArtifactByConvention() throws IOException {
        File dir = tempDir();
        File de = new File(dir, "sentence-de.bin");
        Files.write(de.toPath(), new byte[]{1, 2, 3});
        DirectoryOpenNlpModelCatalog catalog = new DirectoryOpenNlpModelCatalog(dir);

        Optional<File> resolved = catalog.sentenceModel("DE");
        assertTrue(resolved.isPresent());
        assertEquals(de.getAbsolutePath(), resolved.get().getAbsolutePath());
    }

    @Test
    public void anUninstalledLanguageIsAnExpectedEmpty() throws IOException {
        File dir = tempDir();
        Files.write(new File(dir, "sentence-de.bin").toPath(), new byte[]{1});
        DirectoryOpenNlpModelCatalog catalog = new DirectoryOpenNlpModelCatalog(dir);

        assertFalse("no en model is installed", catalog.sentenceModel("en").isPresent());
        assertFalse("an unknown language invents nothing", catalog.sentenceModel("xx").isPresent());
        assertFalse("a blank key resolves to nothing", catalog.sentenceModel("  ").isPresent());
    }

    @Test
    public void aMissingDirectoryIsAnExpectedEmpty() {
        DirectoryOpenNlpModelCatalog catalog =
                new DirectoryOpenNlpModelCatalog(new File("build/tmp/does-not-exist-opennlp"));
        assertFalse(catalog.sentenceModel("en").isPresent());
    }

    @Test
    public void exposesTheArtifactNamingConvention() {
        assertEquals("sentence-en.bin", DirectoryOpenNlpModelCatalog.fileName("EN"));
        assertEquals("sentence-de.bin", DirectoryOpenNlpModelCatalog.fileName("de"));
    }
}
