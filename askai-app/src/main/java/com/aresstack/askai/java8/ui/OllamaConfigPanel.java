package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.FlowLayout;

public final class OllamaConfigPanel extends JPanel {

    private final AppConfigurationRepository configurationRepository;
    private final JTextField baseUrlField;
    private final JTextField keepAliveField;
    private final JLabel statusLabel;

    public OllamaConfigPanel(AppConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
        this.baseUrlField = new JTextField(28);
        this.keepAliveField = new JTextField(8);
        this.statusLabel = new JLabel(" ");
        buildUserInterface();
        load();
    }

    private void buildUserInterface() {
        setLayout(new FlowLayout(FlowLayout.LEFT, 8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(new JLabel("Ollama URL"));
        add(baseUrlField);
        add(new JLabel("Default keep_alive"));
        add(keepAliveField);
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(event -> save());
        add(saveButton);
        add(statusLabel);
    }

    public void load() {
        AppConfiguration configuration = configurationRepository.load();
        baseUrlField.setText(configuration.getOllamaBaseUrl());
        keepAliveField.setText(configuration.getKeepAlive());
    }

    private void save() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(new AppConfiguration(
                baseUrlField.getText(),
                keepAliveField.getText(),
                current.getProxyConfiguration(),
                current.getCertificateTrustConfiguration(),
                current.getHttpClientConfiguration(),
                current.getHuggingFaceToken(),
                current.getModelDownloadDirectory()));
        statusLabel.setText("Saved.");
    }
}
