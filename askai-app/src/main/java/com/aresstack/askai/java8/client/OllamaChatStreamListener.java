package com.aresstack.askai.java8.client;

/**
 * Receives incremental text and final metadata from an Ollama chat stream.
 */
public interface OllamaChatStreamListener {

    void onContent(String content);

    void onStatus(String status);

    void onComplete(OllamaChatCompletion completion);
}
