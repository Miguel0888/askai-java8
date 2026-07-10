package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.hf.HuggingFaceFile;
import com.aresstack.askai.java8.hf.HuggingFaceModel;
import io.github.ollama4j.models.Model;
import io.github.ollama4j.models.PullProgress;

import java.io.File;
import java.util.List;

public interface AskAiService {

    void listModels(ModelListListener listener);

    void sendChat(ChatRequest request, ChatListener listener);

    void pullOllamaModel(String modelName, PullListener listener);

    void searchHuggingFaceModels(String query, HuggingFaceModelListener listener);

    void listHuggingFaceFiles(String modelId, HuggingFaceFileListener listener);

    void downloadHuggingFaceFile(HuggingFaceFile file, DownloadListener listener);

    InstallTask installGgufFile(String modelName, File ggufFile, InstallListener listener);

    void shutdown();

    /** Handle to a running install; {@link #cancel()} aborts the upload/create. */
    interface InstallTask {
        void cancel();
    }

    interface ModelListListener {
        void onModels(List<Model> models);

        void onError(Exception ex);
    }

    interface ChatListener {
        void onToken(String token);

        void onComplete(ChatSummary summary);

        void onError(Exception ex);
    }

    interface PullListener {
        void onProgress(PullProgress progress);

        void onComplete(String message);

        void onError(Exception ex);
    }

    interface HuggingFaceModelListener {
        void onModels(List<HuggingFaceModel> models);

        void onError(Exception ex);
    }

    interface HuggingFaceFileListener {
        void onFiles(List<HuggingFaceFile> files);

        void onError(Exception ex);
    }

    interface DownloadListener {
        void onProgress(long completed, long total);

        void onComplete(File file);

        void onError(Exception ex);
    }

    interface ActionListener {
        void onComplete(String message);

        void onError(Exception ex);
    }

    interface InstallListener {
        /**
         * Report install progress.
         *
         * @param phase     human-readable phase, e.g. "Hashing", "Uploading", or an Ollama status
         * @param completed bytes done in this phase, or 0 when not measurable
         * @param total     total bytes for this phase, or 0 for an indeterminate step
         */
        void onProgress(String phase, long completed, long total);

        void onComplete(String message);

        void onError(Exception ex);
    }
}
