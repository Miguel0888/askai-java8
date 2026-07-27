package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.AskAiModel;
import com.aresstack.askai.java8.audio.AudioProfileRepository;
import com.aresstack.askai.java8.audio.FileAudioProfileRepository;
import com.aresstack.askai.java8.groupchat.GroupChatConnectionState;
import com.aresstack.askai.java8.groupchat.GroupChatMode;
import com.aresstack.askai.java8.groupchat.GroupChatRoom;
import com.aresstack.askai.java8.groupchat.GroupChatTransport;
import com.aresstack.askai.java8.groupchat.MentionParser;
import com.aresstack.askai.java8.groupchat.Participant;
import com.aresstack.askai.java8.groupchat.ParticipantColorPalette;
import com.aresstack.askai.java8.groupchat.jgroups.JGroupsGroupChatTransport;
import com.aresstack.askai.java8.groupchat.jgroups.JGroupsTransportConfig;
import com.aresstack.askai.java8.party.OllamaBotResponder;
import com.aresstack.askai.java8.party.PartySession;
import com.aresstack.askai.java8.party.PartySettings;
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
import java.util.UUID;
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
public final class OllamaChatPanel extends JPanel {

    private static final String MIC_SYSTEM_DEFAULT = "System default";
    private static final String AUDIO_MODEL_AUTOMATIC = "Automatic";
    /** Delete leftover dictation temp files older than this many milliseconds on startup/close. */
    private static final long TEMP_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    /** Application-state keys under which the chat remembers its last selection. */
    private static final String STATE_LAST_MODEL = "chat.lastModel";
    private static final String STATE_MODE = "chat.mode";
    private static final String STATE_AGENT = "chat.agent";
    private static final String STATE_REASONING = "chat.reasoningEffort";

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
    // The interaction mode shown on the composer's mode selector: GroupChatMode.YAPPING (casual chat,
    // default) or the name of the selected agent when in "Questing" mode. selectedAgent is null while yapping.
    private String chatMode = GroupChatMode.YAPPING;
    private String selectedAgent;
    // Partying (LAN group-chat) state: the active session controller and the persisted settings.
    private PartySession partySession;
    private PartySettings partySettings;
    private MentionCompletionSupport mentionCompletion;
    private boolean partyJoinInFlight;
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

        this.partySettings = new PartySettings(applicationState);
        this.mentionCompletion = new MentionCompletionSupport(composer.getEditor());
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
        String mode = applicationState.get(STATE_MODE, GroupChatMode.YAPPING);
        String agent = applicationState.get(STATE_AGENT, null);
        if (GroupChatMode.PARTYING.equals(mode)) {
            // Restore into Partying mode; the transport is not auto-started on restore.
            chatMode = GroupChatMode.PARTYING;
            selectedAgent = null;
            composer.setModeName("Partying");
            mentionCompletion.setActive(true);
            refreshMentionCompletionHandles();
        } else if (GroupChatMode.YAPPING.equals(mode) || agent == null || agent.trim().isEmpty()) {
            chatMode = GroupChatMode.YAPPING;
            selectedAgent = null;
            composer.setModeName("Yapping");
        } else {
            chatMode = mode;
            selectedAgent = agent;
            composer.setModeName(agent);
        }

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
        add(buildToolbar(), BorderLayout.NORTH);
        add(transcript.getComponent(), BorderLayout.CENTER);
        add(buildComposer(), BorderLayout.SOUTH);
    }

    private JComponent buildToolbar() {
        // The model is now chosen from the ChatGPT-style selector inside the composer; the top row only
        // keeps New chat + a refresh. (modelCombo lives on as the off-screen data model / selection.)
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton newChatButton = new JButton("New chat");
        newChatButton.addActionListener(event -> newChat());
        toolbar.add(newChatButton);

        int refreshSize = newChatButton.getPreferredSize().height;
        JButton refreshButton = new JButton(new RefreshIcon(refreshSize - 6));
        refreshButton.setToolTipText("Refresh models");
        refreshButton.setFocusPainted(false);
        refreshButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
        refreshButton.setPreferredSize(new Dimension(refreshSize, refreshSize));
        refreshButton.addActionListener(event -> refreshModels());
        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        rightControls.add(refreshButton);

        JPanel toolbarRow = new JPanel(new BorderLayout());
        toolbarRow.add(toolbar, BorderLayout.CENTER);
        toolbarRow.add(rightControls, BorderLayout.EAST);

        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.add(toolbarRow, BorderLayout.NORTH);
        // Chat settings moved behind the composer's gear; only Technical details stays here (collapsed).
        header.add(new CollapsiblePanel("Technical details", buildTechnicalDetails(), false), BorderLayout.CENTER);
        return header;
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
        JPanel south = new JPanel();
        south.setLayout(new javax.swing.BoxLayout(south, javax.swing.BoxLayout.Y_AXIS));
        JComponent colorSection = (JComponent) buildColorSettings();
        colorSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        south.add(colorSection);
        JComponent partySection = buildPartySettings();
        partySection.setAlignmentX(Component.LEFT_ALIGNMENT);
        south.add(partySection);
        settings.add(south, BorderLayout.SOUTH);
        return settings;
    }

    /** No preferred participant color — the deterministic assignment picks a free one. */
    private static final String PARTY_COLOR_AUTOMATIC = "(automatic)";

    /**
     * The Partying settings: identity, preferred color, discovery/network options, bot policy,
     * room identity/secret and the local history location.  Values apply on the next join.
     */
    private JComponent buildPartySettings() {
        JPanel party = new JPanel();
        party.setLayout(new javax.swing.BoxLayout(party, javax.swing.BoxLayout.Y_AXIS));
        party.setBorder(BorderFactory.createTitledBorder("Partying (LAN group chat)"));

        final JTextField nameField = new JTextField(partySettings.displayName(), 12);
        final JComboBox<String> colorCombo = new JComboBox<String>();
        colorCombo.addItem(PARTY_COLOR_AUTOMATIC);
        for (String token : ParticipantColorPalette.tokens()) {
            colorCombo.addItem(token);
        }
        String preferred = partySettings.preferredColor();
        colorCombo.setSelectedItem(preferred != null ? preferred : PARTY_COLOR_AUTOMATIC);
        JPanel identityRow = partySettingsRow();
        identityRow.add(new JLabel("Display name"));
        identityRow.add(nameField);
        identityRow.add(new JLabel("Preferred color"));
        identityRow.add(colorCombo);
        party.add(identityRow);

        final javax.swing.JCheckBox discoveryBox = new javax.swing.JCheckBox(
                "Automatic LAN discovery (UDP multicast)", partySettings.discoveryEnabled());
        final JTextField interfaceField = new JTextField(
                partySettings.networkInterface() == null ? "" : partySettings.networkInterface(), 8);
        interfaceField.setToolTipText("Network interface to bind, empty for automatic selection");
        JPanel networkRow = partySettingsRow();
        networkRow.add(discoveryBox);
        networkRow.add(new JLabel("Interface"));
        networkRow.add(interfaceField);
        party.add(networkRow);

        final JTextField peersField = new JTextField(partySettings.manualPeersText(), 24);
        peersField.setToolTipText(
                "Manual peer addresses (host or host:port, comma-separated) when multicast is blocked");
        JPanel peersRow = partySettingsRow();
        peersRow.add(new JLabel("Manual peers"));
        peersRow.add(peersField);
        party.add(peersRow);

        final JComboBox<String> botPolicyCombo = new JComboBox<String>();
        botPolicyCombo.addItem("Answer only when @AskAI is mentioned");
        botPolicyCombo.addItem("Never answer");
        botPolicyCombo.setSelectedIndex(
                PartySettings.BOT_POLICY_OFF.equals(partySettings.botPolicy()) ? 1 : 0);
        JPanel botRow = partySettingsRow();
        botRow.add(new JLabel("Bot"));
        botRow.add(botPolicyCombo);
        party.add(botRow);

        final JTextField roomField = new JTextField(partySettings.roomId(), 10);
        final JTextField secretField = new JTextField(partySettings.roomSecret(), 10);
        secretField.setToolTipText("Room invitation secret: authenticates the join and encrypts traffic");
        JPanel roomRow = partySettingsRow();
        roomRow.add(new JLabel("Room"));
        roomRow.add(roomField);
        roomRow.add(new JLabel("Secret"));
        roomRow.add(secretField);
        party.add(roomRow);

        final JTextField historyField = new JTextField(
                partySettings.historyDirectory().getAbsolutePath(), 24);
        JPanel historyRow = partySettingsRow();
        historyRow.add(new JLabel("History folder"));
        historyRow.add(historyField);
        party.add(historyRow);

        JLabel historyNote = new JLabel(
                "History lives on the participants' machines. Messages no reachable peer remembers cannot be restored.");
        historyNote.setFont(historyNote.getFont().deriveFont(historyNote.getFont().getSize2D() - 2f));
        JPanel noteRow = partySettingsRow();
        noteRow.add(historyNote);
        party.add(noteRow);

        JButton applyButton = new JButton("Apply party settings");
        applyButton.setToolTipText("Saved immediately; network changes take effect on the next join");
        applyButton.addActionListener(event -> {
            partySettings.setDisplayName(nameField.getText());
            Object color = colorCombo.getSelectedItem();
            partySettings.setPreferredColor(
                    PARTY_COLOR_AUTOMATIC.equals(color) ? null : String.valueOf(color));
            partySettings.setDiscoveryEnabled(discoveryBox.isSelected());
            partySettings.setNetworkInterface(interfaceField.getText());
            partySettings.setManualPeers(peersField.getText());
            partySettings.setBotPolicy(botPolicyCombo.getSelectedIndex() == 1
                    ? PartySettings.BOT_POLICY_OFF : PartySettings.BOT_POLICY_MENTION);
            partySettings.setRoomId(roomField.getText());
            partySettings.setRoomSecret(secretField.getText());
            partySettings.setHistoryDirectory(historyField.getText());
            setStatus("Party settings saved — they apply on the next join.");
        });
        JButton diagnosticsButton = new JButton("Network diagnostics");
        diagnosticsButton.setToolTipText("Check multicast/firewall readiness of the local network interfaces");
        diagnosticsButton.addActionListener(event -> {
            appendTech(JGroupsGroupChatTransport.diagnoseMulticast());
            setStatus("Network diagnostics written to Technical details.");
        });
        JPanel actionsRow = partySettingsRow();
        actionsRow.add(applyButton);
        actionsRow.add(diagnosticsButton);
        party.add(actionsRow);
        return party;
    }

    private static JPanel partySettingsRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
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

    // ------------------------------------------------------------------ chat (unchanged behaviour)

    private void refreshModels() {
        setStatus("Loading models from " + model.getOllamaBaseUrl() + " ...");
        ollamaService.listModelNames(new OllamaService.ModelNamesListener() {
            public void onModelNames(final List<String> names) {
                onUi(new Runnable() {
                    public void run() {
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
                        refreshAudioModels(names);
                        // The in-composer selector shows the current model; keep the status line quiet.
                        composer.setModelName((String) modelCombo.getSelectedItem());
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

    private void newChat() {
        if (chatTask != null) {
            return;
        }
        history.clear();
        transcript.clear();
        showEmptyState();
        setStatus("Started a new chat.");
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

    /**
     * The in-composer mode selector: "Yapping" is the default casual chat; "Questing" is the agent
     * mode and carries a submenu of installed agents (none yet); "Partying" is the decentralized LAN
     * group-chat mode where people and bots collaborate.
     *
     * <p>Internal mode IDs are the stable constants from {@link GroupChatMode} (e.g.
     * {@code "builtin.partying"}); display labels shown in the composer pill are derived here.</p>
     */
    private void openModePopup() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();

        javax.swing.JRadioButtonMenuItem yapping =
                new javax.swing.JRadioButtonMenuItem("\uD83D\uDCAC Yapping", GroupChatMode.YAPPING.equals(chatMode));
        yapping.addActionListener(event -> selectYappingMode());
        menu.add(yapping);

        // "Questing" is a submenu (the arrow) listing the installed agents to run.
        javax.swing.JMenu questing = new javax.swing.JMenu("\uD83D\uDDFA Questing");
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

        javax.swing.JRadioButtonMenuItem partying =
                new javax.swing.JRadioButtonMenuItem("\uD83D\uDC65 Partying", GroupChatMode.PARTYING.equals(chatMode));
        partying.setToolTipText("Partying \u2014 Chat with people and bots on your local network");
        partying.addActionListener(event -> selectPartyingMode());
        menu.add(partying);

        JComponent anchor = composer.getModeButton();
        menu.show(anchor, 0, anchor.getHeight());
    }

    /** @return the names of installed agents; empty until agent support ships. */
    private List<String> installedAgentNames() {
        return Collections.emptyList();
    }

    private void selectYappingMode() {
        leavePartySession(false);
        mentionCompletion.setActive(false);
        chatMode = GroupChatMode.YAPPING;
        selectedAgent = null;
        composer.setModeName("Yapping");
        rememberState(STATE_MODE, GroupChatMode.YAPPING);
        rememberState(STATE_AGENT, null);
    }

    private void selectAgentMode(String agent) {
        leavePartySession(false);
        mentionCompletion.setActive(false);
        chatMode = agent;
        selectedAgent = agent;
        composer.setModeName(agent);
        rememberState(STATE_MODE, agent);
        rememberState(STATE_AGENT, agent);
    }

    // ------------------------------------------------------------------ Partying (LAN group chat)

    /**
     * Leaves the active party session; safe to call when not connected, always idempotent.
     *
     * @param synchronously {@code true} during shutdown (the executor is about to stop);
     *                      {@code false} to leave in the background on a mode switch
     */
    private void leavePartySession(boolean synchronously) {
        final PartySession session = partySession;
        partySession = null;
        partyJoinInFlight = false;
        if (session == null) {
            return;
        }
        Runnable leave = new Runnable() {
            public void run() {
                try {
                    session.leave();
                } catch (Exception ignored) {
                }
            }
        };
        if (synchronously) {
            leave.run();
        } else {
            dictationExecutor.execute(leave);
        }
    }

    private void selectPartyingMode() {
        chatMode = GroupChatMode.PARTYING;
        selectedAgent = null;
        composer.setModeName("Partying");
        rememberState(STATE_MODE, GroupChatMode.PARTYING);
        rememberState(STATE_AGENT, null);
        mentionCompletion.setActive(true);
        refreshMentionCompletionHandles();
        startPartySessionIfNeeded();
    }

    /** Builds and joins a party session in the background unless one is already connected. */
    private void startPartySessionIfNeeded() {
        if (partyJoinInFlight || (partySession != null && partySession.isConnected())) {
            return;
        }
        partyJoinInFlight = true;
        setStatus("Looking for a party…");
        final PartySession session = buildPartySession();
        dictationExecutor.execute(new Runnable() {
            public void run() {
                onUi(new Runnable() {
                    public void run() {
                        if (GroupChatMode.PARTYING.equals(chatMode)) {
                            setStatus("Joining the party…");
                        }
                    }
                });
                try {
                    session.join();
                    onUi(new Runnable() {
                        public void run() {
                            partySession = session;
                            partyJoinInFlight = false;
                            refreshMentionCompletionHandles();
                        }
                    });
                    session.updateBotReadiness();
                } catch (final Exception ex) {
                    onUi(new Runnable() {
                        public void run() {
                            partyJoinInFlight = false;
                            if (GroupChatMode.PARTYING.equals(chatMode)) {
                                setStatus("Party connection lost: " + messageOf(ex));
                                transcript.appendInfo("Could not join the party: " + messageOf(ex));
                            }
                        }
                    });
                }
            }
        });
    }

    /** Assembles the session from the persisted settings, the LAN transport and the bot port. */
    private PartySession buildPartySession() {
        String participantId = partySettings.participantId();
        String displayName = partySettings.displayName();
        String handle = MentionParser.computeUniqueHandle(
                displayName, java.util.Collections.<String>emptyList());
        Participant self = new Participant(participantId, displayName, handle,
                partySettings.preferredColor(), true, modelCombo.getSelectedItem() != null);
        GroupChatRoom room = new GroupChatRoom(
                partySettings.roomId(), partySettings.roomName(), partySettings.roomSecret());
        OllamaBotResponder responder = new OllamaBotResponder(ollamaService,
                new Supplier<String>() {
                    public String get() {
                        return (String) modelCombo.getSelectedItem();
                    }
                },
                new Supplier<String>() {
                    public String get() {
                        return keepAliveField.getText();
                    }
                });
        return new PartySession(createPartyTransport(), room, self,
                partySettings.botPolicy(), responder, new PanelPartyUi());
    }

    /** The real LAN transport (JGroups); discovery options come from the Partying settings. */
    private GroupChatTransport createPartyTransport() {
        JGroupsTransportConfig config = new JGroupsTransportConfig.Builder()
                .multicastDiscovery(partySettings.discoveryEnabled())
                .bindInterface(partySettings.networkInterface())
                .manualPeers(partySettings.manualPeers())
                .historyDirectory(partySettings.historyDirectory())
                .build();
        return new JGroupsGroupChatTransport(config);
    }

    /** Routes the session's callbacks (transport threads) onto the EDT and into the shared shell. */
    private final class PanelPartyUi implements PartySession.Ui {
        public void onPartyMessage(final PartySession.PartyMessageView view) {
            onUi(new Runnable() {
                public void run() {
                    if (!GroupChatMode.PARTYING.equals(chatMode)) {
                        return;
                    }
                    transcript.appendPartyMessage(
                            view.getSenderDisplayName(),
                            view.getMessage().getSenderParticipantId(),
                            view.getMessage().getMarkdown(),
                            partyColor(view.getColorToken()),
                            view.isLocal());
                }
            });
        }

        public void onInfoLine(final String text) {
            onUi(new Runnable() {
                public void run() {
                    if (GroupChatMode.PARTYING.equals(chatMode)) {
                        transcript.appendInfo(text);
                    }
                }
            });
        }

        public void onStatus(final GroupChatConnectionState state) {
            onUi(new Runnable() {
                public void run() {
                    if (!GroupChatMode.PARTYING.equals(chatMode)) {
                        return;
                    }
                    if (state.isConnected()) {
                        int count = state.getMemberCount();
                        setStatus(count == 1
                                ? "1 party member (just you)"
                                : count + " party members");
                    } else if (state.hasError()) {
                        setStatus("Party connection lost: " + state.getErrorMessage());
                    } else {
                        setStatus("Not connected to party");
                    }
                }
            });
        }

        public void onHandlesChanged(final java.util.List<String> handles) {
            onUi(new Runnable() {
                public void run() {
                    mentionCompletion.setHandles(handles);
                }
            });
        }
    }

    /** Refreshes the {@code @}-completion handles from the current party membership. */
    private void refreshMentionCompletionHandles() {
        PartySession session = partySession;
        mentionCompletion.setHandles(session != null
                ? session.mentionHandles()
                : java.util.Arrays.asList(MentionParser.BOT_HANDLE));
    }

    /**
     * Maps a replicated palette color token to the theme-matched concrete color: the palette's
     * dark variant on dark transcript backgrounds, the light variant otherwise.
     */
    private Color partyColor(String token) {
        ParticipantColorPalette.Entry entry = ParticipantColorPalette.byToken(token);
        if (entry == null) {
            return null;
        }
        Color background = model.getChatColors().getTranscriptBackground();
        boolean darkTheme = background != null
                && (background.getRed() * 299 + background.getGreen() * 587
                        + background.getBlue() * 114) / 1000 < 128;
        try {
            return Color.decode(darkTheme ? entry.getDarkHex() : entry.getLightHex());
        } catch (NumberFormatException ex) {
            return null;
        }
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
        // A model change may flip this peer's ability to host the party bot; announce it.
        final PartySession session = partySession;
        if (session != null) {
            dictationExecutor.execute(new Runnable() {
                public void run() {
                    try {
                        session.updateBotReadiness();
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    private void showEmptyState() {
        transcript.appendInfo("New conversation. Type a message below and press Enter.");
    }

    private void sendChat() {
        if (!composer.isSendEnabled()) {
            return;
        }

        // In Partying mode, route through the GroupChatSubmissionTarget instead of Ollama.
        if (GroupChatMode.PARTYING.equals(chatMode)) {
            sendPartyChat();
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

    /**
     * Routes a message submission in Partying mode through the {@link GroupChatSubmissionTarget},
     * keeping the existing transcript and composer for the shared chat shell.
     *
     * <p>The composer is cleared only when submission is accepted (lossless: a rejected message
     * stays in the composer so the user can retry or switch to a different mode).</p>
     */
    private void sendPartyChat() {
        final String userPrompt = composer.getMessage().trim();
        if (userPrompt.isEmpty()) {
            setStatus("Write a message before sending.");
            return;
        }
        PartySession session = partySession;
        if (session != null && session.submitMessage(userPrompt)) {
            composer.clearMessage();
        } else {
            // Lossless: the message stays in the composer until a join succeeds and Send is hit again.
            startPartySessionIfNeeded();
            setStatus("Not connected to party — your message stays in the composer.");
        }
    }
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
                "Audio files (wav, mp3, m4a, ogg, flac)", DefaultSpeechToTextService.supportedExtensions()));
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
        leavePartySession(true);
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

    /** A refresh glyph: two circular arrows chasing each other, painted with Java2D (no asset). */
    private static final class RefreshIcon implements javax.swing.Icon {
        private final int size;

        RefreshIcon(int size) {
            this.size = size;
        }

        public int getIconWidth() {
            return size;
        }

        public int getIconHeight() {
            return size;
        }

        public void paintIcon(java.awt.Component component, java.awt.Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(component.isEnabled() ? new Color(0x42, 0x60, 0x77) : new Color(0x9E, 0x9E, 0x9E));
                float stroke = Math.max(1.6f, size / 9f);
                g.setStroke(new java.awt.BasicStroke(stroke, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                double pad = stroke + 1;
                double diameter = size - 2 * pad;
                double cx = x + size / 2.0;
                double cy = y + size / 2.0;
                double radius = diameter / 2.0;
                g.draw(new java.awt.geom.Arc2D.Double(x + pad, y + pad, diameter, diameter, 30, 140, java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Arc2D.Double(x + pad, y + pad, diameter, diameter, 210, 140, java.awt.geom.Arc2D.OPEN));
                drawArrowHead(g, cx, cy, radius, 170);
                drawArrowHead(g, cx, cy, radius, 350);
            } finally {
                g.dispose();
            }
        }

        private void drawArrowHead(java.awt.Graphics2D g, double cx, double cy, double radius, double angleDeg) {
            double a = Math.toRadians(angleDeg);
            double tipX = cx + radius * Math.cos(a);
            double tipY = cy - radius * Math.sin(a);
            double travel = a + Math.PI / 2.0;
            double length = Math.max(3.0, radius * 0.75);
            for (int side = -1; side <= 1; side += 2) {
                double barb = travel + side * Math.toRadians(150);
                double bx = tipX + length * Math.cos(barb);
                double by = tipY - length * Math.sin(barb);
                g.draw(new java.awt.geom.Line2D.Double(tipX, tipY, bx, by));
            }
        }
    }
}
