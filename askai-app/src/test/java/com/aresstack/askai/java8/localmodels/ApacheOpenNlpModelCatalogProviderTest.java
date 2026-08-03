package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.agent.model.nlp.NlpCapability;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The curated catalog offers exactly the official de/en 1.5 sentence detectors with correct metadata + pins. */
public class ApacheOpenNlpModelCatalogProviderTest {

    @Test
    public void offersExactlyDeAndEnSentenceDetectors() {
        List<NlpModelCatalogEntry> models = new ApacheOpenNlpModelCatalogProvider().availableModels();
        assertEquals(2, models.size());
        for (NlpModelCatalogEntry e : models) {
            assertEquals(NlpCapability.SENTENCE_DETECTION, e.getCapability());
            assertEquals("opennlp", e.getImplementation());
            assertEquals("1.9.4", e.getCompatibleRuntime());
            assertTrue("pinned sha", e.getExpectedSha256().length() == 64);
            assertTrue("pinned size", e.getExpectedSize() > 0);
            assertTrue("curated SourceForge 1.5 source",
                    e.getSourceUrl().contains("opennlp/files/models-1.5/"));
        }
        assertEquals("de", models.get(0).getLanguageCode());
        assertEquals("en", models.get(1).getLanguageCode());
        assertEquals("de-sent.bin", models.get(0).getArtifactFileName());
        assertEquals("en-sent.bin", models.get(1).getArtifactFileName());
    }
}
