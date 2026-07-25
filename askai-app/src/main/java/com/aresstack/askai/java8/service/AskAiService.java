package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.hf.HuggingFaceFile;
import com.aresstack.askai.java8.hf.HuggingFaceSearchResult;
import com.aresstack.askai.java8.hf.ModelSearchCriteria;
import com.aresstack.askai.java8.hf.catalog.CatalogBundle;
import com.aresstack.askai.java8.hf.convert.RepositoryAnalysis;
import com.aresstack.askai.java8.hf.convert.SupportDecision;
import com.aresstack.askai.java8.ollamalib.OllamaLibraryModel;
import com.aresstack.askai.java8.ollamalib.OllamaModelVariant;
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

    /**
     * Loads the filter catalogs in the order live → cache → bundled fallback, off the UI thread, and
     * returns a bundle carrying the catalogs, their origin and per-group counts.
     */
    void loadFilterCatalogs(boolean forceLive, FilterCatalogListener listener);

    /** Searches the Ollama library (ollama.com) via HTML scraping, off the UI thread. */
    void searchOllamaLibrary(String query, OllamaLibraryListener listener);

    /** Loads the installable tag variants of an Ollama library model, off the UI thread. */
    void loadOllamaVariants(String baseName, OllamaVariantsListener listener);

    void downloadHuggingFaceFile(HuggingFaceFile file, DownloadListener listener);

    InstallTask installGgufFile(String modelName, File ggufFile, InstallListener listener);

    /**
     * Install a GGUF model together with companion files (e.g. the *mmproj* audio/vision encoder)
     * in a single create, so multimodal models arrive complete.
     */
    InstallTask installGgufFileWithCompanions(String modelName, File ggufFile,
                                              List<File> companionFiles, InstallListener listener);

    /**
     * Like {@link #installGgufFileWithCompanions(String, File, List, InstallListener)} but declares the
     * capabilities the install must yield (e.g. {@code ["audio"]} / {@code ["vision"]} for a targeted
     * encoder provisioning). After a successful {@code /api/create} the model is verified via
     * {@code /api/show}; {@link InstallListener#onVerified(VerificationResult)} reports whether those
     * required capabilities were actually confirmed by Ollama.
     */
    InstallTask installGgufFileWithCompanions(String modelName, File ggufFile, List<File> companionFiles,
                                              List<String> requiredCapabilities, InstallListener listener);

    /**
     * Like the capability-list variant, but carries the full typed install metadata (capabilities plus
     * any trusted {@code info} fields, license and parameters) that Ollama should record on
     * {@code /api/create}. Verification uses the metadata's capability list.
     */
    InstallTask installGgufFileWithCompanions(String modelName, File ggufFile, List<File> companionFiles,
                                              com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata metadata,
                                              InstallListener listener);

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

        /**
         * The verified {@code /api/show} result for the just-installed model, delivered right before
         * {@link #onComplete(String)}. Default no-op so existing callers stay source-compatible.
         */
        default void onVerified(VerificationResult result) {
        }

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

    interface FilterCatalogListener {
        void onLoaded(CatalogBundle bundle);

        void onError(Exception ex);
    }

    interface OllamaLibraryListener {
        void onModels(List<OllamaLibraryModel> models);

        void onError(Exception ex);
    }

    interface OllamaVariantsListener {
        void onVariants(List<OllamaModelVariant> variants);

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

        /**
         * The verified {@code /api/show} result for the just-installed model, delivered right before
         * {@link #onComplete(String)} or {@link #onIncomplete(VerificationResult)}. Default no-op so
         * existing callers stay source-compatible.
         */
        default void onVerified(VerificationResult result) {
        }

        /**
         * Terminal callback for a model that was created on the server but whose {@code /api/show}
         * verification did not reach {@link VerificationStatus#VERIFIED} (MISSING_REQUIRED, UNKNOWN or
         * FAILED). Mutually exclusive with {@link #onComplete(String)}: the install must never be
         * reported as "Installed" and incomplete at the same time. Default falls back to {@code onError}
         * so existing callers still see a terminal signal.
         */
        default void onIncomplete(VerificationResult result) {
            onError(new java.io.IOException("Model created but not verified: " + result.getStatus()));
        }

        /** Terminal callback for a fully verified install. Mutually exclusive with {@link #onIncomplete}. */
        void onComplete(String message);

        void onError(Exception ex);
    }
}
