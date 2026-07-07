package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.net.ProxyConfiguration;
import com.aresstack.winproxy.ProxyDefaults;

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

    private static final String[] UI_MODES = {
            ProxyConfiguration.DISABLED,
            ProxyConfiguration.MANUAL_PROXY,
            ProxyConfiguration.WINDOWS_STATIC_PROXY,
            ProxyConfiguration.PAC_URL_MANUAL,
            ProxyConfiguration.PAC_URL_POWERSHELL,
            ProxyConfiguration.PAC_URL_WSCRIPT,
            ProxyConfiguration.PAC_URL_WINDOWS_SETTINGS,
            ProxyConfiguration.WINDOWS_NATIVE_PROXY_SETTINGS,
            ProxyConfiguration.WINDOWS_NATIVE_ROUTE_RESOLVER
    };

    private final AppConfigurationRepository configurationRepository;
    private final JComboBox<String> mode;
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

        this.mode = new JComboBox<String>(UI_MODES);
        this.mode.setSelectedItem(cfg.getModeName());
        this.testUrl = new JTextField(cfg.getTestUrl());
        this.pacUrlDiscoveryScript = new JTextField(defaultScriptFor(cfg));
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
        append("PAC_URL_POWERSHELL: field contains a PowerShell discovery script.");
        append("PAC_URL_WSCRIPT: field contains a VBScript/WScript discovery script.");
        append("PAC_URL_MANUAL: field contains the PAC/WPAD URL directly.");
        installLiveBindings();
        appendModeHelp(selectedMode());
    }

    private void installLiveBindings() {
        mode.addActionListener(e -> {
            if (ProxyConfiguration.PAC_URL_WSCRIPT.equals(selectedMode())) {
                ProxyConfiguration current = buildConfiguration();
                if (isDefaultPowerShellScript(current.getPacUrlDiscoveryScript())) {
                    pacUrlDiscoveryScript.setText(com.aresstack.askai.java8.net.PacUrlDiscoveryService.defaultScript());
                }
            }
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
        mode.setSelectedItem(cfg.getModeName());
        testUrl.setText(cfg.getTestUrl());
        pacUrlDiscoveryScript.setText(defaultScriptFor(cfg));
        manualHost.setText(empty(cfg.getManualProxyHost()));
        manualPort.setText(cfg.getManualProxyPort() > 0 ? String.valueOf(cfg.getManualProxyPort()) : "");
        writeConfiguration();
        append("Reset to win-proxy-java defaults: " + cfg.getModeName());
        appendModeHelp(cfg.getModeName());
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
                            "Proxy Test - " + cfg.getModeName(), JOptionPane.INFORMATION_MESSAGE);
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

    private String selectedMode() {
        Object selected = mode.getSelectedItem();
        return selected == null ? ProxyConfiguration.defaults().getModeName() : String.valueOf(selected);
    }

    private void appendModeHelp(String selectedMode) {
        if (ProxyConfiguration.PAC_URL_MANUAL.equals(selectedMode)) {
            append("PAC_URL_MANUAL: paste the PAC/WPAD URL into 'PAC URL discovery script'.");
        } else if (ProxyConfiguration.PAC_URL_WSCRIPT.equals(selectedMode)) {
            append("PAC_URL_WSCRIPT: the field contains VBScript that prints the PAC/WPAD URL.");
        } else if (ProxyConfiguration.PAC_URL_WINDOWS_SETTINGS.equals(selectedMode)) {
            append("PAC_URL_WINDOWS_SETTINGS: the PAC URL is taken from Windows proxy settings.");
        } else if (ProxyConfiguration.PAC_URL_POWERSHELL.equals(selectedMode)) {
            append("PAC_URL_POWERSHELL: the field contains the PowerShell discovery script.");
        } else if (ProxyConfiguration.MANUAL_PROXY.equals(selectedMode)) {
            append("MANUAL_PROXY: enter proxy host and port; PAC fields are ignored.");
        }
    }

    private String defaultScriptFor(ProxyConfiguration cfg) {
        if (ProxyConfiguration.PAC_URL_WSCRIPT.equals(cfg.getModeName())) {
            return com.aresstack.askai.java8.net.PacUrlDiscoveryService.defaultScript();
        }
        return nonBlank(cfg.getPacUrlDiscoveryScript(), ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT);
    }

    private boolean isDefaultPowerShellScript(String value) {
        return value == null || value.trim().length() == 0 || ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT.equals(value);
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
