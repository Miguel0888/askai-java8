package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.AskAiModel;
import com.aresstack.askai.java8.audio.AudioProfileRepository;
import com.aresstack.askai.java8.audio.FileAudioProfileRepository;
import com.aresstack.askai.java8.state.ApplicationStateService;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.service.AskAiService;
import com.aresstack.askai.java8.service.DefaultOllamaService;
import com.aresstack.askai.java8.service.VirtualOllamaContainerService;
import com.aresstack.askai.java8.service.FeatureActionService;
import com.aresstack.askai.java8.service.OllamaFeatureActionService;
import com.aresstack.askai.java8.service.OllamaService;
import com.aresstack.askai.java8.stt.AudioCapability;
import com.aresstack.askai.java8.stt.DefaultSpeechToTextService;
import com.aresstack.askai.java8.stt.SpeechToTextService;
import com.aresstack.askai.java8.catalog.GlobalCatalogRefreshService;
import com.aresstack.askai.java8.catalog.GlobalCatalogSnapshot;
import com.aresstack.askai.java8.client.AskAiOllamaClient;
import com.aresstack.askai.java8.client.OllamaModelInfo;
import com.aresstack.askai.java8.batch.service.BatchAudioPreparationService;
import com.aresstack.askai.java8.batch.service.BatchMarkdownResultWriter;
import com.aresstack.askai.java8.batch.service.BatchProfileCatalogLoadedEvent;
import com.aresstack.askai.java8.batch.service.BatchProfileCatalogService;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionEventPublisher;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionService;
import com.aresstack.askai.java8.batch.ui.BatchProfileRefresher;
import com.aresstack.askai.java8.batch.ui.BatchTranscriptionController;
import com.aresstack.askai.java8.batch.ui.BatchTranscriptionPanel;
import com.aresstack.askai.java8.ui.chat.ChatSessionComponent;
import com.aresstack.askai.java8.ui.chat.ChatSessionId;
import com.aresstack.askai.java8.ui.chat.ChatWorkspacePanel;
import com.aresstack.audio.profile.AudioProcessingProfile;
import com.aresstack.audio.application.DefaultAudioProcessingPreviewService;
import com.aresstack.comiccontrols.control.ComicHoverMenu;
import com.aresstack.comiccontrols.theme.ComicTheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
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
    private static final String AI_MODELS_VIEW = "ai-models";
    private static final String NETWORK_VIEW = "network";
    private static final String ABOUT_VIEW = "about";
    private static final String AUDIO_PROCESSING_VIEW = "audio-processing";
    private static final String PLUGINS_VIEW = "plugins";

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
    /** Persisted CSV of the currently open chat-tab ids, so "closed stays closed" survives a crash. */
    private static final String STATE_OPEN_CHAT_TABS = "chat.openTabs";
    /** Shutdown runs exactly once (from the window close OR the File > Beenden menu). */
    private final java.util.concurrent.atomic.AtomicBoolean shuttingDown =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** One concise shutdown log line to STDERR (the runWithDevPlugins console). */
    private static void shutdownLog(String message) {
        System.err.println("[shutdown] " + message);
    }

    private final ApplicationStateService applicationState;
    private OllamaConfigPanel configPanel;
    private ChatWorkspacePanel chatWorkspace;
    private com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel chatWorkspaceHost;
    private com.aresstack.askai.plugin.host.WorkspacePluginService pluginService;
    private com.aresstack.askai.plugin.host.PluginEnablementService pluginEnablement;
    private java.io.File pluginsDirectory;
    private com.aresstack.askai.plugin.host.AgentSessionCoordinator agentCoordinator;
    /**
     * The read-only chat catalog handed to plugins as a host service: it answers "which chat is selected"
     * and "what is this chat called". Created with the chat history store, updated on every tab switch.
     */
    private com.aresstack.askai.java8.plugin.host.LocalChatSessionCatalog chatSessionCatalog;
    /** Reads the PERSISTED messages of a chat for plugins — same store, no second message truth. */
    private com.aresstack.askai.java8.plugin.host.LocalChatSessionHistoryReader chatSessionHistory;
    /** The one WRITING chat port for plugins: create a chat and activate an agent in it. */
    private com.aresstack.askai.java8.plugin.host.LocalChatSessionLauncher chatSessionLauncher;
    /** Builds the active agent's composer accessories above the active tab's composer (kept to avoid GC). */
    private com.aresstack.askai.plugin.host.AgentComposerAccessoryArea composerAccessoryArea;
    /** The tab currently showing a composer accessory, so it can be cleared when the active tab changes. */
    private OllamaChatPanel accessoryTargetTab;
    /** Host runtime services (owns the process-global Solon MCP server); stopped synchronously on close. */
    private com.aresstack.askai.java8.plugin.host.AgentRuntimeServices agentRuntimeServices;
    private AudioProcessingPanel audioProcessingPanel;
    private ModelSearchPanel installSearchPanel;
    private BatchTranscriptionPanel batchPanel;
    private final GlobalCatalogRefreshService catalogRefreshService;
    private final JButton globalRefreshButton;
    /** The general-purpose video recorder controller; created lazily on first use of Help → Record Video. */
    private com.aresstack.askai.java8.video.VideoRecordingController videoRecordingController;

    public AskAiFrame(AppConfigurationRepository configurationRepository, final AskAiService askAiService) {
        super("AskAI");
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        this.model = new AskAiModel(configurationRepository);
        // R0.3: the VIRTUAL container service is the single source of every model list —
        // remote Ollama and AskAI's local model runtime aggregated with per-source fault isolation.
        this.ollamaService = new VirtualOllamaContainerService(model,
                askAiService.localRuntimeManager());
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
        this.catalogRefreshService = new GlobalCatalogRefreshService(
                new GlobalCatalogRefreshService.CatalogLoader<String>() {
                    public List<String> load() throws Exception { return loadInstalledModelNames(); }
                },
                new GlobalCatalogRefreshService.CatalogLoader<String>() {
                    public List<String> load() throws Exception { return loadAudioCapableModelNames(); }
                },
                new GlobalCatalogRefreshService.CatalogLoader<AudioProcessingProfile>() {
                    public List<AudioProcessingProfile> load() { return audioProfileRepository.findAll(); }
                },
                new Consumer<Runnable>() {
                    public void accept(Runnable runnable) { onUi(runnable); }
                });
        this.globalRefreshButton = createGlobalRefreshButton();
        // R0.4 lifecycle: after an AskAI restart the local model runtime comes back automatically
        // when locally installed models exist, and it always ends with AskAI.
        final com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager localRuntime =
                askAiService.localRuntimeManager();
        if (localRuntime.hasInstalledModels() && localRuntime.isAvailable()) {
            Thread localRuntimeBoot = new Thread(new Runnable() {
                public void run() {
                    try {
                        localRuntime.ensureStarted();
                    } catch (Exception ex) {
                        System.err.println("[local-runtime] start on boot failed: "
                                + ex.getMessage());
                    }
                }
            }, "askai-local-runtime-boot");
            localRuntimeBoot.setDaemon(true);
            localRuntimeBoot.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                localRuntime.stop();
            }
        }, "askai-local-runtime-shutdown"));
        this.catalogRefreshService.subscribe(new GlobalCatalogRefreshService.Listener() {
            public void onRefreshStarted() { globalRefreshButton.setEnabled(false); }
            public void onCatalogRefreshed(GlobalCatalogSnapshot snapshot) { applyGlobalSnapshot(snapshot); }
        });
        this.audioProfileRepository = new FileAudioProfileRepository();
        this.applicationState = new ApplicationStateService();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent event) {
                performShutdown();
            }
        });
        setSize(1180, 820);
        setMinimumSize(new Dimension(980, 680));
        setLocationRelativeTo(null);
        buildUserInterface();
        showScreen(CHAT_VIEW);
        catalogRefreshService.refresh(); // initial catalog load, distributed to the chat + batch panels
    }

    /**
     * The single shutdown path (window close OR File &gt; Beenden). Tears the plugin/runtime down gracefully and
     * — crucially — stops the process-global Solon MCP server synchronously via {@link AgentRuntimeServices}.
     * Solon's HTTP server runs a NON-daemon {@code HTTP-Dispatcher} thread that is otherwise never released, so
     * once research has booted Solon the JVM cannot exit naturally (the app hung on close). Stopping it here lets
     * every remaining thread (EDT after dispose, the plugin-shutdown worker once it completes) finish so the JVM
     * exits on its own — no {@code System.exit}/{@code halt} needed. Idempotent.
     */
    private void performShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return; // idempotent
        }
        shutdownLog("performShutdown begin");
        if (chatWorkspaceHost != null) {
            try {
                chatWorkspaceHost.shutdown();
            } catch (RuntimeException ex) {
                shutdownLog("chatWorkspaceHost.shutdown() threw: " + ex);
            }
        }
        if (chatWorkspace != null) {
            for (ChatSessionComponent session : chatWorkspace.sessions()) {
                session.disposeSession();
            }
        }
        if (batchPanel != null) {
            batchPanel.dispose();
        }
        // A running recording must be finalized, not left corrupt, when AskAI exits.
        if (videoRecordingController != null) {
            videoRecordingController.stopIfRecordingForShutdown();
        }
        askAiService.shutdown();
        // Release the non-daemon Solon HTTP-Dispatcher so the JVM can terminate naturally (see method javadoc).
        if (agentRuntimeServices != null) {
            agentRuntimeServices.shutdown();
        }
        shutdownLog("performShutdown done (JVM will exit naturally)");
    }

    /** Kept for the existing launcher: builds the frame and makes it visible. */
    public void showFrame() {
        setVisible(true);
    }

    private void buildUserInterface() {
        // The comic design language's global part: ink contour + yellow/ink selection for ALL
        // dropdowns and context popups, ink baseline under the menu bar (issue #36).
        ComicTheme.installMenuDefaults();
        setJMenuBar(createMenuBar());
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(createContentPanel(), BorderLayout.CENTER);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        menuBar.add(createFileMenu());
        menuBar.add(createTopLevelMenu("Chat", CHAT_VIEW));
        menuBar.add(createTopLevelMenu("Batch", BATCH_VIEW));
        menuBar.add(createModelsMenu());
        menuBar.add(createConfigurationMenu());
        menuBar.add(createHelpMenu());
        menuBar.add(Box.createHorizontalGlue());
        menuBar.add(connectionStatusView);
        menuBar.add(globalRefreshButton); // one refresh for connection, models and audio profiles
        return menuBar;
    }

    /** Far-left "Datei" menu with an explicit "Beenden" that runs the same logged shutdown as the window X. */
    private JMenu createFileMenu() {
        JMenu menu = new ComicHoverMenu("Datei");
        javax.swing.JMenuItem quit = new javax.swing.JMenuItem("Beenden");
        quit.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                shutdownLog("quit requested via File > Beenden");
                performShutdown();
                dispose();
            }
        });
        menu.add(quit);
        return menu;
    }

    private JButton createGlobalRefreshButton() {
        JButton button = new JButton(new RefreshIcon(14));
        button.setToolTipText("Refresh connection, models and audio profiles");
        button.setFocusPainted(false);
        button.setMargin(new Insets(0, 6, 0, 10));
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                catalogRefreshService.refresh(); // no-op if one is already running
            }
        });
        return button;
    }

    /** Load installed model names for the chat model list (blocking; runs off the EDT in the refresh service). */
    private List<String> loadInstalledModelNames() throws Exception {
        // Feeds the CHAT dropdown catalogs: local models only when they can actually chat.
        return ((VirtualOllamaContainerService) ollamaService).loadChatModelNamesNow();
    }

    /** Load only the audio-capable model names (exact {@code audio} capability), same rule as the batch list. */
    private List<String> loadAudioCapableModelNames() throws Exception {
        VirtualOllamaContainerService virtual = (VirtualOllamaContainerService) ollamaService;
        List<String> audioModels = new ArrayList<String>();
        for (OllamaModelInfo installed : virtual.loadInstalledModelsNow()) {
            String name = installed.getDisplayName();
            try {
                if (AudioCapability.isAudioCapable(
                        virtual.loadModelInfoNow(name).getCapabilities())) {
                    audioModels.add(name);
                }
            } catch (Exception ignored) {
                // Skip a model we cannot query; UNKNOWN capabilities never count as audio.
            }
        }
        return audioModels;
    }

    /** Distribute a global catalog snapshot to every open chat tab and the batch panel (on the EDT). */
    private void applyGlobalSnapshot(GlobalCatalogSnapshot snapshot) {
        globalRefreshButton.setEnabled(true);
        if (chatWorkspace != null) {
            for (ChatSessionComponent session : chatWorkspace.sessions()) {
                if (session instanceof OllamaChatPanel) {
                    ((OllamaChatPanel) session).applyCatalogSnapshot(snapshot);
                }
            }
        }
        if (batchPanel != null) {
            batchPanel.applyCatalogSnapshot(snapshot);
        }
        refreshConnectionStatus();
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
        final JMenu menu = new ComicHoverMenu(title);
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
        JMenu modelsMenu = new ComicHoverMenu("Models");
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
        JMenu configurationMenu = new ComicHoverMenu("Configuration");
        configurationMenu.add(createScreenItem("Connections", CONNECTIONS_VIEW));
        configurationMenu.add(createScreenItem("AI models", AI_MODELS_VIEW));
        configurationMenu.add(createScreenItem("Network", NETWORK_VIEW));
        configurationMenu.add(createScreenItem("Audio processing", AUDIO_PROCESSING_VIEW));
        configurationMenu.add(createScreenItem("Plugins", PLUGINS_VIEW));
        return configurationMenu;
    }

    private JMenu createHelpMenu() {
        JMenu helpMenu = new ComicHoverMenu("Help");
        helpMenu.add(createScreenItem("Actions", ACTIONS_VIEW));
        JMenuItem recordVideo = new JMenuItem("Record Video...");
        recordVideo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                openRecordVideoDialog();
            }
        });
        helpMenu.add(recordVideo);
        helpMenu.addSeparator();
        helpMenu.add(createScreenItem("About", ABOUT_VIEW));
        return helpMenu;
    }

    /** The general-purpose video recorder (first entry point: Help). Works over the abstract recording layer. */
    private void openRecordVideoDialog() {
        if (videoRecordingController == null) {
            videoRecordingController = new com.aresstack.askai.java8.video.VideoRecordingController(
                    com.aresstack.askai.java8.video.MediaRecorderProviders.defaults());
        }
        String fileName = "askai-recording-" + videoTimestamp() + ".mp4";
        // Neutral source: only the frame's screen bounds are handed over, never the AskAiFrame itself.
        com.aresstack.askai.java8.video.ui.RecordVideoDialog dialog =
                new com.aresstack.askai.java8.video.ui.RecordVideoDialog(
                        this, videoRecordingController, getBounds(), fileName);
        dialog.setVisible(true);
    }

    private static String videoTimestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new java.util.Date());
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
            ChatWorkspacePanel chatTabs) {
        migrateLegacyChatModeState();
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
        com.aresstack.askai.plugin.api.service.WorkspaceStateStore hostState =
                new com.aresstack.askai.java8.plugin.host.ApplicationStateWorkspaceStateStore(applicationState, "");
        this.pluginEnablement = new com.aresstack.askai.plugin.host.PluginEnablementService(hostState);
        this.pluginsDirectory = pluginsRoot.toFile();
        com.aresstack.askai.plugin.host.WorkspacePluginService pluginService =
                new com.aresstack.askai.plugin.host.WorkspacePluginService(pluginsRoot, HOST_PLUGIN_VERSION, 1,
                        uiExecutor, pluginEnablement);
        this.pluginService = pluginService;
        com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel host =
                new com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel(
                        chatTabs, pluginService, hostContextFactory, uiExecutor, hostState, notificationService);
        // The controls factory needs the mode controller, which the host panel is; wire it now so agent
        // workspaces can embed a reusable Yapping/Questing + agent selector bound to the same controller.
        hostContextFactory.setInteractionModeControlsFactory(
                new com.aresstack.askai.plugin.host.DefaultInteractionModeControlsFactory(host));
        // Controller = single source of truth: every chat tab's composer selector is bound to it in
        // wireChatTabToAgentHost (per tab, because tabs come and go).

        // New agent model (Commit 11): Questing routes to an agent session over the SHARED chat/composer.
        // The agent's activity is pushed into the transcript of the chat tab that is ACTIVE when the agent
        // session is created (the sink is resolved per session, not captured once); the composer routes
        // plain prompts + stop to the active session. Yapping keeps the Ollama path.
        final com.aresstack.askai.plugin.host.SwingUiExecutor agentUiExecutor = uiExecutor;
        final com.aresstack.askai.java8.plugin.host.AskAiNotificationService agentNotify = notificationService;
        final java.io.File agentDataDir = dataDir;
        final com.aresstack.askai.plugin.host.WorkspacePluginService agentPluginService = pluginService;
        com.aresstack.askai.plugin.host.AgentSessionCoordinator.AgentExtensionResolver agentResolver =
                new com.aresstack.askai.plugin.host.AgentSessionCoordinator.AgentExtensionResolver() {
                    public com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension resolve(String agentId) {
                        return agentPluginService.getSelectableAgentExtension(agentId);
                    }
                };
        // Host-owned runtime services for the productive research mode (lazy Solon MCP runtime,
        // MCP tool-client factory, ACP connector). Plugins look them up via AgentHostContext.getService.
        final com.aresstack.askai.java8.plugin.host.AgentRuntimeServices agentRuntimeServices =
                new com.aresstack.askai.java8.plugin.host.AgentRuntimeServices(
                        askAiService.localRuntimeManager(), configurationRepository);
        this.agentRuntimeServices = agentRuntimeServices; // stopped synchronously in performShutdown()
        // Belt-and-suspenders for non-window exits (Ctrl-C etc.); idempotent with the direct call on close.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                agentRuntimeServices.shutdown();
            }
        }, "agent-runtime-services-shutdown"));
        // Central model change → re-publish the running research sessions' descriptors (no direct UI→research
        // coupling: the event flows AskAiModel → this listener → the host session registry).
        final com.aresstack.askai.java8.localmodels.LocalActiveResearchSessionRegistry activeResearchSessions =
                agentRuntimeServices.activeSessionRegistry();
        if (activeResearchSessions != null) {
            model.addAiModelSelectionListener(new com.aresstack.askai.java8.AiModelSelectionListener() {
                public void onMainModelChanged() {
                    activeResearchSessions.refreshInference();
                }

                public void onRerankerOrEmbeddingsChanged() {
                    activeResearchSessions.refreshReranker();
                }
            });
        }
        // The typed "open sources/config view" card actions reach the artifact area through this host
        // service (same hook as /open); the coordinator holder is filled right below.
        final com.aresstack.askai.plugin.host.AgentSessionCoordinator[] coordinatorRef =
                new com.aresstack.askai.plugin.host.AgentSessionCoordinator[1];
        com.aresstack.askai.plugin.host.AgentSessionCoordinator.AgentHostContextProvider agentHostProvider =
                new com.aresstack.askai.plugin.host.AgentSessionCoordinator.AgentHostContextProvider() {
                    public com.aresstack.askai.plugin.api.agent.AgentHostContext create(
                            String agentId, String sessionInstanceId) {
                        java.util.Map<Class<?>, Object> services =
                                new java.util.LinkedHashMap<Class<?>, Object>(
                                        agentRuntimeServices.asServiceMap());
                        // READ-ONLY view of the chat tabs: which one is selected, what each is called.
                        // A plugin needs it to publish its sessions under the host's own chat ids.
                        if (chatSessionCatalog != null) {
                            services.put(com.aresstack.askai.plugin.api.service.ChatSessionCatalog.class,
                                    chatSessionCatalog);
                        }
                        // The persisted messages of a chat: a SEPARATE port (ISP) over the same store, so a
                        // plugin can project the canonical conversation instead of keeping its own copy.
                        if (chatSessionHistory != null) {
                            services.put(
                                    com.aresstack.askai.plugin.api.service.ChatSessionHistoryReader.class,
                                    chatSessionHistory);
                        }
                        // The only WRITING chat port: create a chat + activate an agent in it. Unlike the
                        // read ports this one deliberately changes what the user sees.
                        if (chatSessionLauncher != null) {
                            services.put(com.aresstack.askai.plugin.api.service.ChatSessionLauncher.class,
                                    chatSessionLauncher);
                        }
                        services.put(com.aresstack.askai.plugin.api.service.ArtifactViewOpener.class,
                                new com.aresstack.askai.plugin.api.service.ArtifactViewOpener() {
                                    public void openArtifact(String artifactId) {
                                        if (coordinatorRef[0] != null) {
                                            coordinatorRef[0].openArtifactView(artifactId);
                                        }
                                    }
                                });
                        // Narrow streaming inference for in-process plugins (e.g. the research narrator):
                        // the existing chat stack + the centrally selected main model, read per call.
                        services.put(com.aresstack.askai.agent.model.inference.AgentInferencePort.class,
                                new com.aresstack.askai.java8.plugin.host.OllamaAgentInferencePort(
                                        ollamaService,
                                        new com.aresstack.askai.java8.plugin.host
                                                .OllamaAgentInferencePort.ModelSource() {
                                            public String mainModelName() {
                                                return configurationRepository.load()
                                                        .getAiModelSelections().getMainModel();
                                            }
                                        }));
                        return new com.aresstack.askai.plugin.host.DefaultAgentHostContext(
                                agentUiExecutor,
                                new com.aresstack.askai.java8.plugin.host.AskAiThemeService(),
                                new com.aresstack.askai.java8.plugin.host.AskAiMarkdownViewFactory(),
                                agentNotify,
                                // SESSION-scoped settings, last-setting-wins: a NEW chat starts from
                                // the user's latest choice (write-through template), an EXISTING chat is
                                // frozen at first use and keeps its own values.
                                new com.aresstack.askai.plugin.host.SessionScopedWorkspaceStateStore(
                                        new com.aresstack.askai.java8.plugin.host
                                                .ApplicationStateWorkspaceStateStore(
                                                applicationState, "agent." + agentId + "."),
                                        new com.aresstack.askai.java8.plugin.host
                                                .ApplicationStateWorkspaceStateStore(
                                                applicationState, "agent." + agentId + ".session."
                                                        + sessionInstanceId + ".")),
                                new com.aresstack.askai.plugin.host.ScopedPluginPathService(agentDataDir, agentId),
                                resolveAgentSinkForSessionKey(sessionInstanceId),
                                services);
                    }
                };
        // PER-TAB session scope: the session id (and thus its project directory + sink) is derived from the
        // ACTIVE chat tab's ChatSessionId, so two tabs of the same agent get distinct research sessions and a
        // closed tab's session is never resumed by a fresh tab. Falls back to "session" when no tab is active.
        final com.aresstack.askai.plugin.host.AgentSessionCoordinator.SessionScopeProvider sessionScope =
                new com.aresstack.askai.plugin.host.AgentSessionCoordinator.SessionScopeProvider() {
                    public String currentScope() {
                        OllamaChatPanel chat = activeChat();
                        return chat != null ? chat.getSessionId().toString() : "session";
                    }
                };
        final com.aresstack.askai.plugin.host.AgentSessionCoordinator agentCoordinator =
                new com.aresstack.askai.plugin.host.AgentSessionCoordinator(
                        agentResolver, agentHostProvider, uiExecutor, sessionScope);
        coordinatorRef[0] = agentCoordinator;
        // Transactional refresh: the coordinator detaches the outgoing generation's sessions on the EDT and the
        // service closes them off-EDT before retiring the old classloaders, so no session survives a swap.
        pluginService.setGenerationSwapHook(agentCoordinator);
        host.setAgentSessionCoordinator(agentCoordinator);
        this.agentCoordinator = agentCoordinator;
        // The ACTIVE agent's artifact views live in the LEFT chat sidebar (the old right-hand artifact
        // area is gone): tabs exist exactly while an agent session is active (e.g. Research in Questing)
        // and disappear otherwise. Views are cached per coordinator change, so editing state survives
        // drawer open/close; /open reveals the matching pane latched.
        final com.aresstack.askai.plugin.host.AgentArtifactTabs artifactTabs =
                new com.aresstack.askai.plugin.host.AgentArtifactTabs(agentCoordinator, uiExecutor,
                        new com.aresstack.askai.java8.plugin.host.AskAiThemeService(),
                        new com.aresstack.askai.java8.plugin.host.AskAiMarkdownViewFactory());
        chatTabs.setSidebarTabContributions(
                new java.util.function.Supplier<java.util.List<com.aresstack.askai.java8.ui.sidebar.ChatSidebarTab>>() {
                    public java.util.List<com.aresstack.askai.java8.ui.sidebar.ChatSidebarTab> get() {
                        java.util.List<com.aresstack.askai.java8.ui.sidebar.ChatSidebarTab> tabs =
                                new java.util.ArrayList<com.aresstack.askai.java8.ui.sidebar.ChatSidebarTab>();
                        for (final com.aresstack.askai.plugin.host.AgentArtifactTabs.Tab tab : artifactTabs.tabs()) {
                            tabs.add(new com.aresstack.askai.java8.ui.sidebar.ChatSidebarTab() {
                                public String getTitle() {
                                    return tab.getTitle();
                                }

                                public javax.swing.JComponent getComponent() {
                                    return tab.getComponent();
                                }
                            });
                        }
                        return tabs;
                    }
                });
        artifactTabs.addChangeListener(new Runnable() {
            public void run() {
                chatTabs.refreshSidebarTabs(); // keeps an open drawer honest on agent switch
            }
        });
        // /open <artifact> reveals the matching sidebar pane (latched, so it stays put).
        agentCoordinator.setArtifactOpener(new com.aresstack.askai.plugin.host.AgentSessionCoordinator.ArtifactOpener() {
            public void open(String artifactId) {
                com.aresstack.askai.plugin.host.AgentArtifactTabs.Tab tab = artifactTabs.tabForArtifact(artifactId);
                if (tab != null) {
                    chatTabs.openSidebarTab(tab.getTitle());
                }
            }
        });
        // Generic composer accessories: place the ACTIVE agent's accessory above the active tab's composer.
        // The area rebuilds on every coordinator change (tab/agent switch); the frame routes it to the active
        // tab and clears the previously-targeted one.
        final com.aresstack.askai.plugin.host.ComposerAccessoryHost accessoryHost =
                new com.aresstack.askai.plugin.host.ComposerAccessoryHost() {
                    public void setAccessory(javax.swing.JComponent component) {
                        OllamaChatPanel active = activeChat();
                        if (accessoryTargetTab != null && accessoryTargetTab != active) {
                            accessoryTargetTab.clearComposerAccessory();
                        }
                        if (active != null) {
                            active.setComposerAccessory(component);
                            accessoryTargetTab = active;
                        } else {
                            accessoryTargetTab = null;
                        }
                    }

                    public void clearAccessory() {
                        if (accessoryTargetTab != null) {
                            accessoryTargetTab.clearComposerAccessory();
                            accessoryTargetTab = null;
                        }
                    }

                    public void setComposerPlaceholder(String placeholderOrNull) {
                        if (accessoryTargetTab != null) {
                            accessoryTargetTab.setComposerPlaceholder(placeholderOrNull);
                        }
                    }
                };
        this.composerAccessoryArea = new com.aresstack.askai.plugin.host.AgentComposerAccessoryArea(
                agentCoordinator, uiExecutor,
                new com.aresstack.askai.java8.plugin.host.AskAiThemeService(),
                new com.aresstack.askai.java8.plugin.host.AskAiMarkdownViewFactory(),
                accessoryHost);
        // Generic agent toolbar contributions (left of the workspace gear): same coordinator-driven
        // lifecycle as the composer accessories; the workspace only hosts the slot.
        new com.aresstack.askai.plugin.host.AgentToolbarArea(
                agentCoordinator, uiExecutor,
                new com.aresstack.askai.java8.plugin.host.AskAiThemeService(),
                new com.aresstack.askai.plugin.host.AgentToolbarHost() {
                    public void setToolbar(javax.swing.JComponent component) {
                        chatTabs.setAgentToolbar(component);
                    }

                    public void clearToolbar() {
                        chatTabs.clearAgentToolbar();
                    }
                });
        return host;
    }

    /**
     * Binds one chat tab's composer to the plugin host: Yapping/Questing mode selector, agent submission
     * routing and slash commands. Restored tabs are wired right after the host is built; later tabs by
     * the session factory (no-op until the host exists).
     */
    private void wireChatTabToAgentHost(OllamaChatPanel chat) {
        if (chatWorkspaceHost == null || agentCoordinator == null) {
            return;
        }
        chat.setWorkspaceModeController(chatWorkspaceHost);
        chat.setChatSubmissionRouter(agentCoordinator);
        // Gear menu: the ACTIVE agent's settings pages, session-based (the dialog opens from the active
        // tab, whose session is the coordinator's active one).
        chat.setAgentSettingsSource(
                new java.util.function.Supplier<java.util.List<
                        com.aresstack.askai.plugin.api.agent.AgentSettingsContribution>>() {
                    public java.util.List<com.aresstack.askai.plugin.api.agent.AgentSettingsContribution>
                            get() {
                        return agentCoordinator.getActiveSettingsContributions();
                    }
                },
                new java.util.function.Supplier<com.aresstack.askai.plugin.api.agent.AgentSession>() {
                    public com.aresstack.askai.plugin.api.agent.AgentSession get() {
                        return agentCoordinator.getActiveSession();
                    }
                });
        chat.setAgentCommandRegistry(agentCoordinator);
        // Tab close ENDS this tab's agent session(s) off-EDT (its ChatSessionId is the scope).
        final com.aresstack.askai.plugin.host.AgentSessionCoordinator closerCoordinator = agentCoordinator;
        chat.setTabSessionCloser(new OllamaChatPanel.TabSessionCloser() {
            public void closeSessionsForTab(String scope) {
                closerCoordinator.closeSessionsForScope(scope);
            }
        });
    }

    /**
     * The conversation sink an agent session binds to: the tab the session BELONGS to, identified by the
     * scope embedded in the coordinator's session key ({@code agentId#<ChatSessionId>}). Resolving via the
     * ACTIVE tab instead put another tab's bubbles/action buttons into whichever tab happened to be selected
     * when a session was (re)created with several chat tabs open. Falls back to the active tab only when no
     * tab matches the scope (e.g. the legacy "session" scope with no tab id).
     */
    private com.aresstack.askai.plugin.api.agent.AgentConversationSink resolveAgentSinkForSessionKey(
            String sessionKey) {
        String scope = null;
        if (sessionKey != null) {
            int hash = sessionKey.indexOf('#');
            scope = hash < 0 ? sessionKey : sessionKey.substring(hash + 1);
        }
        if (scope != null && !scope.isEmpty() && chatWorkspace != null) {
            for (ChatSessionComponent session : chatWorkspace.sessions()) {
                if (session instanceof OllamaChatPanel
                        && scope.equals(((OllamaChatPanel) session).getSessionId().toString())) {
                    return ((OllamaChatPanel) session).getAgentConversationSink();
                }
            }
        }
        return resolveActiveAgentSink();
    }

    /** Fallback sink: the active chat tab's (there is always one tab). */
    private com.aresstack.askai.plugin.api.agent.AgentConversationSink resolveActiveAgentSink() {
        OllamaChatPanel chat = activeChat();
        if (chat == null && chatWorkspace != null) {
            for (ChatSessionComponent session : chatWorkspace.sessions()) {
                if (session instanceof OllamaChatPanel) {
                    chat = (OllamaChatPanel) session;
                    break;
                }
            }
        }
        if (chat == null) {
            throw new IllegalStateException("no chat tab available for the agent conversation sink");
        }
        return chat.getAgentConversationSink();
    }

    /**
     * One-time migration from the old chat.mode/chat.agent keys to the controller's chat.interactionMode/
     * chat.questingAgentId. Runs only when the new keys are absent; old keys are then ignored.
     */
    private void migrateLegacyChatModeState() {
        if (applicationState.get("chat.interactionMode", null) != null) {
            return;
        }
        String oldMode = applicationState.get("chat.mode", null);
        if (oldMode == null) {
            return;
        }
        boolean questing = !"Yapping".equalsIgnoreCase(oldMode.trim());
        applicationState.putAndSave("chat.interactionMode",
                questing ? com.aresstack.askai.plugin.host.WorkspaceModeEntry.QUESTING_ID
                        : com.aresstack.askai.plugin.host.WorkspaceModeEntry.YAPPING_ID);
        String oldAgent = applicationState.get("chat.agent", null);
        if (questing && oldAgent != null && oldAgent.trim().length() > 0) {
            applicationState.putAndSave("chat.questingAgentId", oldAgent.trim());
        }
    }

    /** Host version advertised to PF4J for a plugin's {@code Plugin-Requires} check. */
    private static final String HOST_PLUGIN_VERSION = "0.1.0";

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
        // Each tab is an independent chat session, created on demand by the workspace sidebar.
        final com.aresstack.askai.java8.history.ChatHistoryStore historyStore =
                new com.aresstack.askai.java8.history.ChatHistoryStore();
        // The SAME store backs the plugin-facing chat catalog — no second persistence, no copied titles.
        this.chatSessionCatalog =
                new com.aresstack.askai.java8.plugin.host.LocalChatSessionCatalog(historyStore);
        this.chatSessionHistory =
                new com.aresstack.askai.java8.plugin.host.LocalChatSessionHistoryReader(historyStore);
        ChatWorkspacePanel.ChatSessionFactory chatFactory = new ChatWorkspacePanel.ChatSessionFactory() {
            public ChatSessionComponent create(ChatSessionId id) {
                OllamaChatPanel chat = new OllamaChatPanel(id, model, ollamaService, speechToTextService,
                        audioProfileRepository, applicationState, historyStore);
                chat.setInstallAudioModelHandler(installHandler);
                chat.setAudioProcessingSettingsHandler(audioHandler);
                // A persisted title/timestamp must reach an OPEN chat list right away.
                chat.setHistoryChangedListener(new Runnable() {
                    public void run() {
                        if (chatWorkspace != null) {
                            chatWorkspace.refreshChatTitles();
                        }
                    }
                });
                wireChatTabToAgentHost(chat);
                return chat;
            }
        };
        // Restore previously persisted chats (most recent first) so tabs survive a restart — but only the
        // tabs that were OPEN at the last change. "Closed stays closed": the open set is persisted
        // immediately on every open/close (below), so a crash never resurrects a tab the user closed. On
        // first run with no persisted set (migration) we fall back to ALL history, the historical behaviour.
        String openCsv = applicationState.get(STATE_OPEN_CHAT_TABS, null);
        java.util.Set<String> openIds = openCsv == null ? null
                : new java.util.HashSet<String>(java.util.Arrays.asList(openCsv.split(",")));
        List<ChatSessionId> restoreIds = new ArrayList<ChatSessionId>();
        for (com.aresstack.askai.java8.history.ChatRecord record : historyStore.list()) {
            if (openIds != null && !openIds.contains(record.getId())) {
                continue; // this chat's tab was closed — kept in history, but not reopened
            }
            try {
                restoreIds.add(new ChatSessionId(java.util.UUID.fromString(record.getId())));
            } catch (IllegalArgumentException ignored) {
                // Skip records whose id is not a valid UUID.
            }
        }
        this.chatWorkspace = new ChatWorkspacePanel(chatFactory, restoreIds, historyStore);
        // Restore + persist the burger drawer's pinned state across restarts.
        this.chatWorkspace.setApplicationState(applicationState);
        // Persist the open-tab set immediately on every open/close (and once now for the restored set), so a
        // later crash/kill never brings a closed tab back.
        this.chatWorkspace.setTabSetListener(new ChatWorkspacePanel.TabSetListener() {
            public void tabSetChanged(List<ChatSessionId> ids) {
                StringBuilder csv = new StringBuilder();
                for (ChatSessionId id : ids) {
                    if (csv.length() > 0) {
                        csv.append(',');
                    }
                    csv.append(id.toString());
                }
                applicationState.putAndSave(STATE_OPEN_CHAT_TABS, csv.toString());
            }
        });
        // The Chat view is a generic workspace host: "Normal Chat" (the tabbed chat workspace) plus any
        // installed, compatible workspace plugins. The host never constructs the chats itself.
        this.chatWorkspaceHost = buildChatWorkspaceHost(chatWorkspace);
        // Tabs restored above were created BEFORE the plugin host existed — wire them now; tabs opened
        // later are wired by the factory itself.
        for (ChatSessionComponent session : chatWorkspace.sessions()) {
            if (session instanceof OllamaChatPanel) {
                wireChatTabToAgentHost((OllamaChatPanel) session);
            }
        }
        // The workspace mode (Yapping/Questing) + agent is held PER TAB: on every tab switch (and on the
        // initial selection) the host loads and activates that tab's own mode. Closing the last research tab
        // therefore opens a fresh tab whose default mode restarts the agent with a new greeting.
        final com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel hostForTabs = this.chatWorkspaceHost;
        final com.aresstack.askai.java8.plugin.host.LocalChatSessionCatalog catalogForTabs =
                this.chatSessionCatalog;
        chatWorkspace.setActiveSessionListener(new ChatWorkspacePanel.ActiveSessionListener() {
            public void activeSessionChanged(ChatSessionId id) {
                if (hostForTabs != null) {
                    hostForTabs.setActiveChatSession(id.toString());
                }
                // Publish the selection for plugins (read off-EDT by MCP workers): an agent session that
                // is addressed WITHOUT an explicit id follows the visible chat immediately — no session
                // restart, no re-attach anywhere.
                catalogForTabs.setActiveSessionId(id.toString());
            }
        });
        // Seed the initial selection: the listener only fires on CHANGES.
        OllamaChatPanel initialChat = activeChat();
        if (initialChat != null) {
            catalogForTabs.setActiveSessionId(initialChat.getSessionId().toString());
        }
        // The WRITING port: a new chat + its agent, exactly like the user doing it by hand (new chat →
        // Questing → agent). Runs on the EDT; the agent session is up when this returns, because
        // selecting the agent activates it synchronously for the now-active tab.
        final com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel hostForLauncher = this.chatWorkspaceHost;
        final ChatWorkspacePanel workspaceForLauncher = this.chatWorkspace;
        this.chatSessionLauncher = new com.aresstack.askai.java8.plugin.host.LocalChatSessionLauncher(
                new com.aresstack.askai.java8.plugin.host.LocalChatSessionLauncher.EdtChatCreator() {
                    public String create(String agentId, String title) {
                        ChatSessionComponent created = workspaceForLauncher.openNewChat();
                        if (created instanceof OllamaChatPanel) {
                            ((OllamaChatPanel) created).setChatTitle(title);
                        }
                        if (hostForLauncher != null && agentId != null && !agentId.trim().isEmpty()) {
                            hostForLauncher.selectAgent(agentId.trim());
                            hostForLauncher.setInteractionMode(
                                    com.aresstack.askai.plugin.host.WorkspaceModeEntry.QUESTING_ID);
                        }
                        return created.getSessionId().toString();
                    }
                });
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
        // The curated OpenNLP sentence-detection models live in their own store; the Model Browser gets an NLP
        // tab to install them, and the AI-models settings gets a per-language selector from the same catalog.
        final com.aresstack.askai.java8.localmodels.LocalNlpModelStore nlpModelStore =
                new com.aresstack.askai.java8.localmodels.LocalNlpModelStore();
        final com.aresstack.askai.java8.localmodels.LocalNlpModelCatalog nlpModelCatalog =
                new com.aresstack.askai.java8.localmodels.LocalNlpModelCatalog(nlpModelStore);
        final NlpModelsPanel nlpModelsPanel = new NlpModelsPanel(
                new com.aresstack.askai.java8.localmodels.ApacheOpenNlpModelCatalogProvider(),
                nlpModelCatalog, nlpModelStore, new Runnable() {
                    public void run() {
                        catalogRefreshService.refresh();
                    }
                });
        final ModelSearchPanel modelSearchPanel =
                new ModelSearchPanel(configurationRepository, askAiService, nlpModelsPanel);
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
        // Central AI-model selection (reranker + embeddings) owned by AskAI for all plugins. The reranker
        // catalog needs the local runtime; when there is none, the panel simply offers no rerank models.
        contentPanel.add(new AiModelsPanel(model,
                askAiService.localRuntimeManager() == null ? null
                        : new com.aresstack.askai.java8.localmodels.LocalRerankerModelCatalog(
                                askAiService.localRuntimeManager()),
                nlpModelCatalog,
                (VirtualOllamaContainerService) ollamaService), AI_MODELS_VIEW);
        // Java 8 port: the extended proxy panel (WScript discovery, TLS trust, HTTP client, IPv6).
        contentPanel.add(new ProxyPanel(configurationRepository), NETWORK_VIEW);
        contentPanel.add(new OllamaAboutPanel(), ABOUT_VIEW);
        // Java2D pipeline editor for audio-processing profiles (shared repository instance).
        this.audioProcessingPanel = new AudioProcessingPanel(audioProfileRepository, applicationState);
        contentPanel.add(audioProcessingPanel, AUDIO_PROCESSING_VIEW);
        // Configuration → Plugins: the real PF4J catalog (enable/disable, details), built after the host so
        // pluginService/enablement exist.
        contentPanel.add(new com.aresstack.askai.plugin.host.PluginManagementPanel(
                pluginService, pluginEnablement, pluginsDirectory), PLUGINS_VIEW);
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

        // The batch local refresh only reloads audio profiles (a local source); audio models arrive via the
        // global catalog refresh below, distributed through applyCatalogSnapshot(...).
        final BatchProfileCatalogService profileCatalog =
                new BatchProfileCatalogService(new Supplier<List<AudioProcessingProfile>>() {
                    public List<AudioProcessingProfile> get() {
                        return audioProfileRepository.findAll();
                    }
                });
        BatchProfileRefresher profileRefresher = new BatchProfileRefresher() {
            public void loadProfiles(Consumer<BatchProfileCatalogLoadedEvent> callback) {
                profileCatalog.loadAsync(callback);
            }
        };

        this.batchPanel = new BatchTranscriptionPanel(
                batchController,
                Collections.<String>emptyList(),
                audioProfileRepository.findAll(),
                profileRefresher);
        contentPanel.add(batchPanel, BATCH_VIEW);
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
