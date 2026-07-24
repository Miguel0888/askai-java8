package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.hf.HuggingFaceFile;
import com.aresstack.askai.java8.hf.HuggingFaceSearchResult;
import com.aresstack.askai.java8.hf.ModelSearchCriteria;
import com.aresstack.askai.java8.hf.convert.RepositoryAnalysis;
import com.aresstack.askai.java8.hf.convert.SupportDecision;
import io.github.ollama4j.models.Model;
import io.github.ollama4j.models.PullProgress;

import java.io.File;
import java.util.List;

public interface AskAiService {

    void listModels(ModelListListener listener);

    void sendChat(ChatRequest request, ChatListener listener);

    void pullOllamaModel(String modelName, PullListener listener);

    void searchHuggingFaceModels(ModelSearchCriteria criteria, HuggingFaceSearchListener listener);

    /** Continues pagination from a previous search/load-more result (see {@link HuggingFaceSearchResult}). */
    void loadMoreHuggingFaceModels(ModelSearchCriteria criteria, HuggingFaceSearchResult previous,
                                   HuggingFaceSearchListener listener);

    void listHuggingFaceFiles(String modelId, HuggingFaceFileListener listener);

    /**
     * Analyzes a repository's files + config.json and classifies its import support via the
     * ConverterService (spec §17/§18), off the UI thread.
     */
    void analyzeRepository(String modelId, RepositoryAnalysisListener listener);

    void downloadHuggingFaceFile(HuggingFaceFile file, DownloadListener listener);

    InstallTask installGgufFile(String modelName, File ggufFile, InstallListener listener);

    /**
     * Install a GGUF model together with companion files (e.g. the *mmproj* audio/vision encoder)
     * in a single create, so multimodal models arrive complete.
     */
    InstallTask installGgufFileWithCompanions(String modelName, File ggufFile,
                                              List<File> companionFiles, InstallListener listener);

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

    interface HuggingFaceSearchListener {
        void onResult(HuggingFaceSearchResult result);

        void onError(Exception ex);
    }

    interface HuggingFaceFileListener {
        void onFiles(List<HuggingFaceFile> files);

        void onError(Exception ex);
    }

    interface RepositoryAnalysisListener {
        void onDecision(SupportDecision decision, RepositoryAnalysis analysis);

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
