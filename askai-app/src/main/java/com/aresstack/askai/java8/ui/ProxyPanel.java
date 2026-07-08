package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.net.CertificateTrustConfiguration;
import com.aresstack.askai.java8.net.PacUrlDiscoveryService;
import com.aresstack.askai.java8.net.ProxyConfiguration;
import com.aresstack.askai.java8.service.AskAiService;
import com.aresstack.winproxy.ProxyDefaults;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
    private final AskAiService askAiService;
    private final JComboBox<String> mode;
    private final JTextField testUrl;
    private final JTextField pacUrlDiscoveryScript;
    private final JTextField manualHost;
    private final JTextField manualPort;
    private final JCheckBox trustJvmDefault;
    private final JCheckBox trustWindowsRoot;
    private final JCheckBox trustWindowsCaStores;
    private final JTextArea log;

    private String activeScriptMode;
    private String manualPacUrlText;
    private String powerShellScriptText;
    private String wScriptText;
    private String otherScriptText;
    private boolean updatingScriptText;

    public ProxyPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        AppConfiguration appConfiguration = configurationRepository.load();
        ProxyConfiguration cfg = appConfiguration.getProxyConfiguration();
        CertificateTrustConfiguration trust = appConfiguration.getCertificateTrustConfiguration();
        this.manualPacUrlText = "";
        this.powerShellScriptText = ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT;
        this.wScriptText = PacUrlDiscoveryService.defaultScript();
        this.otherScriptText = "";
        rememberScriptText(cfg.getModeName(), cfg.getPacUrlDiscoveryScript());
        this.activeScriptMode = cfg.getModeName();

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        this.mode = new JComboBox<String>(UI_MODES);
        this.mode.setSelectedItem(cfg.getModeName());
        this.testUrl = new JTextField(cfg.getTestUrl());
        this.pacUrlDiscoveryScript = new JTextField(scriptTextFor(cfg.getModeName()));
        this.manualHost = new JTextField(empty(cfg.getManualProxyHost()));
        this.manualPort = new JTextField(cfg.getManualProxyPort() > 0 ? String.valueOf(cfg.getManualProxyPort()) : "");
        this.trustJvmDefault = new JCheckBox("Use JVM default cacerts", trust.isUseJvmDefaultTrustStore());
        this.trustWindowsRoot = new JCheckBox("Use Windows-ROOT store", trust.isUseWindowsRootStore());
        this.trustWindowsCaStores = new JCheckBox("Use Windows Root/Intermediate stores", trust.isUseWindowsCaStores());

        JPanel proxyForm = new JPanel(new GridLayout(0, 2, 6, 6));
        proxyForm.setBorder(BorderFactory.createTitledBorder("Proxy resolution"));
        proxyForm.add(new JLabel("Mode"));
        proxyForm.add(mode);
        proxyForm.add(new JLabel("Test URL"));
        proxyForm.add(testUrl);
        proxyForm.add(new JLabel("PAC URL discovery script"));
        proxyForm.add(pacUrlDiscoveryScript);
        proxyForm.add(new JLabel("Manual host"));
        proxyForm.add(manualHost);
        proxyForm.add(new JLabel("Manual port"));
        proxyForm.add(manualPort);

        JPanel trustForm = new JPanel(new GridLayout(0, 1, 6, 6));
        trustForm.setBorder(BorderFactory.createTitledBorder("TLS certificate trust"));
        trustForm.add(trustJvmDefault);
        trustForm.add(trustWindowsRoot);
        trustForm.add(trustWindowsCaStores);

        JPanel forms = new JPanel(new BorderLayout(8, 8));
        forms.add(proxyForm, BorderLayout.NORTH);
        forms.add(trustForm, BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton test = new JButton("Resolve test URL");
        test.addActionListener(e -> resolveTestUrl());
        JButton hfTest = new JButton("Test HuggingFace HTTPS");
        hfTest.addActionListener(e -> testHuggingFaceHttps());
        JButton defaults = new JButton("Reset default");
        defaults.addActionListener(e -> resetDefault());
        buttons.add(test);
        buttons.add(hfTest);
        buttons.add(defaults);

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(forms, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        log = new JTextArea(12, 60);
        log.setEditable(false);
        add(new JScrollPane(log), BorderLayout.CENTER);
        append("Proxy settings and TLS trust settings are separate.");
        append("Resolve test URL checks only PAC/proxy resolution.");
        append("Test HuggingFace HTTPS performs the real proxy + TLS + certificate path.");
        append("PAC_URL_POWERSHELL: field contains a PowerShell discovery script.");
        append("PAC_URL_WSCRIPT: field contains a VBScript/WScript discovery script.");
        append("PAC_URL_MANUAL: field contains the PAC/WPAD URL directly.");
        installLiveBindings();
        appendModeHelp(selectedMode());
    }

    private void installLiveBindings() {
        mode.addActionListener(e -> switchScriptTextForSelectedMode());
        DocumentListener onEdit = new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { onFieldEdited(); }
            public void removeUpdate(DocumentEvent event) { onFieldEdited(); }
            public void changedUpdate(DocumentEvent event) { onFieldEdited(); }
        };
        JTextComponent[] fields = new JTextComponent[]{manualHost, manualPort, testUrl, pacUrlDiscoveryScript};
        for (int i = 0; i < fields.length; i++) {
            fields[i].getDocument().addDocumentListener(onEdit);
        }
        trustJvmDefault.addActionListener(e -> writeConfiguration());
        trustWindowsRoot.addActionListener(e -> writeConfiguration());
        trustWindowsCaStores.addActionListener(e -> writeConfiguration());
    }

    private void switchScriptTextForSelectedMode() {
        if (!updatingScriptText) {
            rememberScriptText(activeScriptMode, pacUrlDiscoveryScript.getText());
        }
        activeScriptMode = selectedMode();
        updatingScriptText = true;
        try {
            pacUrlDiscoveryScript.setText(scriptTextFor(activeScriptMode));
        } finally {
            updatingScriptText = false;
        }
        writeConfiguration();
        append("Proxy mode: " + activeScriptMode);
        appendModeHelp(activeScriptMode);
    }

    private void onFieldEdited() {
        if (!updatingScriptText) {
            rememberScriptText(activeScriptMode, pacUrlDiscoveryScript.getText());
        }
        writeConfiguration();
    }

    private void rememberScriptText(String modeName, String value) {
        String text = value == null ? "" : value;
        if (ProxyConfiguration.PAC_URL_MANUAL.equals(modeName)) {
            manualPacUrlText = text;
        } else if (ProxyConfiguration.PAC_URL_WSCRIPT.equals(modeName)) {
            wScriptText = text.length() == 0 ? PacUrlDiscoveryService.defaultScript() : text;
        } else if (ProxyConfiguration.PAC_URL_POWERSHELL.equals(modeName)) {
            powerShellScriptText = text.length() == 0 ? ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT : text;
        } else {
            otherScriptText = text;
        }
    }

    private String scriptTextFor(String modeName) {
        if (ProxyConfiguration.PAC_URL_MANUAL.equals(modeName)) {
            return manualPacUrlText;
        }
        if (ProxyConfiguration.PAC_URL_WSCRIPT.equals(modeName)) {
            return wScriptText == null || wScriptText.length() == 0 ? PacUrlDiscoveryService.defaultScript() : wScriptText;
        }
        if (ProxyConfiguration.PAC_URL_POWERSHELL.equals(modeName)) {
            return powerShellScriptText == null || powerShellScriptText.length() == 0 ? ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT : powerShellScriptText;
        }
        return otherScriptText == null ? "" : otherScriptText;
    }

    private void writeConfiguration() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(new AppConfiguration(
                current.getOllamaBaseUrl(),
                current.getKeepAlive(),
                buildProxyConfiguration(),
                buildCertificateTrustConfiguration(),
                current.getHuggingFaceToken(),
                current.getModelDownloadDirectory()));
    }

    private void resetDefault() {
        ProxyConfiguration cfg = ProxyConfiguration.defaults();
        CertificateTrustConfiguration trust = CertificateTrustConfiguration.defaults();
        manualPacUrlText = "";
        powerShellScriptText = ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT;
        wScriptText = PacUrlDiscoveryService.defaultScript();
        otherScriptText = "";
        activeScriptMode = cfg.getModeName();
        mode.setSelectedItem(cfg.getModeName());
        testUrl.setText(cfg.getTestUrl());
        updatingScriptText = true;
        try {
            pacUrlDiscoveryScript.setText(scriptTextFor(activeScriptMode));
        } finally {
            updatingScriptText = false;
        }
        manualHost.setText(empty(cfg.getManualProxyHost()));
        manualPort.setText(cfg.getManualProxyPort() > 0 ? String.valueOf(cfg.getManualProxyPort()) : "");
        trustJvmDefault.setSelected(trust.isUseJvmDefaultTrustStore());
        trustWindowsRoot.setSelected(trust.isUseWindowsRootStore());
        trustWindowsCaStores.setSelected(trust.isUseWindowsCaStores());
        writeConfiguration();
        append("Reset network settings to defaults.");
        appendModeHelp(cfg.getModeName());
    }

    private void resolveTestUrl() {
        final ProxyConfiguration cfg = buildProxyConfiguration();
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
                String diagnostic = cfg.describePacSource();
                if (diagnostic != null) {
                    append(diagnostic);
                }
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

    private void testHuggingFaceHttps() {
        writeConfiguration();
        append("Testing HuggingFace HTTPS via real client path ...");
        askAiService.testHuggingFaceConnection(new AskAiService.ActionListener() {
            public void onComplete(String message) {
                append(message);
                JOptionPane.showMessageDialog(ProxyPanel.this, message,
                        "HuggingFace HTTPS Test", JOptionPane.INFORMATION_MESSAGE);
            }

            public void onError(Exception ex) {
                String message = rootMessage(ex);
                append("ERROR: " + message);
                JOptionPane.showMessageDialog(ProxyPanel.this, message,
                        "HuggingFace HTTPS Test failed", JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    private ProxyConfiguration buildProxyConfiguration() {
        return new ProxyConfiguration(
                selectedMode(),
                nonBlank(testUrl.getText(), ProxyConfiguration.defaults().getTestUrl()),
                scriptTextFor(selectedMode()),
                "",
                empty(manualHost.getText()),
                parsePort(manualPort.getText()));
    }

    private CertificateTrustConfiguration buildCertificateTrustConfiguration() {
        return new CertificateTrustConfiguration(
                trustJvmDefault.isSelected(),
                trustWindowsRoot.isSelected(),
                trustWindowsCaStores.isSelected());
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
