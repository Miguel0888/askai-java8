package com.aresstack.askai.java8.ollamalib;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OllamaLibraryHtmlParserTest {

    private final OllamaLibraryHtmlParser parser = new OllamaLibraryHtmlParser();

    private String fixture(String name) throws IOException {
        InputStream in = getClass().getResourceAsStream("/ollama/" + name);
        assertNotNull("fixture missing: " + name, in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private OllamaLibraryModel find(List<OllamaLibraryModel> models, String base) {
        for (OllamaLibraryModel model : models) {
            if (model.getBaseName().equals(base)) {
                return model;
            }
        }
        return null;
    }

    private OllamaModelVariant variant(List<OllamaModelVariant> variants, String tag) {
        for (OllamaModelVariant v : variants) {
            if (v.getTag().equals(tag)) {
                return v;
            }
        }
        return null;
    }

    @Test
    public void parsesSearchResults() throws IOException {
        List<OllamaLibraryModel> models = parser.parseSearchResults(fixture("search-devstral.html"));
        assertFalse("expected search results", models.isEmpty());

        OllamaLibraryModel devstral = find(models, "devstral-small-2");
        assertNotNull("devstral-small-2 result missing", devstral);
        assertTrue("description present", devstral.getDescription().length() > 10);
        assertTrue("capability vision", devstral.getCapabilities().contains("vision"));
        assertTrue("capability tools", devstral.getCapabilities().contains("tools"));
        assertTrue("param size 24b", devstral.getParameterSizes().contains("24b"));
        assertTrue("pulls present", devstral.getPullsText().length() > 0);

        // No capability should be a stats label leaking through.
        assertFalse(devstral.getCapabilities().contains("pulls"));
        assertFalse(devstral.getCapabilities().contains("tags"));
        assertFalse(devstral.getCapabilities().contains("updated"));
    }

    @Test
    public void parsesModelVariants() throws IOException {
        List<OllamaModelVariant> variants =
                parser.parseModelVariants("devstral-small-2", fixture("library-devstral-small-2.html"));
        assertFalse("expected variants", variants.isEmpty());

        assertNotNull(variant(variants, "devstral-small-2:latest"));
        OllamaModelVariant b24 = variant(variants, "devstral-small-2:24b");
        assertNotNull("24b variant missing", b24);
        assertEquals("24b", b24.getShortTag());
        assertTrue("size present: " + b24.getSize(), b24.getSize().toLowerCase().endsWith("gb"));
        assertTrue("context present: " + b24.getContextWindow(),
                b24.getContextWindow().toLowerCase().contains("context"));
        assertFalse("24b is not cloud", b24.isCloud());

        OllamaModelVariant cloud = variant(variants, "devstral-small-2:24b-cloud");
        assertNotNull("cloud variant missing", cloud);
        assertTrue("cloud flagged", cloud.isCloud());
    }

    @Test
    public void changedHtmlThrowsClearError() throws IOException {
        try {
            parser.parseSearchResults(fixture("changed.html"));
            fail("expected a drift error for changed HTML");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("geändert"));
        }
    }
}
