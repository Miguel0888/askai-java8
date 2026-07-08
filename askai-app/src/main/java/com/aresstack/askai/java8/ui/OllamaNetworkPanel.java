package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.net.ProxyConfiguration;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.FlowLayout;

public final class OllamaNetworkPanel extends JPanel {

    private final AppConfigurationRepository configurationRepository;
    private final JCheckBox enabledBox;
    private final JTextField hostField;
    private final JTextField portField;
    private final JTextField downloadDirectoryField;
    private final JLabel statusLabel;

    public OllamaNetworkPanel(AppConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
        this.enabledBox = new JCheckBox("Use HTTP proxy for HuggingFace");
        this.hostField = new JTextField(18);
        this.portField = new JTextField(6);
        this.downloadDirectoryField = new JTextField(34);
        this.statusLabel = new JLabel(" ");
        buildUserInterface();
        load();
    }

    private void buildUserInterface() {
        setLayout(new FlowLayout(FlowLayout.LEFT, 8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(enabledBox);
        add(new JLabel("Host"));
        add(hostField);
        add(new JLabel("Port"));
        add(portField);
        add(new JLabel("Download directory"));
        add(downloadDirectoryField);
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(event -> save());
        add(saveButton);
        add(statusLabel);
    }

    private void load() {
        AppConfiguration configuration = configurationRepository.load();
        enabledBox.setSelected(configuration.getProxyConfiguration().isEnabled());
        hostField.setText(configuration.getProxyConfiguration().getHost());
        portField.setText(String.valueOf(configuration.getProxyConfiguration().getPort()));
        downloadDirectoryField.setText(configuration.getModelDownloadDirectory().getAbsolutePath());
    }

    private void save() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(new AppConfiguration(
                current.getOllamaBaseUrl(),
                current.getKeepAlive(),
                new ProxyConfiguration(enabledBox.isSelected(), hostField.getText(), parseInt(portField.getText())),
                current.getCertificateTrustConfiguration(),
                current.getHttpClientConfiguration(),
                current.getHuggingFaceToken(),
                new java.io.File(downloadDirectoryField.getText())));
        statusLabel.setText("Saved.");
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
