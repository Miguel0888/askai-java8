package com.aresstack.askai.java8.client;

import java.util.Collections;
import java.util.List;

/**
 * Rich model information returned by Ollama /api/show, in AskAI domain terms.
 */
public final class OllamaModelInfoView {

    private final OllamaModelDetails details;
    private final String template;
    private final String system;
    private final String parameters;
    private final String modelfile;
    private final List<String> capabilities;

    public OllamaModelInfoView(OllamaModelDetails details, String template, String system,
                               String parameters, String modelfile, List<String> capabilities) {
        this.details = details == null ? OllamaModelDetails.empty() : details;
        this.template = safe(template);
        this.system = safe(system);
        this.parameters = safe(parameters);
        this.modelfile = safe(modelfile);
        this.capabilities = capabilities == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(capabilities);
    }

    public OllamaModelDetails getDetails() {
        return details;
    }

    public String getTemplate() {
        return template;
    }

    public String getSystem() {
        return system;
    }

    public String getParameters() {
        return parameters;
    }

    public String getModelfile() {
        return modelfile;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
