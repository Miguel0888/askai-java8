package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.AskAiModel;
import com.aresstack.askai.java8.client.OllamaChatTurn;
import com.aresstack.askai.java8.service.OllamaService;
import com.aresstack.askai.java8.stt.DefaultSpeechToTextService;
import com.aresstack.askai.java8.stt.SpeechToTextService;

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
    private final JTextField keepAliveField;
    private final JTextArea systemPromptArea;
    private final JTextArea inputArea;
    private final ChatTranscript transcript;
    private final JLabel statusLabel;
    private final JButton sendButton;
    private final JButton stopButton;
    private final JButton audioButton;

    private final List<OllamaChatTurn> history = new ArrayList<OllamaChatTurn>();
    private final StringBuilder streamingAssistant = new StringBuilder();
    private OllamaService.Task chatTask;
    private SpeechToTextService.Task transcriptionTask;
    private File lastAudioDirectory;
    private Timer elapsedTimer;
    private long requestStartedAtMillis;

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
        this.audioButton = new JButton("Audio...");
        this.audioButton.setToolTipText("Transcribe an audio file into the message field (speech-to-text)");
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
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(event -> refreshModels());
        toolbar.add(refreshButton);
        JButton newChatButton = new JButton("New chat");
        newChatButton.addActionListener(event -> newChat());
        toolbar.add(newChatButton);
        toolbar.add(new JLabel("keep_alive"));
        toolbar.add(keepAliveField);

        JPanel system = new JPanel(new BorderLayout(6, 2));
        system.setBorder(BorderFactory.createTitledBorder("System prompt"));
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        system.add(new JScrollPane(systemPromptArea), BorderLayout.CENTER);

        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.add(toolbar, BorderLayout.NORTH);
        header.add(system, BorderLayout.CENTER);
        return header;
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
        audioButton.setAlignmentX(CENTER_ALIGNMENT);
        sendButton.addActionListener(event -> sendChat());
        stopButton.addActionListener(event -> stopChat());
        audioButton.addActionListener(event -> onAudioAction());
        buttons.add(sendButton);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(stopButton);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(audioButton);

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

    /** The Audio button starts a transcription, or cancels the one in flight. */
    private void onAudioAction() {
        if (transcriptionTask != null) {
            cancelTranscription();
            return;
        }
        JFileChooser chooser = new JFileChooser(lastAudioDirectory);
        chooser.setDialogTitle("Transcribe audio");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Audio files (wav, mp3, m4a, ogg, flac)", DefaultSpeechToTextService.supportedExtensions()));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File audioFile = chooser.getSelectedFile();
        lastAudioDirectory = audioFile.getParentFile();
        transcribe(audioFile);
    }

    private void transcribe(File audioFile) {
        String sttModel = model.getSpeechToTextConfiguration().getModelName();
        boolean fallback = sttModel.length() == 0;
        if (fallback) {
            Object selected = modelCombo.getSelectedItem();
            sttModel = selected == null ? "" : String.valueOf(selected);
        }
        final boolean usedChatModelFallback = fallback;

        audioButton.setText("Cancel");
        setStatus("Transcribing audio ...");
        SpeechToTextService.TranscriptionRequest request = new SpeechToTextService.TranscriptionRequest(
                audioFile, sttModel, "", "");
        transcriptionTask = speechToTextService.transcribe(request, new SpeechToTextService.TranscriptionListener() {
            @Override
            public void onTranscription(final String text) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        finishTranscription();
                        insertTranscription(text);
                        setStatus("Transcription ready. Review the text and press Send.");
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        finishTranscription();
                        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                        if (usedChatModelFallback) {
                            message += " (No dedicated STT model is configured, so the current chat model"
                                    + " was used — it may not support audio input. Configure a"
                                    + " Speech-to-Text model under Configuration > Connections.)";
                        }
                        setStatus("Transcription failed.");
                        transcript.appendInfo("Transcription failed: " + message);
                    }
                });
            }
        });
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

    private void cancelTranscription() {
        SpeechToTextService.Task task = transcriptionTask;
        if (task != null) {
            task.cancel();
        }
        finishTranscription();
        setStatus("Transcription cancelled.");
    }

    private void finishTranscription() {
        transcriptionTask = null;
        audioButton.setText("Audio...");
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
        audioButton.setEnabled(!busy && model.getSpeechToTextConfiguration().isEnabled());
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
