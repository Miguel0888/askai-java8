package com.aresstack.askai.java8.hf;

public final class HuggingFaceModel {

    private final String id;
    private final String pipelineTag;
    private final long downloads;
    private final long likes;

    public HuggingFaceModel(String id, String pipelineTag, long downloads, long likes) {
        this.id = id == null ? "" : id;
        this.pipelineTag = pipelineTag == null ? "" : pipelineTag;
        this.downloads = downloads;
        this.likes = likes;
    }

    public String getId() {
        return id;
    }

    public String getPipelineTag() {
        return pipelineTag;
    }

    public long getDownloads() {
        return downloads;
    }

    public long getLikes() {
        return likes;
    }

    public String toString() {
        return id;
    }
}
