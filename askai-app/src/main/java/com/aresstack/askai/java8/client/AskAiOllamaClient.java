package com.aresstack.askai.java8.client;

import io.github.ollama4j.Ollama;
import io.github.ollama4j.exceptions.OllamaException;
import io.github.ollama4j.models.ChatCompletion;
import io.github.ollama4j.models.ChatMessage;
import io.github.ollama4j.models.ChatTokenListener;
import io.github.ollama4j.models.EmbeddingResult;
import io.github.ollama4j.models.Model;
import io.github.ollama4j.models.ModelDetails;
import io.github.ollama4j.models.ModelInfo;
import io.github.ollama4j.models.PullProgress;
import io.github.ollama4j.models.PullProgressListener;
import io.github.ollama4j.models.RunningModel;

import java.util.ArrayList;
import java.util.List;

/**
 * The sole adapter between AskAI and the embedded Java 8 {@code ollama4j-java8} library, mirroring
 * the original AskAI {@code AskAiOllamaClient} (which wrapped ollama4j 1.1.7). It maps library DTOs
 * into the AskAI domain models so the UI and services never depend on the library directly.
 *
 * <p>Where the lean Java 8 library lacks data the original library provided (e.g. model
 * capabilities from {@code /api/show}, per-process model details from {@code /api/ps}), the mapped
 * value is empty rather than failing — the UI renders what is available.</p>
 */
public final class AskAiOllamaClient {

    private static final long REQUEST_TIMEOUT_SECONDS = 6L * 60L * 60L;

    private final Ollama ollama;

    public AskAiOllamaClient(String baseUrl) {
        this.ollama = new Ollama(baseUrl);
        this.ollama.setRequestTimeoutSeconds(REQUEST_TIMEOUT_SECONDS);
    }

    public boolean ping() throws OllamaRequestException {
        try {
            return ollama.ping();
        } catch (OllamaException ex) {
            throw wrap("ping", ex);
        }
    }

    public String getVersion() throws OllamaRequestException {
        try {
            return ollama.getVersion();
        } catch (OllamaException ex) {
            throw wrap("version", ex);
        }
    }

    public List<OllamaModelInfo> getInstalledModels() throws OllamaRequestException {
        try {
            List<Model> models = ollama.listModels();
            List<OllamaModelInfo> mapped = new ArrayList<OllamaModelInfo>();
            for (Model model : models) {
                mapped.add(toModelInfo(model));
            }
            return mapped;
        } catch (OllamaException ex) {
            throw wrap("list models", ex);
        }
    }

    public List<String> getModelNames() throws OllamaRequestException {
        List<String> names = new ArrayList<String>();
        for (OllamaModelInfo info : getInstalledModels()) {
            names.add(info.getDisplayName());
        }
        return names;
    }

    public List<OllamaRunningModelInfo> getRunningModels() throws OllamaRequestException {
        try {
            List<RunningModel> running = ollama.ps();
            List<OllamaRunningModelInfo> mapped = new ArrayList<OllamaRunningModelInfo>();
            for (RunningModel model : running) {
                mapped.add(new OllamaRunningModelInfo(
                        model.getName(),
                        model.getName(),
                        model.getExpiresAt(),
                        model.getSize(),
                        model.getSizeVram(),
                        OllamaModelDetails.empty()));
            }
            return mapped;
        } catch (OllamaException ex) {
            throw wrap("list running models", ex);
        }
    }

    public OllamaModelInfoView getModelInfo(String modelName) throws OllamaRequestException {
        try {
            ModelInfo info = ollama.getModelDetails(modelName);
            return new OllamaModelInfoView(
                    toDetails(info.getDetails()),
                    info.getTemplate(),
                    "",
                    info.getParameters(),
                    info.getModelFile(),
                    new ArrayList<String>());
        } catch (OllamaException ex) {
            throw wrap("model info for " + modelName, ex);
        }
    }

    public void deleteModel(String modelName) throws OllamaRequestException {
        try {
            ollama.deleteModel(modelName);
        } catch (OllamaException ex) {
            throw wrap("delete " + modelName, ex);
        }
    }

    public void unloadModel(String modelName) throws OllamaRequestException {
        try {
            ollama.unloadModel(modelName);
        } catch (OllamaException ex) {
            throw wrap("unload " + modelName, ex);
        }
    }

    public void pullModel(String modelName, final OllamaPullListener listener) throws OllamaRequestException {
        try {
            ollama.pullModel(modelName, new PullProgressListener() {
                public void onProgress(PullProgress progress) {
                    if (listener != null) {
                        listener.onProgress(new OllamaPullProgress(
                                progress.getStatus(), progress.getCompleted(), progress.getTotal()));
                    }
                }
            });
        } catch (OllamaException ex) {
            throw wrap("pull " + modelName, ex);
        }
    }

    public String generate(String modelName, String prompt) throws OllamaRequestException {
        try {
            return ollama.generate(modelName, prompt);
        } catch (OllamaException ex) {
            throw wrap("generate with " + modelName, ex);
        }
    }

    public List<List<Double>> embed(String modelName, List<String> inputs) throws OllamaRequestException {
        try {
            EmbeddingResult result = ollama.embed(modelName, inputs);
            return result.getEmbeddings();
        } catch (OllamaException ex) {
            throw wrap("embed with " + modelName, ex);
        }
    }

    public OllamaChatCompletion streamChat(String modelName, List<OllamaChatTurn> conversation, String keepAlive,
                                           final OllamaChatStreamListener listener) throws OllamaRequestException {
        try {
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            for (OllamaChatTurn turn : conversation) {
                messages.add(toChatMessage(turn));
            }
            ChatCompletion completion = ollama.streamChat(modelName, messages, keepAlive, new ChatTokenListener() {
                public void onToken(String token) {
                    if (listener != null) {
                        listener.onContent(token);
                    }
                }
            });
            OllamaChatCompletion mapped = new OllamaChatCompletion(
                    completion.getContent(), completion.getEvalCount(), completion.getEvalDurationNanos());
            if (listener != null) {
                listener.onComplete(mapped);
            }
            return mapped;
        } catch (OllamaException ex) {
            throw wrap("chat with " + modelName, ex);
        }
    }

    private static ChatMessage toChatMessage(OllamaChatTurn turn) {
        if (turn.isSystem()) {
            return ChatMessage.system(turn.getContent());
        }
        if (turn.isAssistant()) {
            return ChatMessage.assistant(turn.getContent());
        }
        return ChatMessage.user(turn.getContent());
    }

    private static OllamaModelInfo toModelInfo(Model model) {
        return new OllamaModelInfo(
                model.getName(),
                model.getName(),
                model.getModifiedAt(),
                model.getSize(),
                model.getDigest(),
                toDetails(model.getDetails()));
    }

    private static OllamaModelDetails toDetails(ModelDetails details) {
        if (details == null) {
            return OllamaModelDetails.empty();
        }
        return new OllamaModelDetails(
                details.getFormat(),
                details.getFamily(),
                details.getParameterSize(),
                details.getQuantizationLevel());
    }

    private static OllamaRequestException wrap(String operation, OllamaException ex) {
        return new OllamaRequestException("Ollama request failed (" + operation + "): " + ex.getMessage(), ex);
    }
}
