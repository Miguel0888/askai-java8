package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.service.AskAiService;
import com.aresstack.askai.java8.service.ChatRequest;
import com.aresstack.askai.java8.service.ChatSummary;
import io.github.ollama4j.models.Model;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

public final class AskAiFrame {

    private final AppConfigurationRepository configurationRepository;
    private final AskAiService askAiService;
    private final JFrame frame;
    private final JTextField baseUrlField;
    private final JTextField keepAliveField;
    private final JComboBox<String> modelComboBox;
    private final JTextArea conversationArea;
    private final JTextArea inputArea;
    private final JLabel statusLabel;
    private final JButton sendButton;
    private final JButton refreshButton;
    private final JButton saveButton;
    private final JButton modelManagerButton;

    public AskAiFrame(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        this.frame = new JFrame("AskAI Java 8");
        this.baseUrlField = new JTextField(24);
        this.keepAliveField = new JTextField(8);
        this.modelComboBox = new JComboBox<String>(new DefaultComboBoxModel<String>());
        this.conversationArea = new JTextArea();
        this.inputArea = new JTextArea(4, 80);
        this.statusLabel = new JLabel("Ready.");
        this.sendButton = new JButton("Send");
        this.refreshButton = new JButton("Refresh models");
        this.saveButton = new JButton("Save settings");
        this.modelManagerButton = new JButton("Models / Downloads");
    }

    public void showFrame() {
        configureFrame();
        loadConfiguration();
        refreshModels();
        frame.setVisible(true);
    }

    private void configureFrame() {
        conversationArea.setEditable(false);
        conversationArea.setLineWrap(true);
        conversationArea.setWrapStyleWord(true);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);

        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setJMenuBar(createMenuBar());
        frame.setLayout(new BorderLayout(8, 8));
        frame.add(createTopPanel(), BorderLayout.NORTH);
        frame.add(createCenterPanel(), BorderLayout.CENTER);
        frame.add(createBottomPanel(), BorderLayout.SOUTH);
        frame.setSize(1100, 760);
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent event) {
                askAiService.shutdown();
            }
        });
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu modelsMenu = new JMenu("Models");
        JMenuItem manageModelsItem = new JMenuItem("Models / Downloads...");
        JMenuItem refreshModelsItem = new JMenuItem("Refresh installed models");
        manageModelsItem.addActionListener(event -> openModelManager());
        refreshModelsItem.addActionListener(event -> refreshModels());
        modelsMenu.add(manageModelsItem);
        modelsMenu.add(refreshModelsItem);
        menuBar.add(modelsMenu);
        return menuBar;
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));
        panel.add(new JLabel("Ollama URL:"));
        panel.add(baseUrlField);
        panel.add(new JLabel("Keep alive:"));
        panel.add(keepAliveField);
        panel.add(saveButton);
        panel.add(new JLabel("Model:"));
        panel.add(modelComboBox);
        panel.add(refreshButton);
        panel.add(modelManagerButton);

        saveButton.addActionListener(event -> saveConfiguration());
        refreshButton.addActionListener(event -> refreshModels());
        modelManagerButton.addActionListener(event -> openModelManager());
        return panel;
    }

    private JSplitPane createCenterPanel() {
        JScrollPane conversationScrollPane = new JScrollPane(conversationArea);
        conversationScrollPane.setBorder(BorderFactory.createTitledBorder("Conversation"));
        JScrollPane inputScrollPane = new JScrollPane(inputArea);
        inputScrollPane.setBorder(BorderFactory.createTitledBorder("Input"));
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, conversationScrollPane, inputScrollPane);
        splitPane.setResizeWeight(0.78d);
        return splitPane;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));
        panel.add(statusLabel, BorderLayout.CENTER);
        panel.add(sendButton, BorderLayout.EAST);
        sendButton.addActionListener(event -> sendInput());
        return panel;
    }

    private void loadConfiguration() {
        AppConfiguration configuration = configurationRepository.load();
        baseUrlField.setText(configuration.getOllamaBaseUrl());
        keepAliveField.setText(configuration.getKeepAlive());
    }

    private void saveConfiguration() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(new AppConfiguration(
                baseUrlField.getText(),
                keepAliveField.getText(),
                current.getProxyConfiguration(),
                current.getHuggingFaceToken(),
                current.getModelDownloadDirectory()));
        setStatus("Settings saved.");
    }

    private void openModelManager() {
        saveConfiguration();
        ModelManagerDialog dialog = new ModelManagerDialog(frame, configurationRepository, askAiService);
        dialog.showDialog();
        loadConfiguration();
        refreshModels();
    }

    private void refreshModels() {
        saveConfiguration();
        setBusy(true, "Loading models...");
        askAiService.listModels(new AskAiService.ModelListListener() {
            public void onModels(final List<Model> models) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        DefaultComboBoxModel<String> comboBoxModel = new DefaultComboBoxModel<String>();
                        for (Model model : models) {
                            comboBoxModel.addElement(model.getName());
                        }
                        modelComboBox.setModel(comboBoxModel);
                        setBusy(false, models.size() + " model(s) loaded.");
                    }
                });
            }

            public void onError(final Exception ex) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        setBusy(false, "Could not load models.");
                        showError(ex);
                    }
                });
            }
        });
    }

    private void sendInput() {
        String modelName = selectedModelName();
        String text = inputArea.getText();
        if (modelName.length() == 0 || text.trim().length() == 0) {
            JOptionPane.showMessageDialog(frame, "Choose a model and enter text.", "AskAI Java 8", JOptionPane.WARNING_MESSAGE);
            return;
        }
        appendConversation("\nYou:\n" + text + "\n\nAssistant:\n");
        inputArea.setText("");
        setBusy(true, "Generating...");
        askAiService.sendChat(new ChatRequest(modelName, text), new AskAiService.ChatListener() {
            public void onToken(final String token) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        appendConversation(token);
                    }
                });
            }

            public void onComplete(final ChatSummary summary) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        appendConversation("\n");
                        if (summary.hasMetrics()) {
                            setBusy(false, String.format("Done. %.2f tokens/s", summary.tokensPerSecond()));
                        } else {
                            setBusy(false, "Done.");
                        }
                    }
                });
            }

            public void onError(final Exception ex) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        appendConversation("\n[Error] " + ex.getMessage() + "\n");
                        setBusy(false, "Generation failed.");
                        showError(ex);
                    }
                });
            }
        });
    }

    private String selectedModelName() {
        Object selected = modelComboBox.getSelectedItem();
        return selected == null ? "" : String.valueOf(selected);
    }

    private void appendConversation(String text) {
        conversationArea.append(text);
        conversationArea.setCaretPosition(conversationArea.getDocument().getLength());
    }

    private void setBusy(boolean busy, String status) {
        sendButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        saveButton.setEnabled(!busy);
        modelManagerButton.setEnabled(!busy);
        setStatus(status);
    }

    private void setStatus(String status) {
        statusLabel.setText(status == null ? "" : status);
    }

    private void showError(Exception ex) {
        JOptionPane.showMessageDialog(frame, ex.getMessage(), "AskAI Java 8", JOptionPane.ERROR_MESSAGE);
    }
}
