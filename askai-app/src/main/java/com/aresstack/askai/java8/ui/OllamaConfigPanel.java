package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.AskAiModel;
import com.aresstack.askai.java8.service.OllamaService;
import com.aresstack.askai.java8.settings.AskAiPaths;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Paths;

/**
 * Edits Ollama endpoint and local model storage settings.
 */
public final class OllamaConfigPanel extends JPanel {

    private final AskAiModel model;
    private final OllamaService ollamaService;
    private final JTextField baseUrlField;
    private final JTextField modelRootField;
    private final JTextField quantizationField;
    private final JTextField keepAliveField;
    private final JTextArea logArea;

    public OllamaConfigPanel(AskAiModel model, OllamaService ollamaService) {
        this.model = model;
        this.ollamaService = ollamaService;
        this.baseUrlField = new JTextField(model.getOllamaBaseUrl(), 42);
        this.modelRootField = new JTextField(model.getModelRoot().toString(), 42);
        this.quantizationField = new JTextField(model.getDefaultQuantization(), 20);
        this.keepAliveField = new JTextField(model.getDefaultKeepAlive(), 20);
        this.logArea = new JTextArea(16, 70);
        buildUserInterface();
        append("Config loaded.");
        append("Settings file: " + AskAiPaths.settingsFile());
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Ollama endpoint"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        addRow(form, constraints, 0, "Base URL", baseUrlField);
        addRow(form, constraints, 1, "Local model root", modelRootField);
        addRow(form, constraints, 2, "Create quantization", quantizationField);
        addRow(form, constraints, 3, "Chat keep_alive", keepAliveField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(event -> save());
        JButton testButton = new JButton("Test Connection");
        testButton.addActionListener(event -> testConnection());
        buttons.add(saveButton);
        buttons.add(testButton);

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(form, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);
    }

    private void addRow(JPanel form, GridBagConstraints constraints, int row, String label, JTextField field) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0.0d;
        form.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        form.add(field, constraints);
    }

    private void save() {
        model.setOllamaBaseUrl(baseUrlField.getText());
        model.setModelRoot(Paths.get(modelRootField.getText().trim()));
        model.setDefaultQuantization(quantizationField.getText());
        model.setDefaultKeepAlive(keepAliveField.getText());
        model.saveSettings();
        append("Saved settings. Ollama: " + model.getOllamaBaseUrl());
    }

    private void testConnection() {
        save();
        append("Testing " + model.getOllamaBaseUrl() + " ...");
        ollamaService.getServerVersion(new OllamaService.ServerVersionListener() {
            @Override
            public void onServerVersion(final String version) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        append("Connection OK: " + (version == null || version.isEmpty() ? "Ollama reachable" : version));
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        append("ERROR: " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void append(String message) {
        UiSupport.appendLog(logArea, message);
    }

    private static void onUi(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }
}
