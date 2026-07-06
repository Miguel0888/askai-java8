package com.aresstack.askai.java8.service;

import io.github.ollama4j.models.Model;

import java.util.List;

public interface AskAiService {

    void listModels(ModelListListener listener);

    void sendChat(ChatRequest request, ChatListener listener);

    void shutdown();

    interface ModelListListener {
        void onModels(List<Model> models);

        void onError(Exception ex);
    }

    interface ChatListener {
        void onToken(String token);

        void onComplete(ChatSummary summary);

        void onError(Exception ex);
    }
}
