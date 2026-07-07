package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.net.ProxyConfiguration;
import com.aresstack.winproxy.ProxyDefaults;
import com.aresstack.winproxy.ProxyMode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;

public final class ProxyPanel extends JPanel {

    private static final ProxyMode[] UI_MODES = {
            ProxyMode.DISABLED,
            ProxyMode.MANUAL_PROXY,
            ProxyMode.WINDOWS_STATIC_PROXY,
            ProxyMode.PAC_URL_MANUAL,
            ProxyMode.PAC_URL_POWERSHELL,
            ProxyMode.PAC_URL_WINDOWS_SETTINGS,
            ProxyMode.WINDOWS_NATIVE_PROXY_SETTINGS,
            ProxyMode.WINDOWS_NATIVE_ROUTE_RESOLVER
    };

    private final AppConfigurationRepository configurationRepository;
    private final JComboBox<ProxyMode> mode;
    private final JTextField testUrl;
    private final JTextField pacUrlDiscoveryScript;
    private final JTextField manualHost;
    private final JTextField manualPort;
    private final JTextArea log;

    public ProxyPanel(AppConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
        ProxyConfiguration cfg = configurationRepository.load().getProxyConfiguration();
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        this.mode = new JComboBox<ProxyMode>(UI_MODES);
        this.mode.setSelectedItem(cfg.getMode());
        this.testUrl = new JTextField(cfg.getTestUrl());
        this.pacUrlDiscoveryScript = new JTextField(defaultPacScript(cfg));
        this.manualHost = new JTextField(empty(cfg.getManualProxyHost()));
        this.manualPort = new JTextField(cfg.getManualProxyPort() > 0 ? String.valueOf(cfg.getManualProxyPort()) : "");

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.setBorder(BorderFactory.createTitledBorder("Proxy for model downloads"));
        form.add(new JLabel("Mode"));
        form.add(mode);
        form.add(new JLabel("Test URL"));
        form.add(testUrl);
        form.add(new JLabel("PAC URL discovery script"));
        form.add(pacUrlDiscoveryScript);
        form.add(new JLabel("Manual host"));
        form.add(manualHost);
        form.add(new JLabel("Manual port"));
        form.add(manualPort);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton test = new JButton("Resolve test URL");
        test.addActionListener(e -> resolveTestUrl());
        JButton defaults = new JButton("Reset default");
        defaults.addActionListener(e -> resetDefault());
        buttons.add(test);
        buttons.add(defaults);

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(form, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        log = new JTextArea(12, 60);
        log.setEditable(false);
        add(new JScrollPane(log), BorderLayout.CENTER);
        append("Proxy changes apply immediately to all Hugging Face operations.");
        append("For PAC_URL_MANUAL, put the PAC/WPAD URL directly into 'PAC URL discovery script'.");
        append("For discovery modes, the configured discovery script is executed automatically while resolving.");
        append("If PowerShell/Windows discovery fails, AskAI also tries Windows Script Host registry discovery automatically.");
        installLiveBindings();
        appendModeHelp(selectedMode());
    }

    private void installLiveBindings() {
        mode.addActionListener(e -> {
            writeConfiguration();
            append("Proxy mode: " + selectedMode());
            appendModeHelp(selectedMode());
        });
        DocumentListener onEdit = new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { writeConfiguration(); }
            public void removeUpdate(DocumentEvent event) { writeConfiguration(); }
            public void changedUpdate(DocumentEvent event) { writeConfiguration(); }
        };
        JTextComponent[] fields = new JTextComponent[]{manualHost, manualPort, testUrl, pacUrlDiscoveryScript};
        for (int i = 0; i < fields.length; i++) {
            fields[i].getDocument().addDocumentListener(onEdit);
        }
    }

    private void writeConfiguration() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(new AppConfiguration(
                current.getOllamaBaseUrl(),
                current.getKeepAlive(),
                buildConfiguration(),
                current.getHuggingFaceToken(),
                current.getModelDownloadDirectory()));
    }

    private void resetDefault() {
        ProxyConfiguration cfg = ProxyConfiguration.defaults();
        mode.setSelectedItem(cfg.getMode());
        testUrl.setText(cfg.getTestUrl());
        pacUrlDiscoveryScript.setText(defaultPacScript(cfg));
        manualHost.setText(empty(cfg.getManualProxyHost()));
        manualPort.setText(cfg.getManualProxyPort() > 0 ? String.valueOf(cfg.getManualProxyPort()) : "");
        writeConfiguration();
        append("Reset to win-proxy-java defaults: " + cfg.getMode());
        appendModeHelp(cfg.getMode());
    }

    private void resolveTestUrl() {
        final ProxyConfiguration cfg = buildConfiguration();
        writeConfiguration();
        final String url = cfg.getTestUrl();
        try {
            cfg.validateForUse();
        } catch (Exception ex) {
            append("ERROR: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Proxy configuration incomplete", JOptionPane.WARNING_MESSAGE);
            return;
        }
        append("Resolving " + url + " ...");
        new SwingWorker<Object, Void>() {
            protected Object doInBackground() {
                return cfg.resolve(url);
            }

            protected void done() {
                try {
                    Object result = get();
                    String detail = String.valueOf(result);
                    append("Result: " + detail);
                    JOptionPane.showMessageDialog(ProxyPanel.this, detail,
                            "Proxy Test - " + cfg.getMode(), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    append("ERROR: " + rootMessage(ex));
                }
            }
        }.execute();
    }

    private ProxyConfiguration buildConfiguration() {
        return new ProxyConfiguration(
                selectedMode(),
                nonBlank(testUrl.getText(), ProxyConfiguration.defaults().getTestUrl()),
                nonBlank(pacUrlDiscoveryScript.getText(), ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT),
                "",
                empty(manualHost.getText()),
                parsePort(manualPort.getText()));
    }

    private ProxyMode selectedMode() {
        Object selected = mode.getSelectedItem();
        return selected instanceof ProxyMode ? (ProxyMode) selected : ProxyConfiguration.defaults().getMode();
    }

    private void appendModeHelp(ProxyMode selectedMode) {
        if (selectedMode == ProxyMode.PAC_URL_MANUAL) {
            append("PAC_URL_MANUAL: paste the PAC/WPAD URL into 'PAC URL discovery script'.");
        } else if (selectedMode == ProxyMode.PAC_URL_WINDOWS_SETTINGS) {
            append("PAC_URL_WINDOWS_SETTINGS: the PAC URL is taken from Windows proxy settings.");
        } else if (selectedMode == ProxyMode.PAC_URL_POWERSHELL) {
            append("PAC_URL_POWERSHELL: the field contains the PowerShell discovery script.");
        } else if (selectedMode == ProxyMode.MANUAL_PROXY) {
            append("MANUAL_PROXY: enter proxy host and port; PAC fields are ignored.");
        }
    }

    private void append(String message) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                log.append(message + System.lineSeparator());
            }
        });
    }

    private String rootMessage(Exception ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? String.valueOf(current) : current.getMessage();
    }

    private static String defaultPacScript(ProxyConfiguration cfg) {
        return nonBlank(cfg.getPacUrlDiscoveryScript(), ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT);
    }

    private static String nonBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    private static int parsePort(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return 0;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
