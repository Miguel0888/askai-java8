package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.AskAiModel;
import com.aresstack.askai.java8.client.OllamaChatTurn;
import com.aresstack.askai.java8.service.OllamaService;
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

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final AskAiModel model;
    private final OllamaService ollamaService;
    private final SpeechToTextService speechToTextService;

    private final JComboBox<String> modelCombo;
    private final JTextField keepAliveField;
    private final JTextArea systemPromptArea;
    private final JTextArea inputArea;
    private final ChatTranscript transcript;
    private final JLabel statusLabel;
    private final JButton sendButton;
    private final JButton stopButton;

    // Dictation controls.
    private final JComboBox<String> audioModelCombo = new JComboBox<String>();
    private final JComboBox<String> micCombo = new JComboBox<String>();
    private final JButton micRefreshButton = new JButton("Refresh");
    private final JButton testMicButton = new JButton("Test microphone");
    private final JButton recordButton = new JButton("Record");
    private final JButton discardButton = new JButton("Discard");
    private final JButton retryButton = new JButton("Retry transcription");
    private final JButton saveButton = new JButton("Save recording");
    private final JButton installModelButton = new JButton("Install audio model");
    private final JButton audioFileButton = new JButton("Transcribe file…");
    private final JProgressBar levelBar = new JProgressBar(0, 100);
    private final JLabel dictationStatus = new JLabel(" ");
    private final JTextArea techDetails = new JTextArea(6, 40);

    private final List<OllamaChatTurn> history = new ArrayList<OllamaChatTurn>();
    private final StringBuilder streamingAssistant = new StringBuilder();
    private OllamaService.Task chatTask;
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
    private boolean updatingMicCombo;

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

    private InstallAudioModelHandler installAudioModelHandler;

    public OllamaChatPanel(AskAiModel model, OllamaService ollamaService,
                           SpeechToTextService speechToTextService) {
        this.model = model;
        this.ollamaService = ollamaService;
        this.speechToTextService = speechToTextService;
        this.modelCombo = new JComboBox<String>();
        this.keepAliveField = new JTextField(model.getDefaultKeepAlive(), 6);
        this.systemPromptArea = new JTextArea("You are a concise local assistant.", 2, 40);
        this.inputArea = new JTextArea(3, 40);
        this.transcript = new ChatTranscript();
        this.statusLabel = new JLabel("Select a model and start chatting.");
        this.sendButton = new JButton("Send");
        this.stopButton = new JButton("Stop");

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
        setBusy(false);
        showEmptyState();
        cleanupOldRecordings();
        refreshModels();
        refreshMicrophones();
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
        RecordingNormalizer normalizer = new DefaultRecordingNormalizer();
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
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.add(new JLabel("Model"));
        modelCombo.setPreferredSize(new Dimension(260, modelCombo.getPreferredSize().height));
        toolbar.add(modelCombo);
        JButton newChatButton = new JButton("New chat");
        newChatButton.addActionListener(event -> newChat());
        toolbar.add(newChatButton);

        int refreshSize = modelCombo.getPreferredSize().height;
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
        header.add(new CollapsiblePanel("Chat settings", buildChatSettings(), false), BorderLayout.CENTER);
        return header;
    }

    /** Collapsed advanced section: system prompt, keep-alive, audio model and microphone. */
    private JComponent buildChatSettings() {
        JPanel params = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        params.add(new JLabel("keep_alive"));
        params.add(keepAliveField);
        params.add(new JLabel("Audio model"));
        audioModelCombo.setEditable(false); // only /api/show-verified models, never free text
        audioModelCombo.setPreferredSize(new Dimension(200, audioModelCombo.getPreferredSize().height));
        audioModelCombo.addActionListener(event -> persistAudioModelSelection());
        params.add(audioModelCombo);

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

        techDetails.setEditable(false);
        techDetails.setLineWrap(true);
        techDetails.setWrapStyleWord(true);
        JScrollPane techScroll = new JScrollPane(techDetails);
        techScroll.setPreferredSize(new Dimension(techScroll.getPreferredSize().width, 140));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(params, BorderLayout.NORTH);
        top.add(micRow, BorderLayout.CENTER);

        JPanel settings = new JPanel(new BorderLayout(4, 4));
        settings.add(top, BorderLayout.NORTH);
        settings.add(system, BorderLayout.CENTER);
        settings.add(new CollapsiblePanel("Technical details", techScroll, false), BorderLayout.SOUTH);
        return settings;
    }

    private JComponent buildComposer() {
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xCFCFCF)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        bindKeys();

        JPanel buttons = new JPanel();
        buttons.setLayout(new javax.swing.BoxLayout(buttons, javax.swing.BoxLayout.Y_AXIS));
        sendButton.setAlignmentX(CENTER_ALIGNMENT);
        stopButton.setAlignmentX(CENTER_ALIGNMENT);
        sendButton.addActionListener(event -> sendChat());
        stopButton.addActionListener(event -> stopChat());

        recordButton.addActionListener(event -> onRecordButton());
        recordButton.setAlignmentX(CENTER_ALIGNMENT);
        discardButton.addActionListener(event -> onDiscardButton());
        discardButton.setForeground(new Color(0xC6, 0x28, 0x28));
        discardButton.setAlignmentX(CENTER_ALIGNMENT);
        discardButton.setToolTipText("Discard the current recording / cancel transcription (Escape)");
        audioFileButton.addActionListener(event -> onAudioFileAction());
        audioFileButton.setAlignmentX(CENTER_ALIGNMENT);
        audioFileButton.setToolTipText("Transcribe an existing audio file (separate from microphone dictation)");
        retryButton.addActionListener(event -> dictation.retryTranscription());
        retryButton.setAlignmentX(CENTER_ALIGNMENT);
        saveButton.addActionListener(event -> saveRecording());
        saveButton.setAlignmentX(CENTER_ALIGNMENT);
        installModelButton.addActionListener(event -> openInstallAudioModel());
        installModelButton.setAlignmentX(CENTER_ALIGNMENT);

        JLabel experimental = new JLabel("Dictation · Experimental");
        experimental.setForeground(new Color(0x90, 0x90, 0x90));
        experimental.setAlignmentX(CENTER_ALIGNMENT);
        experimental.setFont(experimental.getFont().deriveFont(experimental.getFont().getSize2D() - 1f));

        levelBar.setPreferredSize(new Dimension(120, 10));
        levelBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));
        levelBar.setAlignmentX(CENTER_ALIGNMENT);

        buttons.add(sendButton);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(stopButton);
        buttons.add(Box.createVerticalStrut(8));
        buttons.add(experimental);
        buttons.add(recordButton);
        buttons.add(levelBar);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(discardButton);
        buttons.add(retryButton);
        buttons.add(saveButton);
        buttons.add(installModelButton);
        buttons.add(audioFileButton);

        JPanel composer = new JPanel(new BorderLayout(8, 4));
        composer.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        composer.add(buttons, BorderLayout.EAST);

        JPanel hint = new JPanel(new BorderLayout());
        JLabel hintLabel = new JLabel("Enter to send · Shift+Enter newline · Ctrl+Shift+M dictate");
        hintLabel.setForeground(new Color(0x9E9E9E));
        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusRow.add(statusLabel);
        statusRow.add(dictationStatus);
        hint.add(statusRow, BorderLayout.WEST);
        hint.add(hintLabel, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(composer, BorderLayout.CENTER);
        south.add(hint, BorderLayout.SOUTH);

        refreshDictationControls();
        return south;
    }

    private void bindKeys() {
        inputArea.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send-chat");
        inputArea.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "insert-newline");
        inputArea.getInputMap().put(KeyStroke.getKeyStroke("control shift M"), "toggle-dictation");
        inputArea.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "cancel-dictation");
        inputArea.getActionMap().put("send-chat", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                sendChat();
            }
        });
        inputArea.getActionMap().put("insert-newline", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                inputArea.insert("\n", inputArea.getCaretPosition());
            }
        });
        inputArea.getActionMap().put("toggle-dictation", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                onRecordButton();
            }
        });
        inputArea.getActionMap().put("cancel-dictation", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                onDiscardButton();
            }
        });
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
                        if (previous != null) {
                            modelCombo.setSelectedItem(previous);
                        }
                        refreshAudioModels(names);
                        if (names.isEmpty()) {
                            setStatus("No models installed. Open Install to add one.");
                        } else {
                            setStatus("Ready. " + names.size() + " model(s) available.");
                        }
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
        transcript.appendInfo("Now chatting with " + modelName + ".");
        setStatus("Model set to " + modelName + ".");
    }

    private void showEmptyState() {
        transcript.appendInfo("New conversation. Type a message below and press Enter.");
    }

    private void sendChat() {
        if (!sendButton.isEnabled()) {
            return;
        }
        final String modelName = (String) modelCombo.getSelectedItem();
        if (modelName == null || modelName.trim().isEmpty()) {
            setStatus("No model selected. Open Models or Install first.");
            return;
        }
        final String userPrompt = inputArea.getText().trim();
        if (userPrompt.isEmpty()) {
            setStatus("Write a message before sending.");
            return;
        }

        if (transcript.isEmpty() || history.isEmpty()) {
            transcript.clear();
        }
        inputArea.setText("");
        transcript.appendUser(userPrompt);
        history.add(OllamaChatTurn.user(userPrompt));

        transcript.startAssistant(modelName);
        streamingAssistant.setLength(0);
        startElapsedTimer();
        setBusy(true);

        OllamaService.ChatRequest request = new OllamaService.ChatRequest(
                modelName, keepAliveField.getText(), buildConversation());
        chatTask = ollamaService.streamChat(request, new OllamaService.ChatListener() {
            public void onContent(final String content) {
                onUi(new Runnable() {
                    public void run() {
                        streamingAssistant.append(content);
                        transcript.appendAssistantDelta(content);
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
                        stopElapsedTimer();
                        setBusy(false);
                        chatTask = null;
                        transcript.appendAssistantDelta("[error: " + ex.getMessage() + "]");
                        transcript.finishAssistant();
                        setStatus("Chat failed.");
                    }
                });
            }
        });
    }

    private void finishTurn(OllamaService.ChatResult result) {
        stopElapsedTimer();
        setBusy(false);
        chatTask = null;
        String assistantText = streamingAssistant.toString();
        if (assistantText.trim().isEmpty() && !result.getFallbackText().isEmpty()) {
            assistantText = result.getFallbackText();
            transcript.appendAssistantDelta(assistantText);
        }
        transcript.finishAssistant();
        history.add(OllamaChatTurn.assistant(assistantText));
        if (result.hasMetrics()) {
            setStatus(String.format("Ready · %d tokens · %.1f tok/s",
                    result.getEvalCount(), result.tokensPerSecond()));
        } else {
            setStatus("Ready.");
        }
    }

    private List<OllamaChatTurn> buildConversation() {
        List<OllamaChatTurn> conversation = new ArrayList<OllamaChatTurn>();
        String system = systemPromptArea.getText();
        if (system != null && !system.trim().isEmpty()) {
            conversation.add(OllamaChatTurn.system(system));
        }
        conversation.addAll(history);
        return conversation;
    }

    private void stopChat() {
        if (chatTask != null) {
            chatTask.cancel();
            chatTask = null;
            stopElapsedTimer();
            setBusy(false);
            transcript.appendAssistantDelta(" [stopped]");
            transcript.finishAssistant();
            if (!streamingAssistant.toString().trim().isEmpty()) {
                history.add(OllamaChatTurn.assistant(streamingAssistant.toString()));
            }
            setStatus("Stopped.");
        }
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
            levelBar.setValue(scaleLevel(peak));
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
        levelBar.setValue(0);
        setDictationStatus(message);
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
        if (chatTask != null || fileBusy || micTestSession != null) {
            setDictationStatus("Busy — finish the chat / file transcription / mic test first.");
            return;
        }
        recordButton.setEnabled(false);
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
        dictationState = state;
        recordButton.setText(state == DictationState.RECORDING ? "Stop" : "Record");
        if (state == DictationState.RECORDING) {
            recordingStartedAtMillis = System.currentTimeMillis();
            startLevelTimer();
        } else {
            stopLevelTimer();
            if (state.isTerminal() || state == DictationState.IDLE) {
                levelBar.setValue(0);
            }
        }
        setDictationStatus(message != null ? message : state.getDefaultStatusMessage());
        refreshDictationControls();
    }

    private void onDictationResult(DictationResult result) {
        // Insert at the caret, preserve existing text, never auto-send. The service delivers exactly
        // one terminal callback per operation, so this cannot double-insert.
        ComposerInserter.Insertion insertion = ComposerInserter.insert(
                inputArea.getText(), inputArea.getSelectionStart(), inputArea.getSelectionEnd(), result.getText());
        inputArea.setText(insertion.getText());
        inputArea.setCaretPosition(Math.min(insertion.getCaret(), inputArea.getText().length()));
        inputArea.requestFocusInWindow();
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
            levelBar.setValue(scaleLevel(meter.getPeak()));
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

    /** Enable/disable the dictation controls for the current state and available artifacts. */
    private void refreshDictationControls() {
        boolean sttEnabled = model.getSpeechToTextConfiguration().isEnabled();
        boolean idle = dictationState.canStartRecording();
        boolean canRecord = sttEnabled && chatTask == null && !fileBusy && micTestSession == null
                && (idle || dictationState == DictationState.RECORDING || isDictationInFlight());
        recordButton.setEnabled(canRecord);
        discardButton.setEnabled(dictationState == DictationState.RECORDING || isDictationInFlight());
        boolean retryable = dictation.hasRetryableRecording() && idle;
        boolean savable = dictation.hasSavableRecording() && idle;
        retryButton.setVisible(retryable);
        saveButton.setVisible(savable);
        // Offer "Install audio model" only when the last outcome was that none is available.
        installModelButton.setVisible(idle && dictationState == DictationState.FAILED
                && lastFailureNeedsModel);
        audioFileButton.setEnabled(sttEnabled && chatTask == null && idle && !fileBusy);
        testMicButton.setEnabled(chatTask == null && idle && !fileBusy);
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
                        ComposerInserter.Insertion insertion = ComposerInserter.insert(inputArea.getText(),
                                inputArea.getSelectionStart(), inputArea.getSelectionEnd(), text);
                        inputArea.setText(insertion.getText());
                        inputArea.setCaretPosition(Math.min(insertion.getCaret(), inputArea.getText().length()));
                        inputArea.requestFocusInWindow();
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

    /** Chat busy state. Dictation has its own controls, so a chat stream does not free the mic. */
    private void setBusy(boolean busy) {
        sendButton.setEnabled(!busy);
        stopButton.setEnabled(busy);
        modelCombo.setEnabled(!busy);
        inputArea.setEnabled(!busy);
        if (!busy) {
            inputArea.requestFocusInWindow();
        }
        refreshDictationControls();
    }

    private void setStatus(String status) {
        statusLabel.setText(status);
    }

    private void setDictationStatus(String status) {
        // Track whether the "Install audio model" action should be offered.
        lastFailureNeedsModel = status != null && status.contains("No audio-capable model");
        dictationStatus.setText(status == null ? " " : status);
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
