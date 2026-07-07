package com.aresstack.askai.java8.hf;

public final class HuggingFaceFile {

    private final String modelId;
    private final String path;
    private final long size;

    public HuggingFaceFile(String modelId, String path, long size) {
        this.modelId = modelId == null ? "" : modelId;
        this.path = path == null ? "" : path;
        this.size = size;
    }

    public String getModelId() {
        return modelId;
    }

    public String getPath() {
        return path;
    }

    public long getSize() {
        return size;
    }

    public String getFileName() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public boolean isGguf() {
        return path.toLowerCase().endsWith(".gguf");
    }

    public String toString() {
        return path;
    }
}
