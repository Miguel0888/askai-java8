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
    private OllamaChatPanel chatPanel;
    private com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel chatWorkspaceHost;
    private AudioProcessingPanel audioProcessingPanel;
    private ModelSearchPanel installSearchPanel;

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
                // Explicit, bounded shutdown order: tear down workspaces + plugin host first (force-dispose,
                // no unbounded wait on a plugin's close callback), then the chat/dictation, then the service.
                if (chatWorkspaceHost != null) {
                    try {
                        chatWorkspaceHost.shutdown();
                    } catch (RuntimeException ignored) {
                        // never let plugin teardown block application shutdown
                    }
                }
                if (chatPanel != null) {
                    chatPanel.shutdownDictation();
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

    /**
     * Builds the generic chat workspace host around the existing chat panel. The host discovers workspace
     * plugins from a controlled root (overridable via {@code -Daskai.pluginsDir=...}), off the EDT, and wraps
     * AskAI's Markdown/bubble UI as host services. The research plugin is never a compile dependency.
     */
    private com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel buildChatWorkspaceHost(
            OllamaChatPanel normalChat) {
        String override = System.getProperty("askai.pluginsDir");
        java.nio.file.Path pluginsRoot = override != null && override.trim().length() > 0
                ? java.nio.file.Paths.get(override.trim())
                : com.aresstack.askai.java8.settings.AskAiPaths.appDirectory().resolve("plugins");
        try {
            java.nio.file.Files.createDirectories(pluginsRoot);
        } catch (java.io.IOException ignored) {
            // A missing plugin dir just yields an empty catalog; Normal Chat still works.
        }

        java.io.File dataDir = com.aresstack.askai.java8.settings.AskAiPaths.appDirectory().toFile();
        com.aresstack.askai.plugin.host.SwingUiExecutor uiExecutor =
                new com.aresstack.askai.plugin.host.SwingUiExecutor();
        com.aresstack.askai.java8.plugin.host.AskAiNotificationService notificationService =
                new com.aresstack.askai.java8.plugin.host.AskAiNotificationService();
        com.aresstack.askai.plugin.host.WorkspaceHostContextFactory hostContextFactory =
                new com.aresstack.askai.plugin.host.WorkspaceHostContextFactory(
                        dataDir, uiExecutor,
                        new com.aresstack.askai.java8.plugin.host.AskAiThemeService(),
                        new com.aresstack.askai.java8.plugin.host.AskAiMarkdownViewFactory(),
                        new com.aresstack.askai.java8.plugin.host.AskAiConversationSurfaceFactory(),
                        notificationService);
        com.aresstack.askai.plugin.host.WorkspacePluginService pluginService =
                new com.aresstack.askai.plugin.host.WorkspacePluginService(pluginsRoot, HOST_PLUGIN_VERSION, 1,
                        uiExecutor);
        com.aresstack.askai.plugin.api.service.WorkspaceStateStore hostState =
                new com.aresstack.askai.java8.plugin.host.ApplicationStateWorkspaceStateStore(applicationState, "");
        return new com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel(
                normalChat, pluginService, hostContextFactory, uiExecutor, hostState, notificationService);
    }

    /** Host version advertised to PF4J for a plugin's {@code Plugin-Requires} check. */
    private static final String HOST_PLUGIN_VERSION = "0.1.0";

    private JPanel createContentPanel() {
        this.chatPanel = new OllamaChatPanel(model, ollamaService, speechToTextService,
                audioProfileRepository, applicationState);
        chatPanel.setInstallAudioModelHandler(new OllamaChatPanel.InstallAudioModelHandler() {
            public void openInstall() {
                showScreen(INSTALL_VIEW);
            }
        });
        // "Edit profiles…" from the chat settings opens the Audio processing editor page.
        chatPanel.setAudioProcessingSettingsHandler(new OllamaChatPanel.AudioProcessingSettingsHandler() {
            public void openAudioProcessing() {
                showScreen(AUDIO_PROCESSING_VIEW);
            }
        });
        // The Chat view is a generic workspace host: "Normal Chat" (the existing OllamaChatPanel, unchanged)
        // plus any installed, compatible workspace plugins. The host never constructs the chat itself.
        this.chatWorkspaceHost = buildChatWorkspaceHost(chatPanel);
        contentPanel.add(chatWorkspaceHost, CHAT_VIEW);
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
        return contentPanel;
    }

    /** Switches to the Chat view and selects the given model there, keeping the conversation intact. */
    private void useModelInChat(String modelName) {
        showScreen(CHAT_VIEW);
        if (chatPanel != null) {
            chatPanel.useModel(modelName);
        }
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
        if (CHAT_VIEW.equals(screenName) && chatPanel != null) {
            chatPanel.invalidateSpeechReadiness();
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
