package com.aresstack.askai.java8.hf.meta;

import com.aresstack.askai.java8.hf.HuggingFaceInstallPlan;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/** Maps trusted HF metadata into /api/create, with source/confidence conflict resolution. */
public class HuggingFaceMetadataLoaderTest {

    private static HuggingFaceInstallPlan plan(String modelType) {
        return new HuggingFaceInstallPlan("owner/model-GGUF", "main", "m:q4",
                Arrays.asList("TEXT"), Arrays.asList("completion"), modelType);
    }

    @Test
    public void mapsStructuredConfigAndGenerationFields() {
        FakeGateway gw = new FakeGateway();
        gw.files.put("config.json", "{\"model_type\":\"qwen3\",\"max_position_embeddings\":32768,\"hidden_size\":4096}");
        gw.files.put("generation_config.json",
                "{\"temperature\":0.7,\"top_p\":0.9,\"top_k\":40,\"repetition_penalty\":1.05,"
                        + "\"max_new_tokens\":512,\"do_sample\":true,\"eos_token_id\":128001}");

        OllamaCreateMetadata metadata = new HuggingFaceMetadataLoader(gw).load(plan("qwen3"), "model-Q4_K_M.gguf");
        Map<String, Object> info = metadata.toInfoMap();
        assertEquals("qwen3", info.get("model_family"));
        assertEquals(32768, info.get("context_length"));
        assertEquals(4096, info.get("embedding_length"));
        // A name-only quant is MEDIUM → not sent without a structured confirmation.
        assertFalse(info.containsKey("quantization_level"));

        Map<String, Object> parameters = metadata.parameters();
        assertEquals(0.7, parameters.get("temperature"));
        assertEquals(0.9, parameters.get("top_p"));
        assertEquals(40, parameters.get("top_k"));
        assertEquals(1.05, parameters.get("repeat_penalty"));
        assertEquals(512, parameters.get("num_predict"));
        // Transformers-internal flags and token ids must not leak through.
        assertFalse(parameters.containsKey("do_sample"));
        assertFalse(parameters.containsKey("eos_token_id"));
    }

    @Test
    public void ggufMetadataConfirmsQuantizationAndParameterSize() {
        FakeGateway gw = new FakeGateway();
        Map<String, Object> gguf = new LinkedHashMap<String, Object>();
        gguf.put("quantization_level", "Q4_K_M");
        gguf.put("total", 8030000000L);
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("gguf", gguf);
        gw.modelInfo = info;

        OllamaCreateMetadata metadata = new HuggingFaceMetadataLoader(gw).load(plan(""), "renamed.gguf");
        Map<String, Object> map = metadata.toInfoMap();
        assertEquals("Q4_K_M", map.get("quantization_level")); // structured GGUF value is HIGH → sent
        assertEquals("8B", map.get("parameter_size"));
    }

    @Test
    public void licenseFromCardDataIsSent() {
        FakeGateway gw = new FakeGateway();
        Map<String, Object> card = new LinkedHashMap<String, Object>();
        card.put("license", "apache-2.0");
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("cardData", card);
        gw.modelInfo = info;

        OllamaCreateMetadata metadata = new HuggingFaceMetadataLoader(gw).load(plan(""), "m.gguf");
        assertEquals(Collections.singletonList("apache-2.0"), metadata.licenses());
    }

    @Test
    public void noMetadataDegradesToCapabilitiesAndFamily() {
        OllamaCreateMetadata metadata = new HuggingFaceMetadataLoader(new FakeGateway())
                .load(plan("gemma4"), "m.gguf");
        assertEquals(Arrays.asList("completion"), metadata.capabilities());
        assertEquals("gemma4", metadata.toInfoMap().get("model_family"));
        assertNull(metadata.toInfoMap().get("context_length"));
    }

    @Test
    public void formatsParameterSizes() {
        assertEquals("8B", HuggingFaceMetadataLoader.formatParameterSize(8030000000L));
        assertEquals("270M", HuggingFaceMetadataLoader.formatParameterSize(270000000L));
        assertEquals("1.5B", HuggingFaceMetadataLoader.formatParameterSize(1500000000L));
        assertNull(HuggingFaceMetadataLoader.formatParameterSize(0L));
    }

    /** In-memory gateway: config/generation files by name, one model-info map. */
    private static final class FakeGateway implements HuggingFaceMetadataGateway {
        final Map<String, String> files = new LinkedHashMap<String, String>();
        Map<String, Object> modelInfo = new LinkedHashMap<String, Object>();

        public String fetchFile(String repositoryId, String revision, String path) {
            return files.get(path);
        }

        public Map<String, Object> fetchModelInfo(String repositoryId, String revision) {
            return modelInfo;
        }
    }
}
