package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.hf.DownloadProgressListener;
import com.aresstack.askai.java8.hf.HuggingFaceClient;
import com.aresstack.askai.java8.hf.HuggingFaceFile;
import com.aresstack.askai.java8.hf.HuggingFaceSearchResult;
import com.aresstack.askai.java8.hf.HuggingFaceSearchUseCase;
import com.aresstack.askai.java8.hf.ModelSearchCriteria;
import com.aresstack.askai.java8.hf.catalog.CatalogRepository;
import com.aresstack.askai.java8.hf.convert.ConverterService;
import com.aresstack.askai.java8.hf.convert.OllamaEnvironment;
import com.aresstack.askai.java8.hf.convert.RepositoryAnalysis;
import com.aresstack.askai.java8.hf.convert.RepositoryAnalyzer;
import com.aresstack.askai.java8.hf.convert.SupportDecision;
import com.aresstack.askai.java8.ollamalib.OllamaLibraryClient;
import io.github.ollama4j.Ollama;
import io.github.ollama4j.models.ChatCompletion;
import io.github.ollama4j.models.ChatMessage;
import io.github.ollama4j.models.ChatTokenListener;
import io.github.ollama4j.models.PullProgress;
import io.github.ollama4j.models.PullProgressListener;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class DefaultAskAiService implements AskAiService {

    private final AppConfigurationRepository configurationRepository;
    private final ExecutorService executorService;
    private final ConverterService converterService = new ConverterService();
    private final CatalogRepository catalogRepository = new CatalogRepository();
    // Single cached instance so its short-TTL page cache survives across calls.
    private volatile OllamaLibraryClient ollamaLibraryClient;

    public DefaultAskAiService(AppConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
        this.executorService = Executors.newCachedThreadPool(new DaemonThreadFactory());
    }

    public void listModels(final ModelListListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onModels(client().listModels());
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void sendChat(final ChatRequest request, final ChatListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                if (!request.isValid()) {
                    listener.onError(new IllegalArgumentException("Choose a model and enter text."));
                    return;
                }
                try {
                    AppConfiguration configuration = configurationRepository.load();
                    List<ChatMessage> messages = new ArrayList<ChatMessage>();
                    messages.add(ChatMessage.user(request.getText()));
                    ChatCompletion completion = client().streamChat(request.getModelName(), messages,
                            configuration.getKeepAlive(), new ChatTokenListener() {
                                public void onToken(String token) {
                                    listener.onToken(token);
                                }
                            });
                    listener.onComplete(new ChatSummary(completion.getEvalCount(), completion.getEvalDurationNanos()));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void pullOllamaModel(final String modelName, final PullListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    client().pullModel(modelName, new PullProgressListener() {
                        public void onProgress(PullProgress progress) {
                            listener.onProgress(progress);
                        }
                    });
                    listener.onComplete("Installed " + modelName + " on remote Ollama.");
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void searchHuggingFaceModels(final ModelSearchCriteria criteria, final HuggingFaceSearchListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onResult(new HuggingFaceSearchUseCase(huggingFaceClient()).search(criteria));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void loadMoreHuggingFaceModels(final ModelSearchCriteria criteria, final HuggingFaceSearchResult previous,
                                          final HuggingFaceSearchListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onResult(new HuggingFaceSearchUseCase(huggingFaceClient()).loadMore(criteria, previous));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void listHuggingFaceFiles(final String modelId, final HuggingFaceFileListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onFiles(huggingFaceClient().listFiles(modelId));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void analyzeRepository(final String modelId, final RepositoryAnalysisListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    RepositoryAnalysis analysis = new RepositoryAnalyzer(huggingFaceClient()).analyze(modelId);
                    OllamaEnvironment environment = new OllamaEnvironment(safeOllamaVersion());
                    SupportDecision decision = converterService.classify(analysis, environment);
                    listener.onDecision(decision, analysis);
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void searchOllamaLibrary(final String query, final OllamaLibraryListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onModels(ollamaLibraryClient().search(query));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void loadOllamaVariants(final String baseName, final OllamaVariantsListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onVariants(ollamaLibraryClient().loadVariants(baseName));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    private OllamaLibraryClient ollamaLibraryClient() {
        OllamaLibraryClient local = ollamaLibraryClient;
        if (local == null) {
            synchronized (this) {
                if (ollamaLibraryClient == null) {
                    AppConfiguration configuration = configurationRepository.load();
                    ollamaLibraryClient = new OllamaLibraryClient(
                            configuration.getProxyConfiguration(),
                            configuration.getCertificateTrustConfiguration(),
                            configuration.getHttpClientConfiguration());
                }
                local = ollamaLibraryClient;
            }
        }
        return local;
    }

    public void loadFilterCatalogs(final boolean forceLive, final FilterCatalogListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onLoaded(catalogRepository.load(huggingFaceClient(), forceLive));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    /** @return the Ollama server version, or "" when the server is unreachable (best-effort). */
    private String safeOllamaVersion() {
        try {
            return client().getVersion();
        } catch (Exception ex) {
            return "";
        }
    }

    public void downloadHuggingFaceFile(final HuggingFaceFile file, final DownloadListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    File downloaded = huggingFaceClient().download(file,
                            configurationRepository.load().getModelDownloadDirectory(), new DownloadProgressListener() {
                                public void onProgress(long completed, long total) {
                                    listener.onProgress(completed, total);
                                }
                            });
                    listener.onComplete(downloaded);
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public InstallTask installGgufFile(final String modelName, final File ggufFile, final InstallListener listener) {
        return installGgufFileWithCompanions(modelName, ggufFile, java.util.Collections.<File>emptyList(), listener);
    }

    public InstallTask installGgufFileWithCompanions(final String modelName, final File ggufFile,
                                                     final List<File> companionFiles,
                                                     final InstallListener listener) {
        AppConfiguration configuration = configurationRepository.load();
        final RemoteGgufInstaller installer = new RemoteGgufInstaller(configuration.getOllamaBaseUrl());
        final Future<?> future = executorService.submit(new Runnable() {
            public void run() {
                try {
                    installer.install(modelName, ggufFile, companionFiles,
                            new RemoteGgufInstaller.ProgressListener() {
                                public void onProgress(String phase, long completed, long total) {
                                    listener.onProgress(phase, completed, total);
                                }
                            });
                    listener.onComplete("Installed " + modelName + " on remote Ollama.");
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
        return new InstallTask() {
            public void cancel() {
                installer.cancel();
                future.cancel(true);
            }
        };
    }

    public void shutdown() {
        executorService.shutdownNow();
    }

    private Ollama client() {
        AppConfiguration configuration = configurationRepository.load();
        Ollama ollama = new Ollama(configuration.getOllamaBaseUrl());
        ollama.setRequestTimeoutSeconds(6L * 60L * 60L);
        return ollama;
    }

    private HuggingFaceClient huggingFaceClient() {
        AppConfiguration configuration = configurationRepository.load();
        return new HuggingFaceClient(
                configuration.getProxyConfiguration(),
                configuration.getCertificateTrustConfiguration(),
                configuration.getHttpClientConfiguration(),
                configuration.getHuggingFaceToken());
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-java8-service-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
