package io.github.ollama4j.models;

public final class ModelInfo {

    private final String modelFile;
    private final String parameters;
    private final String template;
    private final ModelDetails details;

    public ModelInfo(String modelFile, String parameters, String template, ModelDetails details) {
        this.modelFile = modelFile == null ? "" : modelFile;
        this.parameters = parameters == null ? "" : parameters;
        this.template = template == null ? "" : template;
        this.details = details == null ? ModelDetails.empty() : details;
    }

    public String getModelFile() {
        return modelFile;
    }

    public String getParameters() {
        return parameters;
    }

    public String getTemplate() {
        return template;
    }

    public ModelDetails getDetails() {
        return details;
    }
}
