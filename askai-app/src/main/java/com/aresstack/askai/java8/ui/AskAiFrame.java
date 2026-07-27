package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.AskAiModel;
import com.aresstack.askai.java8.audio.AudioProfileRepository;
import com.aresstack.askai.java8.audio.FileAudioProfileRepository;
import com.aresstack.askai.java8.state.ApplicationStateService;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.service.AskAiService;
import com.aresstack.askai.java8.service.DefaultOllamaService;
import com.aresstack.askai.java8.service.FeatureActionService;
import com.aresstack.askai.java8.service.OllamaFeatureActionService;
import com.aresstack.askai.java8.service.OllamaService;
import com.aresstack.askai.java8.stt.DefaultSpeechToTextService;
import com.aresstack.askai.java8.stt.SpeechToTextService;
import com.aresstack.askai.java8.batch.service.BatchAudioPreparationService;
import com.aresstack.askai.java8.batch.service.BatchMarkdownResultWriter;
import com.aresstack.askai.java8.batch.service.BatchProfileCatalogLoadedEvent;
import com.aresstack.askai.java8.batch.service.BatchProfileCatalogService;
import com.aresstack.askai.java8.batch.service.BatchSelectionCatalogLoadedEvent;
import com.aresstack.askai.java8.batch.service.BatchSelectionCatalogService;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionEventPublisher;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionService;
import com.aresstack.askai.java8.batch.ui.BatchSelectionRefresher;
import com.aresstack.askai.java8.batch.ui.BatchTranscriptionController;
import com.aresstack.askai.java8.batch.ui.BatchTranscriptionPanel;
import com.aresstack.askai.java8.ui.chat.ChatSessionComponent;
import com.aresstack.askai.java8.ui.chat.ChatSessionId;
import com.aresstack.askai.java8.ui.chat.ChatWorkspacePanel;
import com.aresstack.audio.profile.AudioProcessingProfile;
import com.aresstack.audio.application.DefaultAudioProcessingPreviewService;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.Box;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Main frame for provider-based AI chat and model installation. Java 8 port of the original AskAI
 * frame: same menu structure and cards; the Install and Network views are the Java 8 panels that
 * carry this port's extensions (HuggingFace GGUF flow, WScript proxy discovery, TLS trust, IPv6).
 */
public final class AskAiFrame extends JFrame {

    private static final String CHAT_VIEW = "chat";
    private static final String BATCH_VIEW = "batch";
    private static final String MODELS_VIEW = "models";
    private static final String ACTIONS_VIEW = "actions";
    private static final String INSTALL_VIEW = "install";
    private static final String CONNECTIONS_VIEW = "connections";
    private static final String NETWORK_VIEW = "network";
    private static final String ABOUT_VIEW = "about";
    private static final String AUDIO_PROCESSING_VIEW = "audio-processing";

    private final AskAiModel model;
    private final AppConfigurationRepository configurationRepository;
    private final AskAiService askAiService;
    private final OllamaService ollamaService;
    private final FeatureActionService featureActionService;
    private final SpeechToTextService speechToTextService;
    private final ConnectionStatusView connectionStatusView;
    private final CardLayout contentLayout;
    private final JPanel contentPanel;
    private final OllamaModelsPanel modelsPanel;
    private final AudioProfileRepository audioProfileRepository;
    private final ApplicationStateService applicationState;
    private OllamaConfigPanel configPanel;
    private ChatWorkspacePanel chatWorkspace;
    private AudioProcessingPanel audioProcessingPanel;
    private ModelSearchPanel installSearchPanel;
    private BatchTranscriptionPanel batchPanel;

    public AskAiFrame(AppConfigurationRepository configurationRepository, final AskAiService askAiService) {
        super("AskAI");
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        this.model = new AskAiModel(configurationRepository);
        this.ollamaService = new DefaultOllamaService(model);
        this.featureActionService = new OllamaFeatureActionService(model);
        this.speechToTextService = new DefaultSpeechToTextService(configurationRepository);
        this.connectionStatusView = new ConnectionStatusView(new Runnable() {
            public void run() {
                openConnectionSettings();
            }
        });
        this.contentLayout = new CardLayout();
        this.contentPanel = new JPanel(contentLayout);
        this.modelsPanel = new OllamaModelsPanel(model, ollamaService, askAiService, configurationRepository);
        this.audioProfileRepository = new FileAudioProfileRepository();
        this.applicationState = new ApplicationStateService();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent event) {
                if (chatWorkspace != null) {
                    for (ChatSessionComponent session : chatWorkspace.sessions()) {
                        session.disposeSession();
                    }
                }
                if (batchPanel != null) {
                    batchPanel.dispose();
                }
                askAiService.shutdown();
            }
        });
        setSize(1180, 820);
        setMinimumSize(new Dimension(980, 680));
        setLocationRelativeTo(null);
        buildUserInterface();
        showScreen(CHAT_VIEW);
    }

    /** Kept for the existing launcher: builds the frame and makes it visible. */
    public void showFrame() {
        setVisible(true);
    }

    private void buildUserInterface() {
        setJMenuBar(createMenuBar());
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(createContentPanel(), BorderLayout.CENTER);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        menuBar.add(createTopLevelMenu("Chat", CHAT_VIEW));
        menuBar.add(createTopLevelMenu("Batch", BATCH_VIEW));
        menuBar.add(createModelsMenu());
        menuBar.add(createTopLevelMenu("Actions", ACTIONS_VIEW));
        menuBar.add(createConfigurationMenu());
        menuBar.add(createHelpMenu());
        menuBar.add(Box.createHorizontalGlue());
        menuBar.add(connectionStatusView);
        return menuBar;
    }

    /** Shows the Connections view and puts the cursor in its Base URL field, selected for overwrite. */
    private void openConnectionSettings() {
        showScreen(CONNECTIONS_VIEW);
        if (configPanel != null) {
            configPanel.focusBaseUrl();
        }
    }

    /**
     * Refreshes the top-right connection indicator: shows "Connecting…" then pings the server off the
     * EDT, mapping a version response to "Connected" and an error to "Not reachable" (URL and detail
     * kept in the tooltip). Cheap enough to run on view switches.
     */
    private void refreshConnectionStatus() {
        final String url = model.getOllamaBaseUrl();
        connectionStatusView.setStatus(ConnectionStatus.CONNECTING, url, "");
        ollamaService.getServerVersion(new OllamaService.ServerVersionListener() {
            @Override
            public void onServerVersion(final String version) {
                onUi(new Runnable() {
                    public void run() {
                        connectionStatusView.setStatus(ConnectionStatus.forVersion(version), url,
                                version == null || version.isEmpty() ? "" : "version " + version);
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        connectionStatusView.setStatus(ConnectionStatus.NOT_REACHABLE, url,
                                ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    }
                });
            }
        });
    }

    private static void onUi(Runnable runnable) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            javax.swing.SwingUtilities.invokeLater(runnable);
        }
    }

    private JMenu createTopLevelMenu(String title, String screenName) {
        final JMenu menu = new JMenu(title);
        menu.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                showScreen(screenName);
                menu.setSelected(false);
            }
        });
        return menu;
    }

    private JMenu createModelsMenu() {
        JMenu modelsMenu = new JMenu("Models");
        JMenuItem installedItem = new JMenuItem("Installed");
        installedItem.addActionListener(event -> showModels(true));
        JMenuItem runningItem = new JMenuItem("Running Models");
        runningItem.addActionListener(event -> showModels(false));
        modelsMenu.add(createScreenItem("Setup", INSTALL_VIEW));
        modelsMenu.add(installedItem);
        modelsMenu.add(runningItem);
        return modelsMenu;
    }

    private JMenu createConfigurationMenu() {
        JMenu configurationMenu = new JMenu("Configuration");
        configurationMenu.add(createScreenItem("Connections", CONNECTIONS_VIEW));
        configurationMenu.add(createScreenItem("Network", NETWORK_VIEW));
        configurationMenu.add(createScreenItem("Audio processing", AUDIO_PROCESSING_VIEW));
        return configurationMenu;
    }

    private JMenu createHelpMenu() {
        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(createScreenItem("About", ABOUT_VIEW));
        return helpMenu;
    }

    private JMenuItem createScreenItem(String title, String screenName) {
        JMenuItem item = new JMenuItem(title);
        item.addActionListener(event -> showScreen(screenName));
        return item;
    }

    private JPanel createContentPanel() {
        final OllamaChatPanel.InstallAudioModelHandler installHandler =
                new OllamaChatPanel.InstallAudioModelHandler() {
                    public void openInstall() {
                        showScreen(INSTALL_VIEW);
                    }
                };
        // "Edit profiles…" from the chat settings opens the Audio processing editor page.
        final OllamaChatPanel.AudioProcessingSettingsHandler audioHandler =
                new OllamaChatPanel.AudioProcessingSettingsHandler() {
                    public void openAudioProcessing() {
                        showScreen(AUDIO_PROCESSING_VIEW);
                    }
                };
        // Each tab is an independent chat session, created on demand by the workspace's "+" tab.
        this.chatWorkspace = new ChatWorkspacePanel(new ChatWorkspacePanel.ChatSessionFactory() {
            public ChatSessionComponent create(ChatSessionId id) {
                OllamaChatPanel chat = new OllamaChatPanel(id, model, ollamaService, speechToTextService,
                        audioProfileRepository, applicationState);
                chat.setInstallAudioModelHandler(installHandler);
                chat.setAudioProcessingSettingsHandler(audioHandler);
                return chat;
            }
        });
        contentPanel.add(chatWorkspace, CHAT_VIEW);
        // One-click "Use in chat" from an installed model card: switch to Chat and select the model.
        modelsPanel.setUseInChatHandler(new OllamaModelsPanel.UseInChatHandler() {
            public void useInChat(String modelName) {
                useModelInChat(modelName);
            }
        });
        contentPanel.add(modelsPanel, MODELS_VIEW);
        contentPanel.add(new OllamaActionsPanel(featureActionService, ollamaService), ACTIONS_VIEW);
        // Java 8 port: model search with two sources in tabs — HuggingFace (search/analyze/import)
        // and the Ollama Library (scrape ollama.com, pull a tag on the remote server).
        final ModelSearchPanel modelSearchPanel = new ModelSearchPanel(configurationRepository, askAiService);
        contentPanel.add(modelSearchPanel, INSTALL_VIEW);
        // "Add-ons on Hugging Face" from an installed model card: switch to Setup and enter add-on mode so
        // the chosen encoder is attached to this model (from/adapters), not installed as a new model.
        modelsPanel.setFindAddOnsHandler(new OllamaModelsPanel.FindAddOnsHandler() {
            public void findAddOns(String modelName) {
                showScreen(INSTALL_VIEW);
                modelSearchPanel.openHuggingFaceAddOnSearch(modelName, modelName);
            }

            public void selectLocalAddOn(String modelName) {
                showScreen(INSTALL_VIEW);
                modelSearchPanel.openLocalProjectorAddOn(modelName);
            }
        });
        // After a verified encoder attach, re-read Installed Models from /api/show (no local state kept).
        modelSearchPanel.setAddOnAttachedListener(new Runnable() {
            public void run() {
                modelsPanel.showInstalled();
            }
        });
        this.installSearchPanel = modelSearchPanel;
        this.configPanel = new OllamaConfigPanel(model, ollamaService);
        contentPanel.add(configPanel, CONNECTIONS_VIEW);
        // Java 8 port: the extended proxy panel (WScript discovery, TLS trust, HTTP client, IPv6).
        contentPanel.add(new ProxyPanel(configurationRepository), NETWORK_VIEW);
        contentPanel.add(new OllamaAboutPanel(), ABOUT_VIEW);
        // Java2D pipeline editor for audio-processing profiles (shared repository instance).
        this.audioProcessingPanel = new AudioProcessingPanel(audioProfileRepository, applicationState);
        contentPanel.add(audioProcessingPanel, AUDIO_PROCESSING_VIEW);
        wireBatchComponent();
        return contentPanel;
    }

    /**
     * Wires the batch transcription card. Reuses the shared {@link SpeechToTextService} port and the
     * audio-processing DSP services; the model list is filled asynchronously with only the audio-capable
     * models reported by Ollama's {@code /api/show}, so the panel is built with an empty model list first.
     */
    private void wireBatchComponent() {
        BatchTranscriptionEventPublisher batchEvents = new BatchTranscriptionEventPublisher();
        BatchAudioPreparationService audioPreparation = new BatchAudioPreparationService(
                new DefaultAudioProcessingPreviewService());
        BatchTranscriptionService batchService = new BatchTranscriptionService(
                speechToTextService,
                audioPreparation,
                new BatchMarkdownResultWriter(),
                batchEvents);
        BatchTranscriptionController batchController =
                new BatchTranscriptionController(batchService, batchEvents);

        // The two selection catalogs the Batch view reloads on Refresh. Same audio-capability rule as the
        // Chat model list (via BatchSelectionCatalogService); profiles come from the shared repository.
        final BatchSelectionCatalogService modelCatalog =
                new BatchSelectionCatalogService(new Supplier<String>() {
                    public String get() {
                        return model.getOllamaBaseUrl();
                    }
                });
        final BatchProfileCatalogService profileCatalog =
                new BatchProfileCatalogService(new Supplier<List<AudioProcessingProfile>>() {
                    public List<AudioProcessingProfile> get() {
                        return audioProfileRepository.findAll();
                    }
                });
        BatchSelectionRefresher refresher = new BatchSelectionRefresher() {
            public void loadModels(Consumer<BatchSelectionCatalogLoadedEvent> callback) {
                modelCatalog.loadAsync(callback);
            }
            public void loadProfiles(Consumer<BatchProfileCatalogLoadedEvent> callback) {
                profileCatalog.loadAsync(callback);
            }
        };

        this.batchPanel = new BatchTranscriptionPanel(
                batchController,
                Collections.<String>emptyList(),
                audioProfileRepository.findAll(),
                refresher);
        contentPanel.add(batchPanel, BATCH_VIEW);

        // Initial async model load (profiles are already shown from the constructor list above).
        modelCatalog.loadAsync(new Consumer<BatchSelectionCatalogLoadedEvent>() {
            public void accept(final BatchSelectionCatalogLoadedEvent event) {
                onUi(new Runnable() {
                    public void run() {
                        if (event.isSuccessful()) {
                            batchPanel.setAvailableModels(event.getAudioModelNames());
                        }
                    }
                });
            }
        });
    }

    /** Switches to the Chat view and selects the given model in the active chat, keeping its conversation. */
    private void useModelInChat(String modelName) {
        showScreen(CHAT_VIEW);
        OllamaChatPanel chat = activeChat();
        if (chat != null) {
            chat.useModel(modelName);
        }
    }

    /** @return the OllamaChatPanel of the currently selected chat tab, or null. */
    private OllamaChatPanel activeChat() {
        if (chatWorkspace == null) {
            return null;
        }
        ChatSessionComponent session = chatWorkspace.activeSession();
        return session instanceof OllamaChatPanel ? (OllamaChatPanel) session : null;
    }

    private void showScreen(String screenName) {
        // Leaving the Setup/HF view must drop any transient add-on target so a later normal install can
        // never be silently attached to the old model.
        if (!INSTALL_VIEW.equals(screenName) && installSearchPanel != null) {
            installSearchPanel.leaveAddOnMode();
        }
        contentLayout.show(contentPanel, screenName);
        refreshConnectionStatus();
        // Re-check speech-to-text readiness when returning to the chat (server/model may have changed).
        if (CHAT_VIEW.equals(screenName)) {
            OllamaChatPanel chat = activeChat();
            if (chat != null) {
                chat.invalidateSpeechReadiness();
            }
        }
    }

    /** Show the Models view and select the Installed or Running Models sub-view. */
    private void showModels(boolean installed) {
        if (installSearchPanel != null) {
            installSearchPanel.leaveAddOnMode(); // leaving Setup drops any transient add-on target
        }
        contentLayout.show(contentPanel, MODELS_VIEW);
        if (installed) {
            modelsPanel.showInstalled();
        } else {
            modelsPanel.showRunning();
        }
        refreshConnectionStatus();
    }
}
