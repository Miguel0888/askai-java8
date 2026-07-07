package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.hf.DownloadProgressListener;
import com.aresstack.askai.java8.hf.HuggingFaceClient;
import com.aresstack.askai.java8.hf.HuggingFaceFile;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class DefaultAskAiService implements AskAiService {

    private final AppConfigurationRepository configurationRepository;
    private final ExecutorService executorService;

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

    public void searchHuggingFaceModels(final String query, final HuggingFaceModelListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onModels(huggingFaceClient().searchModels(query, 30));
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

    public void installGgufFile(final String modelName, final File ggufFile, final ActionListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    AppConfiguration configuration = configurationRepository.load();
                    new RemoteGgufInstaller(configuration.getOllamaBaseUrl()).install(modelName, ggufFile);
                    listener.onComplete("Installed " + modelName + " on remote Ollama.");
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
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
        return new HuggingFaceClient(configuration.getProxyConfiguration(), configuration.getHuggingFaceToken());
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
