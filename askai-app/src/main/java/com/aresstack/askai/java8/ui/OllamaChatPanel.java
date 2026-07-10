package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.AskAiModel;
import com.aresstack.askai.java8.client.OllamaChatTurn;
import com.aresstack.askai.java8.service.OllamaService;
import com.aresstack.askai.java8.stt.DefaultSpeechToTextService;
import com.aresstack.askai.java8.stt.SpeechToTextConfiguration;
import com.aresstack.askai.java8.stt.SpeechToTextService;
import com.aresstack.audio.application.RecordSpeechInputUseCase;
import com.aresstack.audio.application.SpeechCaptureConfiguration;
import com.aresstack.audio.application.SpeechRecordingSession;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.infrastructure.JavaSoundMicrophoneSource;
import com.aresstack.audio.infrastructure.WavFileAudioSink;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Clean, shippable chat window for the selected Ollama model.
 *
 * <p>Keeps a multi-turn conversation, streams assistant tokens live into a styled
 * transcript, and sends the full history (optionally prefixed by a system prompt) on
 * every turn. All Ollama access goes through {@link OllamaService}.</p>
 */
public final class OllamaChatPanel extends JPanel {

    private final AskAiModel model;
    private final OllamaService ollamaService;
    private final SpeechToTextService speechToTextService;

    private final JComboBox<String> modelCombo;
    private final JComboBox<String> audioModelCombo;
    private final JTextField keepAliveField;
    private final JTextArea systemPromptArea;
    private final JTextArea inputArea;
    private final ChatTranscript transcript;
    private final JLabel statusLabel;
    private final JButton sendButton;
    private final JButton stopButton;
    private final JButton recordButton;
    private final JButton audioFileButton;

    private final List<OllamaChatTurn> history = new ArrayList<OllamaChatTurn>();
    private final StringBuilder streamingAssistant = new StringBuilder();
    private OllamaService.Task chatTask;
    private SpeechToTextService.Task transcriptionTask;
    private SpeechRecordingSession recordingSession;
    private File recordingTempFile;
    private final List<File> pendingAudioFiles = new ArrayList<File>();
    private int audioFileTotal;
    private boolean labelTranscriptions;
    private boolean deleteAudioAfterTranscription;
    private boolean transcriptionCancelled;
    private File lastAudioDirectory;
    private boolean updatingAudioModelCombo;
    private Timer elapsedTimer;
    private Timer recordingTimer;
    private long requestStartedAtMillis;

    public OllamaChatPanel(AskAiModel model, OllamaService ollamaService,
                           SpeechToTextService speechToTextService) {
        this.model = model;
        this.ollamaService = ollamaService;
        this.speechToTextService = speechToTextService;
        this.modelCombo = new JComboBox<String>();
        this.audioModelCombo = new JComboBox<String>();
        this.audioModelCombo.setEditable(true);
        this.audioModelCombo.setToolTipText(
                "Model used for speech-to-text; leave equal to the chat model if it accepts audio");
        this.keepAliveField = new JTextField(model.getDefaultKeepAlive(), 6);
        this.systemPromptArea = new JTextArea("You are a concise local assistant.", 2, 40);
        this.inputArea = new JTextArea(3, 40);
        this.transcript = new ChatTranscript();
        this.statusLabel = new JLabel("Select a model and start chatting.");
        this.sendButton = new JButton("Send");
        this.stopButton = new JButton("Stop");
        this.recordButton = new JButton("Record");
        this.recordButton.setToolTipText(
                "Record from the microphone; click again to stop and transcribe into the message field");
        this.audioFileButton = new JButton("▾");
        this.audioFileButton.setToolTipText("Transcribe existing audio file(s) instead of recording");
        buildUserInterface();
        setBusy(false);
        showEmptyState();
        refreshModels();
    }

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
        toolbar.add(new JLabel("keep_alive"));
        toolbar.add(keepAliveField);
        toolbar.add(new JLabel("Audio model"));
        audioModelCombo.setPreferredSize(new Dimension(220, audioModelCombo.getPreferredSize().height));
        toolbar.add(audioModelCombo);
        audioModelCombo.addActionListener(event -> persistAudioModelSelection());

        // Square, icon-only refresh button, pinned to the far right of the toolbar row.
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

        JPanel system = new JPanel(new BorderLayout(6, 2));
        system.setBorder(BorderFactory.createTitledBorder("System prompt"));
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        system.add(new JScrollPane(systemPromptArea), BorderLayout.CENTER);

        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.add(toolbarRow, BorderLayout.NORTH);
        header.add(system, BorderLayout.CENTER);
        return header;
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
                // Two arcs leaving a gap on each side; an arrowhead sits at the leading edge of each gap.
                g.draw(new java.awt.geom.Arc2D.Double(x + pad, y + pad, diameter, diameter, 30, 140, java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Arc2D.Double(x + pad, y + pad, diameter, diameter, 210, 140, java.awt.geom.Arc2D.OPEN));
                drawArrowHead(g, cx, cy, radius, 170);
                drawArrowHead(g, cx, cy, radius, 350);
            } finally {
                g.dispose();
            }
        }

        /** Draw an arrowhead at the given angle on the circle, pointing in the counterclockwise travel direction. */
        private void drawArrowHead(java.awt.Graphics2D g, double cx, double cy, double radius, double angleDeg) {
            double a = Math.toRadians(angleDeg);
            double tipX = cx + radius * Math.cos(a);
            double tipY = cy - radius * Math.sin(a);
            double travel = a + Math.PI / 2.0; // tangent direction for counterclockwise motion
            double length = Math.max(3.0, radius * 0.75);
            for (int side = -1; side <= 1; side += 2) {
                double barb = travel + side * Math.toRadians(150);
                double bx = tipX + length * Math.cos(barb);
                double by = tipY - length * Math.sin(barb);
                g.draw(new java.awt.geom.Line2D.Double(tipX, tipY, bx, by));
            }
        }
    }

    private JComponent buildComposer() {
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xCFCFCF)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        bindEnterToSend();

        JPanel buttons = new JPanel();
        buttons.setLayout(new javax.swing.BoxLayout(buttons, javax.swing.BoxLayout.Y_AXIS));
        sendButton.setAlignmentX(CENTER_ALIGNMENT);
        stopButton.setAlignmentX(CENTER_ALIGNMENT);
        sendButton.addActionListener(event -> sendChat());
        stopButton.addActionListener(event -> stopChat());
        recordButton.addActionListener(event -> onRecordAction());
        audioFileButton.addActionListener(event -> onAudioFileAction());
        audioFileButton.setMargin(new java.awt.Insets(2, 4, 2, 4));
        // Record toggle plus a small arrow for picking existing audio files, ChatGPT-style.
        JPanel audioRow = new JPanel(new BorderLayout(2, 0));
        audioRow.setOpaque(false);
        audioRow.add(recordButton, BorderLayout.CENTER);
        audioRow.add(audioFileButton, BorderLayout.EAST);
        audioRow.setAlignmentX(CENTER_ALIGNMENT);
        audioRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, recordButton.getPreferredSize().height + 4));
        buttons.add(sendButton);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(stopButton);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(audioRow);

        JPanel composer = new JPanel(new BorderLayout(8, 4));
        composer.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        composer.add(buttons, BorderLayout.EAST);

        JPanel hint = new JPanel(new BorderLayout());
        JLabel hintLabel = new JLabel("Enter to send  ·  Shift+Enter for a new line");
        hintLabel.setForeground(new Color(0x9E9E9E));
        hint.add(statusLabel, BorderLayout.WEST);
        hint.add(hintLabel, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(composer, BorderLayout.CENTER);
        south.add(hint, BorderLayout.SOUTH);
        return south;
    }

    private void bindEnterToSend() {
        inputArea.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send-chat");
        inputArea.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "insert-newline");
        inputArea.getActionMap().put("send-chat", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                sendChat();
            }
        });
        inputArea.getActionMap().put("insert-newline", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                inputArea.insert("\n", inputArea.getCaretPosition());
            }
        });
    }

    private void refreshModels() {
        setStatus("Loading models from " + model.getOllamaBaseUrl() + " ...");
        ollamaService.listModelNames(new OllamaService.ModelNamesListener() {
            @Override
            public void onModelNames(final List<String> names) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        Object previous = modelCombo.getSelectedItem();
                        modelCombo.removeAllItems();
                        for (String name : names) {
                            modelCombo.addItem(name);
                        }
                        if (previous != null) {
                            modelCombo.setSelectedItem(previous);
                        }
                        refillAudioModelCombo(names);
                        if (names.isEmpty()) {
                            setStatus("No models installed. Open Install to add one.");
                        } else {
                            setStatus("Ready. " + names.size() + " model(s) available.");
                        }
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
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
            @Override
            public void onContent(final String content) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        streamingAssistant.append(content);
                        transcript.appendAssistantDelta(content);
                    }
                });
            }

            @Override
            public void onStatus(final String status) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(status);
                    }
                });
            }

            @Override
            public void onComplete(final OllamaService.ChatResult result) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        finishTurn(result);
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
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

    /** Refill the audio-model dropdown without triggering the persistence listener. */
    private void refillAudioModelCombo(List<String> names) {
        updatingAudioModelCombo = true;
        try {
            Object previous = audioModelCombo.getEditor().getItem();
            String configured = model.getSpeechToTextConfiguration().getModelName();
            audioModelCombo.removeAllItems();
            for (String name : names) {
                audioModelCombo.addItem(name);
            }
            if (previous != null && String.valueOf(previous).trim().length() > 0) {
                audioModelCombo.setSelectedItem(previous);
            } else if (configured.length() > 0) {
                audioModelCombo.setSelectedItem(configured);
            } else {
                audioModelCombo.setSelectedItem("");
            }
        } finally {
            updatingAudioModelCombo = false;
        }
    }

    /** Persist the chosen audio model as the speech-to-text model so it survives restarts. */
    private void persistAudioModelSelection() {
        if (updatingAudioModelCombo) {
            return;
        }
        String selected = selectedAudioModel();
        SpeechToTextConfiguration current = model.getSpeechToTextConfiguration();
        if (selected.equals(current.getModelName())) {
            return;
        }
        model.setSpeechToTextConfiguration(new SpeechToTextConfiguration(
                current.isEnabled(), current.getBackend(), selected, current.getLanguage(),
                current.getPrompt(), current.getMaxFileSizeMb(), current.getTimeoutSeconds()));
        model.saveSettings();
    }

    private String selectedAudioModel() {
        Object item = audioModelCombo.getEditor().getItem();
        return item == null ? "" : String.valueOf(item).trim();
    }

    /** The record button toggles: idle -> recording -> transcribe; while transcribing it cancels. */
    private void onRecordAction() {
        if (recordingSession != null) {
            stopRecordingAndTranscribe();
            return;
        }
        if (transcriptionTask != null || !pendingAudioFiles.isEmpty()) {
            cancelTranscription();
            return;
        }
        startRecording();
    }

    private void startRecording() {
        final PcmAudioFormat format = PcmAudioFormat.speechDefault();
        final File tempFile;
        try {
            tempFile = File.createTempFile("askai-speech-", ".wav");
        } catch (java.io.IOException ex) {
            transcript.appendInfo("Recording failed: could not create a temporary file: " + ex.getMessage());
            return;
        }
        SpeechCaptureConfiguration captureConfiguration = SpeechCaptureConfiguration.speechDefaults();
        final SpeechRecordingSession session = new SpeechRecordingSession(
                new JavaSoundMicrophoneSource(format, null),
                new WavFileAudioSink(tempFile),
                RecordSpeechInputUseCase.buildSpeechPipeline(captureConfiguration, null),
                captureConfiguration.getFrameDurationMillis());

        recordButton.setEnabled(false);
        setStatus("Opening microphone ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    session.start();
                    onUi(new Runnable() {
                        @Override
                        public void run() {
                            recordingSession = session;
                            recordingTempFile = tempFile;
                            recordButton.setText("Stop");
                            recordButton.setEnabled(true);
                            audioFileButton.setEnabled(false);
                            startRecordingTimer();
                        }
                    });
                } catch (final Exception ex) {
                    deleteQuietly(tempFile);
                    onUi(new Runnable() {
                        @Override
                        public void run() {
                            recordButton.setEnabled(true);
                            setStatus("Microphone not available.");
                            transcript.appendInfo("Recording failed: " + ex.getMessage());
                        }
                    });
                }
            }
        }, "askai-record-start").start();
    }

    private void stopRecordingAndTranscribe() {
        final SpeechRecordingSession session = recordingSession;
        final File tempFile = recordingTempFile;
        recordingSession = null;
        recordingTempFile = null;
        stopRecordingTimer();
        recordButton.setText("Record");
        recordButton.setEnabled(false);
        setStatus("Finishing recording ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Exception failure = null;
                try {
                    session.stop();
                } catch (Exception ex) {
                    failure = ex;
                }
                final Exception stopFailure = failure;
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        recordButton.setEnabled(true);
                        if (stopFailure != null) {
                            deleteQuietly(tempFile);
                            setStatus("Recording failed.");
                            transcript.appendInfo("Recording failed: " + stopFailure.getMessage());
                            return;
                        }
                        List<File> files = new ArrayList<File>();
                        files.add(tempFile);
                        beginTranscriptions(files, true);
                    }
                });
            }
        }, "askai-record-stop").start();
    }

    /** The arrow button picks one or more existing audio files and transcribes them immediately. */
    private void onAudioFileAction() {
        if (recordingSession != null || transcriptionTask != null || !pendingAudioFiles.isEmpty()) {
            return;
        }
        JFileChooser chooser = new JFileChooser(lastAudioDirectory);
        chooser.setDialogTitle("Transcribe audio file(s)");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Audio files (wav, mp3, m4a, ogg, flac)", DefaultSpeechToTextService.supportedExtensions()));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] selected = chooser.getSelectedFiles();
        if (selected == null || selected.length == 0) {
            return;
        }
        lastAudioDirectory = selected[0].getParentFile();
        List<File> files = new ArrayList<File>();
        for (int i = 0; i < selected.length; i++) {
            files.add(selected[i]);
        }
        beginTranscriptions(files, false);
    }

    /**
     * Start transcribing the given files sequentially. Each result is appended to the input field
     * as soon as it arrives; with more than one file every result is introduced by its file name.
     */
    private void beginTranscriptions(List<File> files, boolean deleteAfter) {
        pendingAudioFiles.clear();
        pendingAudioFiles.addAll(files);
        audioFileTotal = files.size();
        labelTranscriptions = files.size() > 1;
        deleteAudioAfterTranscription = deleteAfter;
        transcriptionCancelled = false;
        recordButton.setText("Cancel");
        audioFileButton.setEnabled(false);
        transcribeNext();
    }

    private void transcribeNext() {
        if (transcriptionCancelled || pendingAudioFiles.isEmpty()) {
            finishTranscriptions();
            return;
        }
        final File audioFile = pendingAudioFiles.remove(0);
        final int index = audioFileTotal - pendingAudioFiles.size();

        String sttModel = selectedAudioModel();
        final boolean usedChatModelFallback = sttModel.length() == 0;
        if (usedChatModelFallback) {
            Object selected = modelCombo.getSelectedItem();
            sttModel = selected == null ? "" : String.valueOf(selected);
        }

        setStatus(audioFileTotal > 1
                ? "Transcribing (" + index + "/" + audioFileTotal + "): " + audioFile.getName() + " ..."
                : "Transcribing audio ...");
        SpeechToTextService.TranscriptionRequest request = new SpeechToTextService.TranscriptionRequest(
                audioFile, sttModel, "", "");
        transcriptionTask = speechToTextService.transcribe(request, new SpeechToTextService.TranscriptionListener() {
            @Override
            public void onTranscription(final String text) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        transcriptionTask = null;
                        cleanUpTranscribedFile(audioFile);
                        insertTranscription(labelTranscriptions
                                ? "[" + audioFile.getName() + "]\n" + text : text);
                        transcribeNext();
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        transcriptionTask = null;
                        cleanUpTranscribedFile(audioFile);
                        if (!transcriptionCancelled) {
                            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                            if (usedChatModelFallback) {
                                message += " (No dedicated audio model is selected, so the chat model was"
                                        + " used — it may not support audio input. Pick an audio-capable"
                                        + " model in the \"Audio model\" dropdown.)";
                            }
                            transcript.appendInfo("Transcription failed for "
                                    + audioFile.getName() + ": " + message);
                        }
                        transcribeNext();
                    }
                });
            }
        });
    }

    private void finishTranscriptions() {
        boolean cancelled = transcriptionCancelled;
        pendingAudioFiles.clear();
        transcriptionCancelled = false;
        recordButton.setText("Record");
        recordButton.setEnabled(true);
        audioFileButton.setEnabled(true);
        setStatus(cancelled
                ? "Transcription cancelled."
                : "Transcription ready. Review the text and press Send.");
    }

    private void cancelTranscription() {
        transcriptionCancelled = true;
        SpeechToTextService.Task task = transcriptionTask;
        if (task != null) {
            task.cancel();
        } else {
            finishTranscriptions();
        }
    }

    /** Delete recorded temp files after transcription; never touch files the user picked. */
    private void cleanUpTranscribedFile(File audioFile) {
        if (deleteAudioAfterTranscription) {
            deleteQuietly(audioFile);
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.isFile()) {
            file.delete();
        }
    }

    private void startRecordingTimer() {
        final long startedAtMillis = System.currentTimeMillis();
        recordingTimer = new Timer(500, event -> {
            long seconds = (System.currentTimeMillis() - startedAtMillis) / 1000L;
            setStatus("Recording ... " + seconds + "s — click Stop to transcribe.");
        });
        recordingTimer.start();
        setStatus("Recording ... speak now.");
    }

    private void stopRecordingTimer() {
        if (recordingTimer != null) {
            recordingTimer.stop();
            recordingTimer = null;
        }
    }

    /** Writes the transcription into the input field without sending; existing text is kept. */
    private void insertTranscription(String text) {
        String existing = inputArea.getText();
        if (existing != null && existing.trim().length() > 0) {
            inputArea.setText(existing + "\n" + text);
        } else {
            inputArea.setText(text);
        }
        inputArea.requestFocusInWindow();
        inputArea.setCaretPosition(inputArea.getText().length());
    }

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

    private void setBusy(boolean busy) {
        sendButton.setEnabled(!busy);
        stopButton.setEnabled(busy);
        modelCombo.setEnabled(!busy);
        inputArea.setEnabled(!busy);
        // Speech-to-text is unavailable while a chat is streaming, and hidden behind the
        // configuration switch so the feature can be turned off entirely.
        boolean speechAvailable = !busy && model.getSpeechToTextConfiguration().isEnabled();
        recordButton.setEnabled(speechAvailable);
        audioFileButton.setEnabled(speechAvailable);
        if (!busy) {
            inputArea.requestFocusInWindow();
        }
    }

    private void setStatus(String status) {
        statusLabel.setText(status);
    }

    private static void onUi(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }
}
