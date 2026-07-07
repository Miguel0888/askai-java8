package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.service.AskAiService;
import com.aresstack.askai.java8.service.ChatRequest;
import com.aresstack.askai.java8.service.ChatSummary;
import io.github.ollama4j.models.Model;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;

public final class OllamaChatPanel extends JPanel {

    private final AppConfigurationRepository configurationRepository;
    private final AskAiService askAiService;
    private final JComboBox<String> modelCombo;
    private final JTextField keepAliveField;
    private final JTextArea transcriptArea;
    private final JTextArea inputArea;
    private final JLabel statusLabel;
    private final JButton sendButton;
    private final JButton refreshButton;

    public OllamaChatPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        this.modelCombo = new JComboBox<String>();
        this.keepAliveField = new JTextField(configurationRepository.load().getKeepAlive(), 6);
        this.transcriptArea = new JTextArea();
        this.inputArea = new JTextArea(3, 40);
        this.statusLabel = new JLabel("Select a model and start chatting.");
        this.sendButton = new JButton("Send");
        this.refreshButton = new JButton("Refresh");
        buildUserInterface();
        refreshModels();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        transcriptArea.setEditable(false);
        transcriptArea.setLineWrap(true);
        transcriptArea.setWrapStyleWord(true);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        add(buildToolbar(), BorderLayout.NORTH);
        JScrollPane transcriptScroll = new JScrollPane(transcriptArea);
        transcriptScroll.setBorder(BorderFactory.createTitledBorder("Conversation"));
        add(transcriptScroll, BorderLayout.CENTER);
        add(buildComposer(), BorderLayout.SOUTH);
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.add(new JLabel("Model"));
        modelCombo.setPreferredSize(new Dimension(260, modelCombo.getPreferredSize().height));
        toolbar.add(modelCombo);
        refreshButton.addActionListener(event -> refreshModels());
        toolbar.add(refreshButton);
        JButton newChatButton = new JButton("New chat");
        newChatButton.addActionListener(event -> newChat());
        toolbar.add(newChatButton);
        toolbar.add(new JLabel("keep_alive"));
        toolbar.add(keepAliveField);
        return toolbar;
    }

    private JPanel buildComposer() {
        JPanel composer = new JPanel(new BorderLayout(8, 4));
        composer.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        sendButton.addActionListener(event -> sendChat());
        composer.add(sendButton, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(composer, BorderLayout.CENTER);
        south.add(statusLabel, BorderLayout.SOUTH);
        return south;
    }

    public void onShown() {
        refreshModels();
    }

    private void refreshModels() {
        setStatus("Loading models from " + configurationRepository.load().getOllamaBaseUrl() + " ...");
        askAiService.listModels(new AskAiService.ModelListListener() {
            public void onModels(final List<Model> models) {
                onUi(new Runnable() {
                    public void run() {
                        Object previous = modelCombo.getSelectedItem();
                        DefaultComboBoxModel<String> comboBoxModel = new DefaultComboBoxModel<String>();
                        for (Model model : models) {
                            comboBoxModel.addElement(model.getName());
                        }
                        modelCombo.setModel(comboBoxModel);
                        if (previous != null) {
                            modelCombo.setSelectedItem(previous);
                        }
                        if (models.isEmpty()) {
                            setStatus("No models installed. Open Install to add one.");
                        } else {
                            setStatus("Ready. " + models.size() + " model(s) available.");
                        }
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        setStatus("Server not reachable. Check Connections.");
                        transcriptArea.append("Connection error: " + ex.getMessage() + "\n");
                    }
                });
            }
        });
    }

    private void newChat() {
        transcriptArea.setText("New conversation. Type a message below and press Send.\n");
        setStatus("Started a new chat.");
    }

    private void sendChat() {
        final String modelName = modelCombo.getSelectedItem() == null ? "" : String.valueOf(modelCombo.getSelectedItem());
        final String text = inputArea.getText().trim();
        if (modelName.length() == 0 || text.length() == 0) {
            setStatus("Choose a model and write a message before sending.");
            return;
        }
        inputArea.setText("");
        transcriptArea.append("\nYou:\n" + text + "\n\nAssistant:\n");
        setBusy(true);
        askAiService.sendChat(new ChatRequest(modelName, text), new AskAiService.ChatListener() {
            public void onToken(final String token) {
                onUi(new Runnable() {
                    public void run() {
                        transcriptArea.append(token);
                        transcriptArea.setCaretPosition(transcriptArea.getDocument().getLength());
                    }
                });
            }

            public void onComplete(final ChatSummary summary) {
                onUi(new Runnable() {
                    public void run() {
                        transcriptArea.append("\n");
                        setBusy(false);
                        if (summary.hasMetrics()) {
                            setStatus(String.format("Ready · %.1f tok/s", summary.tokensPerSecond()));
                        } else {
                            setStatus("Ready.");
                        }
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        transcriptArea.append("[error: " + ex.getMessage() + "]\n");
                        setBusy(false);
                        setStatus("Chat failed.");
                    }
                });
            }
        });
    }

    private void setBusy(boolean busy) {
        sendButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        modelCombo.setEnabled(!busy);
        inputArea.setEnabled(!busy);
    }

    private void setStatus(String status) {
        statusLabel.setText(status);
    }

    private static void onUi(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }
}
