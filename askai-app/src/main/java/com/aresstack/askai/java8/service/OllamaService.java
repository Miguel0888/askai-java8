package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.client.OllamaChatTurn;
import com.aresstack.askai.java8.client.OllamaModelInfoView;
import com.aresstack.askai.java8.client.OllamaModelInfo;
import com.aresstack.askai.java8.client.OllamaPullProgress;
import com.aresstack.askai.java8.client.OllamaRunningModelInfo;

import java.util.List;

/**
 * UI-facing Ollama service boundary. Swing panels depend on this interface,
 * not on HTTP clients, JSON, or a concrete Ollama library. It exposes the full
 * set of operations AskAI wires from {@code ollama4j}.
 */
public interface OllamaService {

    Task listModelNames(ModelNamesListener listener);

    Task listInstalledModels(InstalledModelsListener listener);

    Task listRunningModels(RunningModelsListener listener);

    Task getServerVersion(ServerVersionListener listener);

    Task ping(ActionListener listener);

    Task getModelInfo(String modelName, ModelInfoListener listener);

    Task deleteModel(String modelName, ActionListener listener);

    Task unloadModel(String modelName, ActionListener listener);

    Task pullModel(String modelName, PullListener listener);

    Task generate(String modelName, String prompt, ActionListener listener);

    Task embed(String modelName, String input, EmbedListener listener);

    Task streamChat(ChatRequest request, ChatListener listener);

    interface Task {
        void cancel();
    }

    interface ModelNamesListener extends FailureListener {
        void onModelNames(List<String> names);
    }

    interface InstalledModelsListener extends FailureListener {
        void onInstalledModels(List<OllamaModelInfo> models);
    }

    interface RunningModelsListener extends FailureListener {
        void onRunningModels(List<OllamaRunningModelInfo> models);
    }

    interface ServerVersionListener extends FailureListener {
        void onServerVersion(String version);
    }

    interface ModelInfoListener extends FailureListener {
        void onModelInfo(OllamaModelInfoView info);
    }

    interface ActionListener extends FailureListener {
        void onComplete(String message);
    }

    interface PullListener extends FailureListener {
        void onProgress(OllamaPullProgress progress);

        void onComplete(String message);
    }

    interface EmbedListener extends FailureListener {
        void onEmbedding(int vectorCount, int dimensions);
    }

    interface ChatListener extends FailureListener {
        void onContent(String content);

        void onStatus(String status);

        void onComplete(ChatResult result);
    }

    interface FailureListener {
        void onError(Exception ex);
    }

    final class ChatRequest {
        private final String modelName;
        private final String keepAlive;
        private final List<OllamaChatTurn> messages;

        public ChatRequest(String modelName, String keepAlive, List<OllamaChatTurn> messages) {
            this.modelName = modelName;
            this.keepAlive = keepAlive;
            this.messages = messages;
        }

        public String getModelName() {
            return modelName;
        }

        public String getKeepAlive() {
            return keepAlive;
        }

        public List<OllamaChatTurn> getMessages() {
            return messages;
        }
    }

    final class ChatResult {
        private final String fallbackText;
        private final long evalCount;
        private final long evalDurationNanos;

        public ChatResult(String fallbackText, long evalCount, long evalDurationNanos) {
            this.fallbackText = fallbackText == null ? "" : fallbackText;
            this.evalCount = evalCount;
            this.evalDurationNanos = evalDurationNanos;
        }

        public String getFallbackText() {
            return fallbackText;
        }

        public long getEvalCount() {
            return evalCount;
        }

        public long getEvalDurationNanos() {
            return evalDurationNanos;
        }

        public boolean hasMetrics() {
            return evalCount > 0L && evalDurationNanos > 0L;
        }

        public double tokensPerSecond() {
            if (!hasMetrics()) {
                return 0.0d;
            }
            return evalCount / (evalDurationNanos / 1_000_000_000.0d);
        }
    }
}
