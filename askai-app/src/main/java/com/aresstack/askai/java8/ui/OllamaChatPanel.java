package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.AskAiModel;
import com.aresstack.askai.java8.audio.AudioProfileRepository;
import com.aresstack.askai.java8.audio.format.SupportedAudioFormats;
import com.aresstack.askai.java8.catalog.GlobalCatalogSnapshot;
import com.aresstack.askai.java8.ui.chat.ChatSessionComponent;
import com.aresstack.askai.java8.ui.chat.ChatSessionId;
import com.aresstack.askai.java8.audio.FileAudioProfileRepository;
import com.aresstack.askai.java8.state.ApplicationStateService;
import com.aresstack.askai.java8.client.OllamaChatTurn;
import com.aresstack.askai.java8.service.OllamaService;
import com.aresstack.askai.java8.service.ThinkingOption;
import com.aresstack.askai.java8.speech.AudioModelResolver;
import com.aresstack.askai.java8.speech.ComposerInserter;
import com.aresstack.askai.java8.speech.DefaultRecordingNormalizer;
import com.aresstack.askai.java8.speech.DictationDiagnostics;
import com.aresstack.askai.java8.speech.DictationErrorKind;
import com.aresstack.askai.java8.speech.DictationFailure;
import com.aresstack.askai.java8.speech.DictationListener;
import com.aresstack.askai.java8.speech.DictationResult;
import com.aresstack.askai.java8.speech.DictationState;
import com.aresstack.askai.java8.speech.JavaSoundMicrophoneRecorder;
import com.aresstack.askai.java8.speech.MicrophoneRecorder;
import com.aresstack.askai.java8.speech.OllamaAudioModelResolver;
import com.aresstack.askai.java8.speech.OllamaServerProbe;
import com.aresstack.askai.java8.speech.ReadinessStatus;
import com.aresstack.askai.java8.speech.RecordingNormalizer;
import com.aresstack.askai.java8.speech.ServerProbe;
import com.aresstack.askai.java8.speech.SpeechDictationService;
import com.aresstack.askai.java8.speech.SpeechToTextReadinessService;
import com.aresstack.askai.java8.speech.SpeechTranscriber;
import com.aresstack.askai.java8.stt.AudioCapability;
import com.aresstack.askai.java8.stt.DefaultSpeechToTextService;
import com.aresstack.askai.java8.stt.OllamaSpeechTranscriber;
import com.aresstack.askai.java8.stt.SpeechToTextConfiguration;
import com.aresstack.askai.java8.stt.SpeechToTextService;
import com.aresstack.audio.application.RecordingQualityAnalyzer;
import com.aresstack.audio.dsp.AudioLevelMeter;
import com.aresstack.audio.infrastructure.AvailableAudioDevices;
import com.aresstack.audio.profile.AudioProcessingProfile;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Clean, shippable chat window for the selected Ollama model, with reliable microphone dictation.
 *
 * <p>Chat streaming is unchanged. Dictation (record → normalize → transcribe → insert at the caret) is
 * delegated entirely to the Swing-free {@link SpeechDictationService}: this panel only reads the
 * {@link DictationState}, renders the live level, and applies the recognized text with
 * {@link ComposerInserter}. There is no second microphone path; capture format is negotiated by the
 * service, not forced to 16 kHz here.</p>
 */
public final class OllamaChatPanel extends JPanel implements ChatSessionComponent {

    private static final String MIC_SYSTEM_DEFAULT = "System default";
    private static final String AUDIO_MODEL_AUTOMATIC = "Automatic";
    /** Delete leftover dictation temp files older than this many milliseconds on startup/close. */
    private static final long TEMP_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    /** Application-state keys under which the chat remembers its last selection. */
    private static final String STATE_LAST_MODEL = "chat.lastModel";
    private static final String STATE_MODE = "chat.mode";
    private static final String STATE_AGENT = "chat.agent";
    private static final String STATE_REASONING = "chat.reasoningEffort";

    private final ChatSessionId sessionId;
    private final AskAiModel model;
    private final OllamaService ollamaService;
    private final SpeechToTextService speechToTextService;
    private final AudioProfileRepository audioProfileRepository;
    private final ApplicationStateService applicationState;
    // The persisted model to restore once the model list first loads (consumed once, then cleared).
    private String pendingRestoreModel;

    private final JComboBox<String> modelCombo;
    private final JTextField keepAliveField;
    private final JTextArea systemPromptArea;
    private final ChatTranscript transcript;
    private final ChatComposerPanel composer;
    // The interaction mode shown on the composer's mode selector: "Yapping" (casual chat, default) or the
    // name of the selected agent when in "Questing" mode. selectedAgent is null while yapping.
    private String chatMode = "Yapping";
    private String selectedAgent;
    // Thinking effort ("off"/"low"/"medium"/"high"), only sent when the selected model supports thinking.
    private String reasoningEffort = "off";
    private boolean modelSupportsThinking;
    // The model whose thinking capability is currently being probed, to ignore stale /api/show callbacks.
    private String reasoningProbeModel;
    // Per-turn streaming state for the thinking → answer flow.
    private final ThinkingSummaryProvider thinkingSummaryProvider = new DefaultThinkingSummaryProvider();
    private final StringBuilder streamingThinking = new StringBuilder();
    private com.aresstack.askai.java8.ui.bubble.BubbleTranscriptPanel.ThinkingHandle activeThinking;
    private boolean assistantBubbleStarted;
    private String streamingModelName = "";

    // Dictation controls.
    private final JComboBox<String> audioModelCombo = new JComboBox<String>();
    private final JComboBox<AudioProcessingProfile> audioProfileCombo = new JComboBox<AudioProcessingProfile>();
    private final JComboBox<String> micCombo = new JComboBox<String>();
    private final JButton micRefreshButton = new JButton("Refresh");
    private final JButton testMicButton = new JButton("Test microphone");
    private final JTextArea techDetails = new JTextArea(6, 40);

    private final List<OllamaChatTurn> history = new ArrayList<OllamaChatTurn>();
    private final StringBuilder streamingAssistant = new StringBuilder();
    private OllamaService.Task chatTask;
    // The UI's chat-busy state, decoupled from the technical chatTask handle so the dictation controls
    // re-enable reliably the moment a chat turn ends (regardless of when chatTask is nulled).
    private boolean chatBusy;
    private Timer elapsedTimer;
    private long requestStartedAtMillis;

    // Dictation runtime.
    private final ExecutorService dictationExecutor;
    private final SpeechDictationService dictation;
    private final SpeechToTextReadinessService readiness;
    private final File workDir;
    private DictationState dictationState = DictationState.IDLE;
    private Timer levelTimer;
    private long recordingStartedAtMillis;
    private boolean updatingAudioModelCombo;
    private boolean updatingAudioProfileCombo;
    private boolean updatingMicCombo;
    private boolean checkingReadiness;

    // Existing-file transcription (kept, but decoupled from the microphone path).
    private SpeechToTextService.Task fileTask;
    private boolean fileBusy;
    private File lastAudioDirectory;

    // Runs a one-off microphone test without touching the dictation flow.
    private MicrophoneRecorder.Session micTestSession;
    private Timer micTestTimer;

    /** Callbacks that dictate a "install an audio model" navigation (wired by the frame). */
    public interface InstallAudioModelHandler {
        void openInstall();
    }

    /** Callback to open the Audio processing profile editor from the chat settings (wired by the frame). */
    public interface AudioProcessingSettingsHandler {
        void openAudioProcessing();
    }

    private InstallAudioModelHandler installAudioModelHandler;
    private AudioProcessingSettingsHandler audioProcessingSettingsHandler;

    public OllamaChatPanel(AskAiModel model, OllamaService ollamaService,
                           SpeechToTextService speechToTextService) {
        this(model, ollamaService, speechToTextService, new FileAudioProfileRepository(), null);
    }

    public OllamaChatPanel(AskAiModel model, OllamaService ollamaService,
                           SpeechToTextService speechToTextService,
                           AudioProfileRepository audioProfileRepository) {
        this(model, ollamaService, speechToTextService, audioProfileRepository, null);
    }

    public OllamaChatPanel(AskAiModel model, OllamaService ollamaService,
                           SpeechToTextService speechToTextService,
                           AudioProfileRepository audioProfileRepository,
                           ApplicationStateService applicationState) {
        this(ChatSessionId.create(), model, ollamaService, speechToTextService,
                audioProfileRepository, applicationState);
    }

    public OllamaChatPanel(ChatSessionId sessionId, AskAiModel model, OllamaService ollamaService,
                           SpeechToTextService speechToTextService,
                           AudioProfileRepository audioProfileRepository,
                           ApplicationStateService applicationState) {
        this.sessionId = sessionId == null ? ChatSessionId.create() : sessionId;
        this.model = model;
        this.ollamaService = ollamaService;
        this.speechToTextService = speechToTextService;
        this.audioProfileRepository = audioProfileRepository;
        this.applicationState = applicationState;
        this.modelCombo = new JComboBox<String>();
        this.keepAliveField = new JTextField(model.getDefaultKeepAlive(), 6);
        this.systemPromptArea = new JTextArea("You are a concise local assistant.", 2, 40);
        this.transcript = new ChatTranscript();
        this.transcript.applyColors(model.getChatColors());
        this.composer = new ChatComposerPanel(new ChatComposerPanel.Actions() {
            public void selectModel() {
                openModelPopup();
            }

            public void selectMode() {
                openModePopup();
            }

            public void selectReasoning() {
                openReasoningPopup();
            }

            public void openSettings() {
                openSettingsDialog();
            }

            public void send() {
                sendChat();
            }

            public void stop() {
                stopChat();
            }

            public void toggleRecording() {
                onRecordButton();
            }

            public void discardDictation() {
                onDiscardButton();
            }

            public void retryTranscription() {
                retryDictation();
            }

            public void saveRecording() {
                OllamaChatPanel.this.saveRecording();
            }

            public void installAudioModel() {
                openInstallAudioModel();
            }

            public void transcribeAudioFile() {
                onAudioFileAction();
            }
        });

        this.dictationExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory());
        this.workDir = new File(System.getProperty("java.io.tmpdir"), "askai-speech");
        this.dictation = buildDictationService();
        Supplier<String> baseUrl = new Supplier<String>() {
            public String get() {
                return model.getOllamaBaseUrl();
            }
        };
        this.readiness = new SpeechToTextReadinessService(new OllamaServerProbe(baseUrl), audioModelResolver());

        buildUserInterface();
        restoreChatPreferences();
        setBusy(false);
        showEmptyState();
        cleanupOldRecordings();
        refreshModels();
        refreshMicrophones();
    }

    /**
     * Restores the mode, agent and thinking effort remembered in the application state and arms the last
     * model for restoration once the model list loads. Nothing is persisted here; only user actions write.
     */
    private void restoreChatPreferences() {
        if (applicationState == null) {
            return;
        }
        String mode = applicationState.get(STATE_MODE, YAPPING_MODE);
        String agent = applicationState.get(STATE_AGENT, null);
        if (YAPPING_MODE.equals(mode) || agent == null || agent.trim().isEmpty()) {
            chatMode = YAPPING_MODE;
            selectedAgent = null;
        } else {
            chatMode = mode;
            selectedAgent = agent;
        }
        composer.setModeName(chatMode);

        String effort = applicationState.get(STATE_REASONING, "off");
        reasoningEffort = isKnownReasoningLevel(effort) ? effort : "off";
        composer.setReasoningName(reasoningLabel(reasoningEffort));

        pendingRestoreModel = applicationState.get(STATE_LAST_MODEL, null);
    }

    private static boolean isKnownReasoningLevel(String level) {
        for (String known : REASONING_LEVELS) {
            if (known.equals(level)) {
                return true;
            }
        }
        return false;
    }

    /** @return the remembered model to select, if it is present in the freshly loaded list; consumed once. */
    private String consumePendingRestoreModel(List<String> names) {
        String wanted = pendingRestoreModel;
        pendingRestoreModel = null;
        return wanted != null && names.contains(wanted) ? wanted : null;
    }

    private void rememberState(String key, String value) {
        if (applicationState != null) {
            applicationState.putAndSave(key, value);
        }
    }

    private boolean windowCleanupInstalled;

    @Override
    public void addNotify() {
        super.addNotify();
        if (windowCleanupInstalled) {
            return;
        }
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            windowCleanupInstalled = true;
            window.addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent event) {
                    shutdownDictation();
                }
            });
        }
    }

    /** Lets the frame wire the "Install audio model" action to a screen switch. */
    public void setInstallAudioModelHandler(InstallAudioModelHandler handler) {
        this.installAudioModelHandler = handler;
    }

    public void setAudioProcessingSettingsHandler(AudioProcessingSettingsHandler handler) {
        this.audioProcessingSettingsHandler = handler;
    }

    // ------------------------------------------------------------------ dictation wiring

    private AudioModelResolver audioModelResolver() {
        Supplier<String> baseUrl = new Supplier<String>() {
            public String get() {
                return model.getOllamaBaseUrl();
            }
        };
        Supplier<String> lastModel = new Supplier<String>() {
            public String get() {
                return model.getSpeechToTextConfiguration().getLastAudioModel();
            }
        };
        return new OllamaAudioModelResolver(baseUrl, lastModel);
    }

    private SpeechDictationService buildDictationService() {
        Supplier<String> baseUrl = new Supplier<String>() {
            public String get() {
                return model.getOllamaBaseUrl();
            }
        };
        Supplier<Integer> timeout = new Supplier<Integer>() {
            public Integer get() {
                return model.getSpeechToTextConfiguration().getTimeoutSeconds();
            }
        };
        MicrophoneRecorder recorder = new JavaSoundMicrophoneRecorder();
        // Resolve the selected transcription profile just before EACH recording, so saved profile edits
        // take effect on the next capture without restarting or rebuilding the chat panel. An unknown or
        // missing id falls back to the built-in default inside DefaultRecordingNormalizer.
        RecordingNormalizer normalizer = new DefaultRecordingNormalizer(new Supplier<AudioProcessingProfile>() {
            public AudioProcessingProfile get() {
                return audioProfileRepository.findById(
                        model.getSpeechToTextConfiguration().getAudioProcessingProfileId());
            }
        });
        AudioModelResolver resolver = audioModelResolver();
        SpeechTranscriber transcriber = new OllamaSpeechTranscriber(baseUrl, timeout);
        ServerProbe probe = new OllamaServerProbe(baseUrl);
        DictationListener listener = new DictationListener() {
            public void onState(final DictationState state, final String message) {
                onUi(new Runnable() {
                    public void run() {
                        onDictationState(state, message);
                    }
                });
            }

            public void onResult(final DictationResult result) {
                onUi(new Runnable() {
                    public void run() {
                        onDictationResult(result);
                    }
                });
            }

            public void onFailure(final DictationFailure failure) {
                onUi(new Runnable() {
                    public void run() {
                        onDictationFailure(failure);
                    }
                });
            }
        };
        return new SpeechDictationService(dictationExecutor, recorder, normalizer, resolver, transcriber,
                RecordingQualityAnalyzer.withDefaults(), workDir, probe, listener);
    }

    // ------------------------------------------------------------------ UI construction

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(transcript.getComponent(), BorderLayout.CENTER);
        add(buildBottomArea(), BorderLayout.SOUTH);
    }

    /**
     * The bottom area of a chat: the composer, with the (collapsed) Technical details directly below it,
     * both full width. There is no top toolbar anymore — New chat is the workspace's "+" tab and model
     * refresh is the global button in the menu bar.
     */
    private JComponent buildBottomArea() {
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(buildComposer(), BorderLayout.NORTH);
        bottom.add(new CollapsiblePanel("Technical details", buildTechnicalDetails(), false), BorderLayout.SOUTH);
        return bottom;
    }

    // ------------------------------------------------------------------ ChatSessionComponent

    public ChatSessionId getSessionId() {
        return sessionId;
    }

    public java.awt.Component getComponent() {
        return this;
    }

    /** Release this session's resources when its tab closes: abort the chat, dictation and file work. */
    public void disposeSession() {
        stopChat();
        shutdownDictation();
    }

    /** The always-available (collapsed) technical log shown in the header. */
    private JComponent buildTechnicalDetails() {
        techDetails.setEditable(false);
        techDetails.setLineWrap(true);
        techDetails.setWrapStyleWord(true);
        JScrollPane techScroll = new JScrollPane(techDetails);
        techScroll.setPreferredSize(new Dimension(techScroll.getPreferredSize().width, 140));
        return techScroll;
    }

    /** The chat settings (system prompt, keep-alive, audio model, microphone) shown in the gear dialog. */
    private JComponent buildSettingsContent() {
        JPanel params = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        params.add(new JLabel("keep_alive"));
        params.add(keepAliveField);
        params.add(new JLabel("Audio model"));
        audioModelCombo.setEditable(false); // only /api/show-verified models, never free text
        audioModelCombo.setPreferredSize(new Dimension(200, audioModelCombo.getPreferredSize().height));
        audioModelCombo.addActionListener(event -> persistAudioModelSelection());
        params.add(audioModelCombo);

        JPanel profileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        profileRow.add(new JLabel("Transcription profile"));
        audioProfileCombo.setPreferredSize(new Dimension(240, audioProfileCombo.getPreferredSize().height));
        audioProfileCombo.setToolTipText("Choose the audio-processing profile used for microphone transcription.");
        audioProfileCombo.addActionListener(event -> persistAudioProfileSelection());
        profileRow.add(audioProfileCombo);
        JButton editProfilesButton = new JButton("Edit profiles…");
        editProfilesButton.addActionListener(event -> openAudioProcessingSettings());
        profileRow.add(editProfilesButton);

        JPanel micRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        micRow.add(new JLabel("Microphone"));
        micCombo.setPreferredSize(new Dimension(240, micCombo.getPreferredSize().height));
        micCombo.addActionListener(event -> persistMicrophoneSelection());
        micRow.add(micCombo);
        micRefreshButton.addActionListener(event -> refreshMicrophones());
        micRow.add(micRefreshButton);
        testMicButton.addActionListener(event -> testMicrophone());
        micRow.add(testMicButton);

        JPanel system = new JPanel(new BorderLayout(6, 2));
        system.setBorder(BorderFactory.createTitledBorder("System prompt"));
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        system.add(new JScrollPane(systemPromptArea), BorderLayout.CENTER);

        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        params.setAlignmentX(Component.LEFT_ALIGNMENT);
        profileRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        micRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(params);
        top.add(profileRow);
        top.add(micRow);

        JPanel settings = new JPanel(new BorderLayout(4, 4));
        settings.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        settings.add(top, BorderLayout.NORTH);
        settings.add(system, BorderLayout.CENTER);
        settings.add(buildColorSettings(), BorderLayout.SOUTH);
        return settings;
    }

    /** Color pickers for the chat bubble colors — persisted and applied to the transcript immediately. */
    private JComponent buildColorSettings() {
        JPanel colors = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        colors.setBorder(BorderFactory.createTitledBorder("Colors"));
        colors.add(colorButton("Background",
                () -> model.getChatColors().getTranscriptBackground(),
                c -> model.setChatColors(model.getChatColors().withTranscriptBackground(c))));
        colors.add(colorButton("Your bubble",
                () -> model.getChatColors().getUserBackground(),
                c -> model.setChatColors(model.getChatColors().withUserBackground(c))));
        colors.add(colorButton("Your text",
                () -> model.getChatColors().getUserForeground(),
                c -> model.setChatColors(model.getChatColors().withUserForeground(c))));
        colors.add(colorButton("Reply bubble",
                () -> model.getChatColors().getAssistantBackground(),
                c -> model.setChatColors(model.getChatColors().withAssistantBackground(c))));
        colors.add(colorButton("Reply text",
                () -> model.getChatColors().getAssistantForeground(),
                c -> model.setChatColors(model.getChatColors().withAssistantForeground(c))));
        JButton reset = new JButton("Reset");
        reset.setToolTipText("Restore the default chat colors");
        reset.addActionListener(event -> {
            model.setChatColors(com.aresstack.askai.java8.config.ChatColorSettings.defaults());
            model.saveSettings();
            transcript.applyColors(model.getChatColors());
            for (Runnable refresh : colorSwatchRefreshers) {
                refresh.run();
            }
        });
        colors.add(reset);
        return colors;
    }

    /** A labelled button with a swatch of its current color; picking a new one persists + applies it live. */
    private JButton colorButton(final String label, final Supplier<Color> current, final Consumer<Color> apply) {
        final JButton button = new JButton(label, swatchIcon(current.get()));
        button.addActionListener(event -> {
            Color chosen = JColorChooser.showDialog(OllamaChatPanel.this, "Choose color: " + label, current.get());
            if (chosen != null) {
                apply.accept(chosen);
                model.saveSettings();
                transcript.applyColors(model.getChatColors());
                button.setIcon(swatchIcon(chosen));
            }
        });
        colorSwatchRefreshers.add(new Runnable() {
            public void run() {
                button.setIcon(swatchIcon(current.get()));
            }
        });
        return button;
    }

    /** @return a small square icon filled with {@code color} and a subtle border, for the color buttons. */
    private static Icon swatchIcon(final Color color) {
        return new Icon() {
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(color);
                g.fillRect(x, y, 14, 14);
                g.setColor(Color.GRAY);
                g.drawRect(x, y, 14, 14);
            }

            public int getIconWidth() {
                return 15;
            }

            public int getIconHeight() {
                return 15;
            }
        };
    }

    private javax.swing.JDialog settingsDialog;
    /** One per color button: re-reads its current color into its swatch (used by "Reset"). */
    private final List<Runnable> colorSwatchRefreshers = new ArrayList<Runnable>();

    /** Opens the (modeless) Chat settings dialog behind the composer's gear icon. */
    private void openSettingsDialog() {
        refreshAudioProfiles(); // reload so profiles saved in the editor appear immediately
        if (settingsDialog == null) {
            java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
            settingsDialog = new javax.swing.JDialog(
                    owner instanceof java.awt.Frame ? (java.awt.Frame) owner : null, "Chat settings", false);
            JComponent content = buildSettingsContent();
            settingsDialog.setContentPane(new JScrollPane(content));
            settingsDialog.pack();
            settingsDialog.setSize(new Dimension(Math.max(460, settingsDialog.getWidth()), 360));
            settingsDialog.setLocationRelativeTo(this);
        }
        settingsDialog.setVisible(true);
        settingsDialog.toFront();
    }

    private JComponent buildComposer() {
        composer.setChatStatus("Select a model and start chatting.");
        composer.setDictationStatus(" ");
        refreshDictationControls();
        return composer;
    }

    // ------------------------------------------------------------------ global catalog snapshot

    /**
     * Apply a globally-refreshed catalog to this chat without re-querying Ollama, preserving this tab's own
     * selection (model by name, audio model / profile by their persisted ids). Only parts that loaded
     * successfully are applied, so a partial failure never clears a working list.
     */
    public void applyCatalogSnapshot(GlobalCatalogSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (snapshot.isModelsLoaded()) {
            applyModelNames(snapshot.getChatModels());
        }
        if (snapshot.isAudioModelsLoaded()) {
            setAudioModelItems(snapshot.getAudioModels());
        }
        if (snapshot.isProfilesLoaded()) {
            refreshAudioProfiles(); // profiles are local; re-read the (just-refreshed) repository
        }
    }

    /** Repopulate the (off-screen) model selector, keeping the current model selected when it survives. */
    private void applyModelNames(List<String> names) {
        Object previous = modelCombo.getSelectedItem();
        modelCombo.removeAllItems();
        for (String name : names) {
            modelCombo.addItem(name);
        }
        String restored = consumePendingRestoreModel(names);
        if (restored != null) {
            modelCombo.setSelectedItem(restored);
        } else if (previous != null) {
            modelCombo.setSelectedItem(previous);
        }
        composer.setModelName((String) modelCombo.getSelectedItem());
    }

    // ------------------------------------------------------------------ chat (unchanged behaviour)

    private void refreshModels() {
        setStatus("Loading models from " + model.getOllamaBaseUrl() + " ...");
        ollamaService.listModelNames(new OllamaService.ModelNamesListener() {
            public void onModelNames(final List<String> names) {
                onUi(new Runnable() {
                    public void run() {
                        applyModelNames(names);
                        refreshAudioModels(names);
                        refreshReasoningForModel((String) modelCombo.getSelectedItem());
                        setStatus(names.isEmpty() ? "No models installed. Open Install to add one." : " ");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        setStatus("Server not reachable. Check Connections.");
                        transcript.appendInfo("Connection error: " + ex.getMessage());
                    }
                });
            }
        });
    }

    /** Selects {@code modelName} as the active chat model without touching the conversation. */
    public void useModel(String modelName) {
        if (modelName == null || modelName.trim().length() == 0) {
            return;
        }
        boolean present = false;
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            if (modelName.equals(modelCombo.getItemAt(i))) {
                present = true;
                break;
            }
        }
        if (!present) {
            modelCombo.addItem(modelName);
        }
        modelCombo.setSelectedItem(modelName);
        composer.setModelName(modelName);
        refreshReasoningForModel(modelName);
        rememberState(STATE_LAST_MODEL, modelName);
        transcript.appendInfo("Now chatting with " + modelName + ".");
        setStatus("Model set to " + modelName + ".");
    }

    /** Opens the ChatGPT-style model picker anchored to the composer's model button. */
    private void openModelPopup() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        String current = (String) modelCombo.getSelectedItem();
        if (modelCombo.getItemCount() == 0) {
            javax.swing.JMenuItem none = new javax.swing.JMenuItem("No models — open Install");
            none.addActionListener(event -> openInstallAudioModel());
            menu.add(none);
        } else {
            for (int i = 0; i < modelCombo.getItemCount(); i++) {
                final String name = modelCombo.getItemAt(i);
                javax.swing.JRadioButtonMenuItem item =
                        new javax.swing.JRadioButtonMenuItem(name, name.equals(current));
                item.addActionListener(event -> {
                    modelCombo.setSelectedItem(name);
                    composer.setModelName(name);
                    refreshReasoningForModel(name);
                    rememberState(STATE_LAST_MODEL, name);
                });
                menu.add(item);
            }
        }
        menu.addSeparator();
        javax.swing.JMenuItem refresh = new javax.swing.JMenuItem("Refresh models");
        refresh.addActionListener(event -> refreshModels());
        menu.add(refresh);
        JComponent anchor = composer.getModelButton();
        menu.show(anchor, 0, anchor.getHeight());
    }

    /** The default, casual chat mode label (a gamified name for "just talking"). */
    private static final String YAPPING_MODE = "Yapping";

    /**
     * The in-composer mode selector: "Yapping" is the default casual chat; "Questing" is the agent mode
     * and carries a submenu of installed agents (none yet). Selecting an agent switches into that mode.
     */
    private void openModePopup() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();

        javax.swing.JRadioButtonMenuItem yapping =
                new javax.swing.JRadioButtonMenuItem(YAPPING_MODE, YAPPING_MODE.equals(chatMode));
        yapping.addActionListener(event -> selectYappingMode());
        menu.add(yapping);

        // "Questing" is a submenu (the arrow) listing the installed agents to run.
        javax.swing.JMenu questing = new javax.swing.JMenu("Questing");
        List<String> agents = installedAgentNames();
        if (agents.isEmpty()) {
            javax.swing.JMenuItem none = new javax.swing.JMenuItem("No agents installed");
            none.setEnabled(false);
            questing.add(none);
        } else {
            for (final String agent : agents) {
                javax.swing.JRadioButtonMenuItem item =
                        new javax.swing.JRadioButtonMenuItem(agent, agent.equals(selectedAgent));
                item.addActionListener(event -> selectAgentMode(agent));
                questing.add(item);
            }
        }
        menu.add(questing);

        JComponent anchor = composer.getModeButton();
        menu.show(anchor, 0, anchor.getHeight());
    }

    /** @return the names of installed agents; empty until agent support ships. */
    private List<String> installedAgentNames() {
        return Collections.emptyList();
    }

    private void selectYappingMode() {
        chatMode = YAPPING_MODE;
        selectedAgent = null;
        composer.setModeName(YAPPING_MODE);
        rememberState(STATE_MODE, YAPPING_MODE);
        rememberState(STATE_AGENT, null);
    }

    private void selectAgentMode(String agent) {
        chatMode = agent;
        selectedAgent = agent;
        composer.setModeName(agent);
        rememberState(STATE_MODE, agent);
        rememberState(STATE_AGENT, agent);
    }

    // ------------------------------------------------------------------ reasoning effort

    /** The thinking-effort levels offered when the selected model supports thinking. */
    private static final String[] REASONING_LEVELS = {"off", "low", "medium", "high"};

    /** The effort selector: Off / Low / Medium / High. Only reachable when the model supports thinking. */
    private void openReasoningPopup() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        for (final String level : REASONING_LEVELS) {
            javax.swing.JRadioButtonMenuItem item =
                    new javax.swing.JRadioButtonMenuItem(reasoningLabel(level), level.equals(reasoningEffort));
            item.addActionListener(event -> selectReasoningEffort(level));
            menu.add(item);
        }
        JComponent anchor = composer.getReasoningButton();
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void selectReasoningEffort(String level) {
        reasoningEffort = level;
        composer.setReasoningName(reasoningLabel(level));
        rememberState(STATE_REASONING, level);
    }

    private static String reasoningLabel(String level) {
        return "Think: " + Character.toUpperCase(level.charAt(0)) + level.substring(1);
    }

    /**
     * Probes the given model's {@code /api/show} capabilities and enables the reasoning selector only when
     * the model supports thinking. Stale callbacks (the user switched models meanwhile) are ignored.
     */
    private void refreshReasoningForModel(final String modelName) {
        reasoningProbeModel = modelName;
        if (modelName == null || modelName.trim().isEmpty()) {
            applyThinkingSupport(modelName, false);
            return;
        }
        ollamaService.getModelInfo(modelName, new OllamaService.ModelInfoListener() {
            public void onModelInfo(final com.aresstack.askai.java8.client.OllamaModelInfoView info) {
                onUi(new Runnable() {
                    public void run() {
                        boolean supported = ModelCapability.fromOllamaTags(info.getCapabilities())
                                .contains(ModelCapability.THINKING);
                        applyThinkingSupport(modelName, supported);
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        applyThinkingSupport(modelName, false); // unknown → keep it greyed out
                    }
                });
            }
        });
    }

    private void applyThinkingSupport(String modelName, boolean supported) {
        if (modelName != null && !modelName.equals(reasoningProbeModel)) {
            return; // a newer selection is in flight; ignore this result
        }
        modelSupportsThinking = supported;
        composer.setReasoningEnabled(supported);
        if (!supported) {
            reasoningEffort = "off";
            composer.setReasoningName(reasoningLabel("off"));
        }
    }

    private void showEmptyState() {
        transcript.appendInfo("New conversation. Type a message below and press Enter.");
    }

    private void sendChat() {
        if (!composer.isSendEnabled()) {
            return;
        }
        final String modelName = (String) modelCombo.getSelectedItem();
        if (modelName == null || modelName.trim().isEmpty()) {
            setStatus("No model selected. Open Models or Install first.");
            return;
        }
        final String userPrompt = composer.getMessage().trim();
        if (userPrompt.isEmpty()) {
            setStatus("Write a message before sending.");
            return;
        }

        if (transcript.isEmpty() || history.isEmpty()) {
            transcript.clear();
        }
        composer.clearMessage();
        transcript.appendUser(userPrompt);
        history.add(OllamaChatTurn.user(userPrompt));

        // Do not open an assistant bubble yet: thinking (if any) opens a green thinking bubble first, and
        // the answer bubble only appears when real content arrives.
        streamingAssistant.setLength(0);
        streamingThinking.setLength(0);
        activeThinking = null;
        assistantBubbleStarted = false;
        streamingModelName = modelName;
        startElapsedTimer();
        setBusy(true);

        // Only request thinking when the model supports it and a level other than "off" is chosen.
        ThinkingOption thinking = modelSupportsThinking && !"off".equals(reasoningEffort)
                ? ThinkingOption.ofLevel(reasoningEffort) : ThinkingOption.defaultOption();
        OllamaService.ChatRequest request = new OllamaService.ChatRequest(
                modelName, keepAliveField.getText(), buildConversation(), thinking);
        chatTask = ollamaService.streamChat(request, new OllamaService.ChatListener() {
            public void onThinkingDelta(final String delta) {
                onUi(new Runnable() {
                    public void run() {
                        handleThinkingDelta(delta);
                    }
                });
            }

            public void onContent(final String content) {
                onUi(new Runnable() {
                    public void run() {
                        handleContentDelta(content);
                    }
                });
            }

            public void onStatus(final String status) {
                onUi(new Runnable() {
                    public void run() {
                        setStatus(status);
                    }
                });
            }

            public void onComplete(final OllamaService.ChatResult result) {
                onUi(new Runnable() {
                    public void run() {
                        finishTurn(result);
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        handleStreamError(ex);
                    }
                });
            }
        });
    }

    /** First non-empty thinking delta opens the green thinking bubble; further deltas stream into it. */
    private void handleThinkingDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (activeThinking == null) {
            activeThinking = transcript.startAssistantThinking(streamingModelName);
        }
        streamingThinking.append(delta);
        transcript.appendAssistantThinkingDelta(activeThinking, delta);
    }

    /** First real content ends thinking (burst + rising summary), then streams into the answer bubble. */
    private void handleContentDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        endThinkingIfActive();
        if (!assistantBubbleStarted) {
            transcript.startAssistant(streamingModelName);
            assistantBubbleStarted = true;
        }
        streamingAssistant.append(content);
        transcript.appendAssistantDelta(content);
    }

    private void endThinkingIfActive() {
        if (activeThinking != null) {
            transcript.completeAssistantThinking(activeThinking,
                    thinkingSummaryProvider.createSummary(streamingThinking.toString()));
            activeThinking = null;
        }
    }

    private void finishTurn(OllamaService.ChatResult result) {
        stopElapsedTimer();
        setBusy(false);
        chatTask = null;
        // Thinking that never produced an answer: close it neutrally, do not leave an empty answer bubble.
        endThinkingIfActive();

        String assistantText = streamingAssistant.toString();
        if (!assistantBubbleStarted && assistantText.trim().isEmpty() && !result.getFallbackText().isEmpty()) {
            // The answer arrived only as a fallback (no streamed deltas): open and fill a bubble now.
            assistantText = result.getFallbackText();
            transcript.startAssistant(streamingModelName);
            assistantBubbleStarted = true;
            transcript.appendAssistantDelta(assistantText);
        }

        if (assistantBubbleStarted) {
            transcript.finishAssistant();
            history.add(OllamaChatTurn.assistant(assistantText));
            if (result.hasMetrics()) {
                setStatus(String.format("Ready · %d tokens · %.1f tok/s",
                        result.getEvalCount(), result.tokensPerSecond()));
            } else {
                setStatus("Ready.");
            }
        } else {
            // Thinking-only turn: no answer, no empty bubble — a neutral status instead.
            setStatus("Thinking finished — no answer returned.");
        }
    }

    /**
     * Output-format contract appended to every system turn so the transcript can render Markdown/Mermaid.
     * Models otherwise tend to wrap the whole reply in a ```markdown fence or nest a Mermaid fence inside
     * one, which then shows as raw code instead of a rendered document/diagram.
     */
    private static final String OUTPUT_FORMAT_HINT =
            "Formatting: reply in normal Markdown directly. Do not wrap the whole response in a ```markdown "
            + "or ```md fence. For a diagram, emit a direct fenced Mermaid block:\n"
            + "```mermaid\ngraph TD\n  A --> B\n```\n"
            + "Never put a Mermaid fence inside a markdown fence.";

    private List<OllamaChatTurn> buildConversation() {
        List<OllamaChatTurn> conversation = new ArrayList<OllamaChatTurn>();
        String system = systemPromptArea.getText();
        String combined = system == null || system.trim().isEmpty()
                ? OUTPUT_FORMAT_HINT
                : system.trim() + "\n\n" + OUTPUT_FORMAT_HINT;
        conversation.add(OllamaChatTurn.system(combined));
        conversation.addAll(history);
        return conversation;
    }

    private void stopChat() {
        if (chatTask != null) {
            chatTask.cancel();
            chatTask = null;
            stopElapsedTimer();
            setBusy(false);
            if (activeThinking != null) {
                transcript.cancelAssistantThinking(activeThinking, "Stopped");
                activeThinking = null;
            }
            if (assistantBubbleStarted) {
                transcript.appendAssistantDelta(" [stopped]");
                transcript.finishAssistant();
                if (!streamingAssistant.toString().trim().isEmpty()) {
                    history.add(OllamaChatTurn.assistant(streamingAssistant.toString()));
                }
            }
            setStatus("Stopped.");
        }
    }

    private void handleStreamError(Exception ex) {
        stopElapsedTimer();
        setBusy(false);
        chatTask = null;
        if (activeThinking != null) {
            transcript.cancelAssistantThinking(activeThinking, "Failed");
            activeThinking = null;
        }
        if (assistantBubbleStarted) {
            transcript.appendAssistantDelta("[error: " + ex.getMessage() + "]");
            transcript.finishAssistant();
        }
        setStatus("Chat failed.");
    }

    // ------------------------------------------------------------------ audio-model dropdown

    /**
     * Populate the audio-model dropdown with {@code Automatic} first, then the models whose concrete
     * installation reports the exact "audio" capability via {@code /api/show} (never free text). The
     * current selection is restored from the persisted configuration.
     */
    private void refreshAudioModels(final List<String> names) {
        setAudioModelItems(new ArrayList<String>()); // Automatic only until queried
        if (names.isEmpty()) {
            refreshDictationControls();
            return;
        }
        final List<String> audioCapable = new ArrayList<String>();
        final int[] remaining = {names.size()};
        for (final String name : names) {
            ollamaService.getModelInfo(name, new OllamaService.ModelInfoListener() {
                public void onModelInfo(final com.aresstack.askai.java8.client.OllamaModelInfoView info) {
                    onUi(new Runnable() {
                        public void run() {
                            if (AudioCapability.isAudioCapable(info.getCapabilities())) {
                                audioCapable.add(name);
                            }
                            done();
                        }
                    });
                }

                public void onError(Exception ex) {
                    onUi(new Runnable() {
                        public void run() {
                            done();
                        }
                    });
                }

                private void done() {
                    remaining[0]--;
                    if (remaining[0] == 0) {
                        setAudioModelItems(audioCapable);
                        refreshDictationControls();
                    }
                }
            });
        }
    }

    private void setAudioModelItems(List<String> audioModels) {
        updatingAudioModelCombo = true;
        try {
            SpeechToTextConfiguration stt = model.getSpeechToTextConfiguration();
            audioModelCombo.removeAllItems();
            audioModelCombo.addItem(AUDIO_MODEL_AUTOMATIC);
            for (String name : audioModels) {
                audioModelCombo.addItem(name);
            }
            String desired = AUDIO_MODEL_AUTOMATIC;
            if (!stt.isAudioModelAutomatic() && audioModels.contains(stt.getModelName())) {
                desired = stt.getModelName();
            }
            audioModelCombo.setSelectedItem(desired);
        } finally {
            updatingAudioModelCombo = false;
        }
    }

    private void persistAudioModelSelection() {
        if (updatingAudioModelCombo) {
            return;
        }
        Object selected = audioModelCombo.getSelectedItem();
        String choice = selected == null ? AUDIO_MODEL_AUTOMATIC : String.valueOf(selected);
        SpeechToTextConfiguration current = model.getSpeechToTextConfiguration();
        SpeechToTextConfiguration updated = AUDIO_MODEL_AUTOMATIC.equals(choice)
                ? current.withAudioModelAutomatic(true).withModelName("")
                : current.withAudioModelAutomatic(false).withModelName(choice);
        model.setSpeechToTextConfiguration(updated);
        model.saveSettings();
    }

    /** Reloads the transcription-profile combo from the repository and selects the persisted profile. */
    private void refreshAudioProfiles() {
        updatingAudioProfileCombo = true;
        try {
            String selectedId = model.getSpeechToTextConfiguration().getAudioProcessingProfileId();
            audioProfileCombo.removeAllItems();
            List<AudioProcessingProfile> profiles = audioProfileRepository.findAll();
            AudioProcessingProfile selected = null;
            for (int i = 0; i < profiles.size(); i++) {
                AudioProcessingProfile profile = profiles.get(i);
                audioProfileCombo.addItem(profile);
                if (profile.getId().equals(selectedId)) {
                    selected = profile;
                }
            }
            if (selected == null && audioProfileCombo.getItemCount() > 0) {
                selected = audioProfileCombo.getItemAt(0); // deleted/unknown id → fall back to the first
            }
            audioProfileCombo.setSelectedItem(selected);
        } finally {
            updatingAudioProfileCombo = false;
        }
    }

    /** Persists the chosen transcription profile id; the next recording resolves it fresh. */
    private void persistAudioProfileSelection() {
        if (updatingAudioProfileCombo) {
            return;
        }
        AudioProcessingProfile selected = (AudioProcessingProfile) audioProfileCombo.getSelectedItem();
        if (selected == null) {
            return;
        }
        SpeechToTextConfiguration current = model.getSpeechToTextConfiguration();
        model.setSpeechToTextConfiguration(current.withAudioProcessingProfileId(selected.getId()));
        model.saveSettings();
    }

    /** Closes the settings dialog and opens the Audio processing profile editor page. */
    private void openAudioProcessingSettings() {
        if (settingsDialog != null) {
            settingsDialog.setVisible(false);
        }
        if (audioProcessingSettingsHandler != null) {
            audioProcessingSettingsHandler.openAudioProcessing();
        }
    }

    /** @return the requested model for the resolver: "Automatic" or a specific verified model. */
    private String requestedAudioModel() {
        Object selected = audioModelCombo.getSelectedItem();
        return selected == null ? AUDIO_MODEL_AUTOMATIC : String.valueOf(selected);
    }

    // ------------------------------------------------------------------ microphone dropdown + test

    private void refreshMicrophones() {
        updatingMicCombo = true;
        try {
            String persisted = model.getSpeechToTextConfiguration().getMicrophoneDeviceId();
            micCombo.removeAllItems();
            micCombo.addItem(MIC_SYSTEM_DEFAULT);
            List<String> devices;
            try {
                devices = AvailableAudioDevices.listCaptureDeviceNames();
            } catch (Exception ex) {
                devices = new ArrayList<String>();
            }
            for (String device : devices) {
                micCombo.addItem(device);
            }
            // Fall back to System default when the saved device is gone.
            if (persisted != null && !persisted.isEmpty() && devices.contains(persisted)) {
                micCombo.setSelectedItem(persisted);
            } else {
                micCombo.setSelectedItem(MIC_SYSTEM_DEFAULT);
            }
        } finally {
            updatingMicCombo = false;
        }
    }

    private void persistMicrophoneSelection() {
        if (updatingMicCombo) {
            return;
        }
        SpeechToTextConfiguration current = model.getSpeechToTextConfiguration();
        model.setSpeechToTextConfiguration(current.withMicrophoneDeviceId(selectedMicDeviceId()));
        model.saveSettings();
    }

    /** @return the selected capture device id, or "" for the system default. */
    private String selectedMicDeviceId() {
        Object selected = micCombo.getSelectedItem();
        String value = selected == null ? "" : String.valueOf(selected);
        return MIC_SYSTEM_DEFAULT.equals(value) ? "" : value;
    }

    /** Opens the selected device briefly and shows the input level; nothing is sent to Ollama. */
    private void testMicrophone() {
        if (dictationState != DictationState.IDLE && !dictationState.canStartRecording()) {
            setDictationStatus("Finish the current dictation before testing the microphone.");
            return;
        }
        if (micTestSession != null) {
            stopMicTest("Microphone test stopped.");
            return;
        }
        final String device = selectedMicDeviceId();
        testMicButton.setText("Stop test");
        setDictationStatus("Testing microphone …");
        dictationExecutor.execute(new Runnable() {
            public void run() {
                MicrophoneRecorder.Session session;
                try {
                    session = new JavaSoundMicrophoneRecorder().start(device, workDir);
                } catch (final Exception ex) {
                    onUi(new Runnable() {
                        public void run() {
                            testMicButton.setText("Test microphone");
                            setDictationStatus("Microphone test failed: " + messageOf(ex));
                        }
                    });
                    return;
                }
                final MicrophoneRecorder.Session opened = session;
                onUi(new Runnable() {
                    public void run() {
                        micTestSession = opened;
                        refreshDictationControls();
                        startMicTestTimer(opened.getMeter());
                    }
                });
            }
        });
    }

    private void startMicTestTimer(final AudioLevelMeter meter) {
        final long endAt = System.currentTimeMillis() + 4000;
        micTestTimer = new Timer(100, event -> {
            int peak = meter.getPeak();
            composer.setAudioLevel(scaleLevel(peak));
            boolean signal = meter.getOverallRms() > 30 || peak > 500;
            setDictationStatus(signal ? "Microphone test: signal detected." : "Microphone test: no signal.");
            if (System.currentTimeMillis() > endAt) {
                stopMicTest(signal ? "Microphone works." : "Microphone test finished — no signal detected.");
            }
        });
        micTestTimer.start();
    }

    private void stopMicTest(String message) {
        if (micTestTimer != null) {
            micTestTimer.stop();
            micTestTimer = null;
        }
        final MicrophoneRecorder.Session session = micTestSession;
        micTestSession = null;
        testMicButton.setText("Test microphone");
        composer.setAudioLevel(0);
        setDictationStatus(message);
        refreshDictationControls();
        if (session != null) {
            dictationExecutor.execute(new Runnable() {
                public void run() {
                    session.discard();
                }
            });
        }
    }

    // ------------------------------------------------------------------ dictation control

    private void onRecordButton() {
        switch (dictationState) {
            case RECORDING:
                dictation.stopAndTranscribe(requestedAudioModel(), model.getSpeechToTextConfiguration().getLanguage(), "");
                break;
            case OPENING_MICROPHONE:
            case FINALIZING_RECORDING:
            case VERIFYING_MODEL:
            case UPLOADING_AUDIO:
            case TRANSCRIBING:
                dictation.cancel();
                break;
            default:
                startDictationWithReadiness();
        }
    }

    private void onDiscardButton() {
        if (dictationState == DictationState.RECORDING) {
            dictation.discard();
        } else if (isDictationInFlight()) {
            dictation.cancel();
        } else if (micTestSession != null) {
            stopMicTest("Microphone test stopped.");
        }
    }

    private boolean isDictationInFlight() {
        return dictationState == DictationState.OPENING_MICROPHONE
                || dictationState == DictationState.FINALIZING_RECORDING
                || dictationState == DictationState.VERIFYING_MODEL
                || dictationState == DictationState.UPLOADING_AUDIO
                || dictationState == DictationState.TRANSCRIBING;
    }

    private void startDictationWithReadiness() {
        if (chatBusy || fileBusy || micTestSession != null) {
            setDictationStatus("Busy — finish the chat / file transcription / mic test first.");
            return;
        }
        checkingReadiness = true;
        refreshDictationControls();
        setDictationStatus("Checking speech-to-text readiness …");
        final String requested = requestedAudioModel();
        dictationExecutor.execute(new Runnable() {
            public void run() {
                final ReadinessStatus status = readiness.check(requested);
                onUi(new Runnable() {
                    public void run() {
                        handleReadiness(status);
                    }
                });
            }
        });
    }

    private void handleReadiness(ReadinessStatus status) {
        checkingReadiness = false;
        if (status == ReadinessStatus.READY) {
            dictation.startRecording(selectedMicDeviceId());
            return;
        }
        switch (status) {
            case SERVER_UNREACHABLE:
                setDictationStatus("Ollama is not reachable — check Connections.");
                break;
            case SERVER_ENDPOINT_UNAVAILABLE:
                setDictationStatus("This Ollama server does not support speech-to-text.");
                break;
            case NO_AUDIO_MODEL:
                setDictationStatus("No audio-capable model installed.");
                break;
            case MODEL_CAPABILITY_UNKNOWN:
                setDictationStatus("The model's audio capability could not be confirmed.");
                break;
            case MODEL_NOT_AUDIO_CAPABLE:
                setDictationStatus("The selected model does not support audio.");
                break;
            default:
                setDictationStatus("Speech-to-text is not ready.");
        }
        refreshDictationControls();
    }

    /** Invalidate the readiness cache after server/model changes (called by the frame). */
    public void invalidateSpeechReadiness() {
        readiness.invalidate();
    }

    private void onDictationState(DictationState state, String message) {
        checkingReadiness = false;
        dictationState = state;
        if (state == DictationState.RECORDING) {
            recordingStartedAtMillis = System.currentTimeMillis();
            startLevelTimer();
        } else {
            stopLevelTimer();
            if (state.isTerminal() || state == DictationState.IDLE) {
                composer.setAudioLevel(0);
            }
        }
        setDictationStatus(message != null ? message : state.getDefaultStatusMessage());
        refreshDictationControls();
    }

    private void onDictationResult(DictationResult result) {
        // Insert at the caret, preserve existing text, never auto-send. The service delivers exactly
        // one terminal callback per operation, so this cannot double-insert.
        JTextArea editor = composer.getEditor();
        ComposerInserter.Insertion insertion = ComposerInserter.insert(
                editor.getText(), editor.getSelectionStart(), editor.getSelectionEnd(), result.getText());
        editor.setText(insertion.getText());
        editor.setCaretPosition(Math.min(insertion.getCaret(), editor.getText().length()));
        composer.focusEditor();
        persistLastAudioModel(result.getModelUsed());
        showDiagnostics(result.getDiagnostics());
        setDictationStatus("Transcription ready. Review the text and press Send.");
        refreshDictationControls();
    }

    private void onDictationFailure(DictationFailure failure) {
        setDictationStatus(describeFailure(failure));
        appendTech("Dictation failed: " + failure.getKind() + (failure.getDetail().isEmpty()
                ? "" : " — " + failure.getDetail()));
        refreshDictationControls();
    }

    private String describeFailure(DictationFailure failure) {
        switch (failure.getKind()) {
            case CANCELLED:
                return "Dictation cancelled.";
            case MICROPHONE_OPEN_FAILED:
                return "Could not open the microphone (" + micLabel() + ").";
            case FINALIZE_FAILED:
                return "Could not finalize the recording.";
            case NORMALIZE_FAILED:
                return "Could not process the recording.";
            case QUALITY_TOO_SHORT:
                return "Recording too short — hold and speak a little longer.";
            case QUALITY_NO_SIGNAL:
                return "No speech detected — check the microphone and try again.";
            case NO_AUDIO_MODEL:
                return "No audio-capable model installed.";
            case MODEL_CAPABILITY_UNKNOWN:
                return "The model's audio capability could not be confirmed.";
            case MODEL_NOT_AUDIO:
                return "The selected model does not support audio.";
            case SERVER_ENDPOINT_UNAVAILABLE:
                return "This Ollama server does not support speech-to-text.";
            case SERVER_UNREACHABLE:
                return "Ollama is not reachable — check Connections.";
            case TRANSCRIPTION_EMPTY:
                return "The model returned no text — try speaking again.";
            case TRANSCRIPTION_FAILED:
            default:
                return "Transcription failed.";
        }
    }

    private String micLabel() {
        String id = selectedMicDeviceId();
        return id.isEmpty() ? "system default" : id;
    }

    private void persistLastAudioModel(String modelUsed) {
        if (modelUsed == null || modelUsed.trim().isEmpty()) {
            return; // a failed attempt must not overwrite the last successful model
        }
        SpeechToTextConfiguration current = model.getSpeechToTextConfiguration();
        if (modelUsed.equals(current.getLastAudioModel())) {
            return;
        }
        model.setSpeechToTextConfiguration(current.withLastAudioModel(modelUsed));
        model.saveSettings();
    }

    // ------------------------------------------------------------------ level meter timer

    private void startLevelTimer() {
        stopLevelTimer();
        levelTimer = new Timer(100, event -> {
            AudioLevelMeter meter = dictation.getActiveMeter();
            long seconds = (System.currentTimeMillis() - recordingStartedAtMillis) / 1000L;
            if (meter == null) {
                setDictationStatus("● Recording — " + formatDuration(seconds));
                return;
            }
            composer.setAudioLevel(scaleLevel(meter.getPeak()));
            boolean signal = meter.getOverallRms() > 30 || meter.getPeak() > 500;
            boolean clipping = meter.getClippedSampleCount() > 0;
            setDictationStatus("● Recording — " + formatDuration(seconds) + " · " + micLabel()
                    + (clipping ? " · CLIPPING" : signal ? " · signal" : " · no signal")
                    + "  (Stop to transcribe · Discard/Esc to cancel)");
        });
        levelTimer.start();
    }

    private void stopLevelTimer() {
        if (levelTimer != null) {
            levelTimer.stop();
            levelTimer = null;
        }
    }

    private static int scaleLevel(int peak) {
        return (int) Math.max(0, Math.min(100, peak * 100L / 32767L));
    }

    private static String formatDuration(long seconds) {
        long safe = Math.max(0, seconds);
        return (safe / 60) + ":" + String.format("%02d", safe % 60);
    }

    // ------------------------------------------------------------------ recovery actions


    private void retryDictation() {
        dictation.retryTranscription();
    }

    private void saveRecording() {
        File source = dictation.savedRecordingSource();
        if (source == null || !source.isFile()) {
            setDictationStatus("No recording to save.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save recording");
        chooser.setSelectedFile(new File("dictation.wav"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return; // cancelling the dialog must not change the dictation state
        }
        File dest = chooser.getSelectedFile();
        try {
            java.nio.file.Files.copy(source.toPath(), dest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            setDictationStatus("Recording saved to " + dest.getName() + ".");
        } catch (Exception ex) {
            setDictationStatus("Could not save recording: " + messageOf(ex));
        }
    }

    private void openInstallAudioModel() {
        if (installAudioModelHandler != null) {
            installAudioModelHandler.openInstall();
        }
    }

    /** Render the complete dictation action state in the integrated composer. */
    private void refreshDictationControls() {
        boolean sttEnabled = model.getSpeechToTextConfiguration().isEnabled();
        boolean idle = dictationState.canStartRecording();
        boolean inFlight = isDictationInFlight();
        boolean recording = dictationState == DictationState.RECORDING;
        boolean canRecord = sttEnabled && !chatBusy && !fileBusy && micTestSession == null
                && !checkingReadiness && (idle || recording || inFlight);
        boolean retryable = dictation.hasRetryableRecording() && idle;
        boolean savable = dictation.hasSavableRecording() && idle;
        boolean installVisible = idle && dictationState == DictationState.FAILED && lastFailureNeedsModel;
        boolean audioFileEnabled = sttEnabled && !chatBusy && idle && !fileBusy;
        boolean levelVisible = recording || micTestSession != null;

        composer.setDictationView(new ChatComposerPanel.DictationView(
                dictationState.getMicButtonLabel(),
                canRecord,
                recording,
                inFlight || checkingReadiness,
                // Only a real "throw away the recording" affordance: while transcribing, the record
                // button itself already shows "Cancel", so no separate discard X is needed there.
                recording || micTestSession != null,
                retryable,
                savable,
                installVisible,
                audioFileEnabled,
                levelVisible));
        testMicButton.setEnabled(!chatBusy && idle && !fileBusy && !checkingReadiness);
    }

    private boolean lastFailureNeedsModel;

    // ------------------------------------------------------------------ existing-file transcription (decoupled)

    private void onAudioFileAction() {
        if (fileBusy || isDictationInFlight() || dictationState == DictationState.RECORDING) {
            setDictationStatus("Finish the current dictation first.");
            return;
        }
        JFileChooser chooser = new JFileChooser(lastAudioDirectory);
        chooser.setDialogTitle("Transcribe an audio file");
        chooser.setFileFilter(new FileNameExtensionFilter(
                SupportedAudioFormats.fileChooserDescription(), SupportedAudioFormats.extensionArray()));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file == null) {
            return;
        }
        lastAudioDirectory = file.getParentFile();
        String sttModel = requestedAudioModel();
        if (AUDIO_MODEL_AUTOMATIC.equals(sttModel)) {
            setDictationStatus("Pick a specific audio model for file transcription.");
            return;
        }
        fileBusy = true;
        refreshDictationControls();
        setDictationStatus("Transcribing " + file.getName() + " …");
        SpeechToTextService.TranscriptionRequest request =
                new SpeechToTextService.TranscriptionRequest(file, sttModel, "", "");
        fileTask = speechToTextService.transcribe(request, new SpeechToTextService.TranscriptionListener() {
            public void onTranscription(final String text) {
                onUi(new Runnable() {
                    public void run() {
                        fileBusy = false;
                        fileTask = null;
                        JTextArea editor = composer.getEditor();
                        ComposerInserter.Insertion insertion = ComposerInserter.insert(editor.getText(),
                                editor.getSelectionStart(), editor.getSelectionEnd(), text);
                        editor.setText(insertion.getText());
                        editor.setCaretPosition(Math.min(insertion.getCaret(), editor.getText().length()));
                        composer.focusEditor();
                        setDictationStatus("Transcription ready. Review the text and press Send.");
                        refreshDictationControls();
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        fileBusy = false;
                        fileTask = null;
                        setDictationStatus("File transcription failed: " + messageOf(ex));
                        refreshDictationControls();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ diagnostics + cleanup

    private void showDiagnostics(DictationDiagnostics diagnostics) {
        if (diagnostics == null) {
            return;
        }
        StringBuilder builder = new StringBuilder("Dictation diagnostics:");
        for (Map.Entry<String, String> entry : diagnostics.asMap().entrySet()) {
            builder.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue());
        }
        appendTech(builder.toString());
    }

    private void appendTech(String line) {
        techDetails.append(line + "\n");
        techDetails.setCaretPosition(techDetails.getDocument().getLength());
    }

    private void cleanupOldRecordings() {
        long cutoff = System.currentTimeMillis() - TEMP_TTL_MILLIS;
        deleteOldSpeechFiles(workDir, cutoff);
        deleteOldSpeechFiles(new File(System.getProperty("java.io.tmpdir")), cutoff);
    }

    private static void deleteOldSpeechFiles(File directory, long cutoff) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith("askai-speech") && name.endsWith(".wav")
                    && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    /** Stop any recording, abort HTTP, stop timers and clean up (window closing / app exit). */
    public void shutdownDictation() {
        stopLevelTimer();
        stopElapsedTimer();
        if (micTestSession != null) {
            stopMicTest("");
        }
        try {
            dictation.discard();
        } catch (Exception ignored) {
        }
        if (fileTask != null) {
            try {
                fileTask.cancel();
            } catch (Exception ignored) {
            }
        }
        cleanupOldRecordings();
        dictationExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ chat timers + busy

    private void startElapsedTimer() {
        requestStartedAtMillis = System.currentTimeMillis();
        elapsedTimer = new Timer(1000, event -> {
            long seconds = (System.currentTimeMillis() - requestStartedAtMillis) / 1000L;
            setStatus("Generating ... " + seconds + "s");
        });
        elapsedTimer.start();
    }

    private void stopElapsedTimer() {
        if (elapsedTimer != null) {
            elapsedTimer.stop();
            elapsedTimer = null;
        }
    }

    /** Switch the integrated composer between the Send and Stop states. */
    private void setBusy(boolean busy) {
        chatBusy = busy;
        composer.setChatBusy(busy);
        modelCombo.setEnabled(!busy);
        if (!busy) {
            composer.focusEditor();
            clearChatBusyDictationHint();
        }
        refreshDictationControls();
    }

    /** Drop a stale "busy — finish the chat" hint once the chat has finished. */
    private void clearChatBusyDictationHint() {
        if (composer.getDictationStatus() != null && composer.getDictationStatus().startsWith("Busy —")
                && dictationState.canStartRecording()) {
            setDictationStatus("Ready for dictation.");
        }
    }

    private void setStatus(String status) {
        composer.setChatStatus(status);
    }

    private void setDictationStatus(String status) {
        // Track whether the "Install audio model" action should be offered.
        lastFailureNeedsModel = status != null && status.contains("No audio-capable model");
        composer.setDictationStatus(status);
    }

    private static String messageOf(Throwable ex) {
        return ex.getMessage() == null ? ex.toString() : ex.getMessage();
    }

    private static void onUi(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-dictation-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

}
