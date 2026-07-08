package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.hf.HuggingFaceFile;
import com.aresstack.askai.java8.hf.HuggingFaceModel;
import com.aresstack.askai.java8.net.ProxyConfiguration;
import com.aresstack.askai.java8.service.AskAiService;
import io.github.ollama4j.models.PullProgress;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.io.File;
import java.util.List;

public final class ModelManagerDialog {

    private final AppConfigurationRepository configurationRepository;
    private final AskAiService askAiService;
    private final JDialog dialog;
    private final JCheckBox proxyEnabledCheckBox;
    private final JTextField proxyHostField;
    private final JTextField proxyPortField;
    private final JTextField hfTokenField;
    private final JTextField downloadDirectoryField;
    private final JTextField remotePullField;
    private final JButton remotePullButton;
    private final JTextField searchField;
    private final JButton searchButton;
    private final JButton filesButton;
    private final JButton downloadButton;
    private final JTextField localModelNameField;
    private final JButton installFileButton;
    private final DefaultListModel<HuggingFaceModel> modelListModel;
    private final DefaultListModel<HuggingFaceFile> fileListModel;
    private final JList<HuggingFaceModel> modelList;
    private final JList<HuggingFaceFile> fileList;
    private final JLabel statusLabel;
    private File lastDownloadedFile;

    public ModelManagerDialog(Frame owner, AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        this.dialog = new JDialog(owner, "Models and downloads", true);
        this.proxyEnabledCheckBox = new JCheckBox("Use proxy for HuggingFace");
        this.proxyHostField = new JTextField(18);
        this.proxyPortField = new JTextField(6);
        this.hfTokenField = new JTextField(22);
        this.downloadDirectoryField = new JTextField(34);
        this.remotePullField = new JTextField(28);
        this.remotePullButton = new JButton("Install via remote Ollama pull");
        this.searchField = new JTextField(28);
        this.searchButton = new JButton("Search HuggingFace");
        this.filesButton = new JButton("Show GGUF files");
        this.downloadButton = new JButton("Download selected GGUF");
        this.localModelNameField = new JTextField(18);
        this.installFileButton = new JButton("Install downloaded GGUF");
        this.modelListModel = new DefaultListModel<HuggingFaceModel>();
        this.fileListModel = new DefaultListModel<HuggingFaceFile>();
        this.modelList = new JList<HuggingFaceModel>(modelListModel);
        this.fileList = new JList<HuggingFaceFile>(fileListModel);
        this.statusLabel = new JLabel("Ready.");
    }

    public void showDialog() {
        loadConfiguration();
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(createSettingsPanel(), BorderLayout.NORTH);
        dialog.add(createCenterPanel(), BorderLayout.CENTER);
        dialog.add(createBottomPanel(), BorderLayout.SOUTH);
        dialog.setSize(1000, 700);
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
    }

    private JPanel createSettingsPanel() {
        JPanel panel = new JPanel(new GridLayout(3, 1, 4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));

        JPanel proxyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        proxyPanel.add(proxyEnabledCheckBox);
        proxyPanel.add(new JLabel("Host:"));
        proxyPanel.add(proxyHostField);
        proxyPanel.add(new JLabel("Port:"));
        proxyPanel.add(proxyPortField);
        proxyPanel.add(new JLabel("HF token:"));
        proxyPanel.add(hfTokenField);

        JPanel directoryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        directoryPanel.add(new JLabel("Download dir:"));
        directoryPanel.add(downloadDirectoryField);
        JButton saveButton = new JButton("Save settings");
        saveButton.addActionListener(event -> saveConfiguration());
        directoryPanel.add(saveButton);

        JPanel pullPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pullPanel.setBorder(BorderFactory.createTitledBorder("Remote Ollama registry install"));
        pullPanel.add(new JLabel("Model:"));
        pullPanel.add(remotePullField);
        pullPanel.add(remotePullButton);
        remotePullButton.addActionListener(event -> pullRemoteModel());

        panel.add(proxyPanel);
        panel.add(directoryPanel);
        panel.add(pullPanel);
        return panel;
    }

    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actionPanel.setBorder(BorderFactory.createTitledBorder("HuggingFace GGUF search"));
        actionPanel.add(new JLabel("Search:"));
        actionPanel.add(searchField);
        actionPanel.add(searchButton);
        actionPanel.add(filesButton);
        actionPanel.add(downloadButton);
        actionPanel.add(new JLabel("Install name:"));
        actionPanel.add(localModelNameField);
        actionPanel.add(installFileButton);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(modelList), new JScrollPane(fileList));
        splitPane.setResizeWeight(0.5d);
        panel.add(actionPanel, BorderLayout.NORTH);
        panel.add(splitPane, BorderLayout.CENTER);

        searchButton.addActionListener(event -> searchModels());
        filesButton.addActionListener(event -> loadFiles());
        downloadButton.addActionListener(event -> downloadFile());
        installFileButton.addActionListener(event -> installDownloadedFile());
        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));
        panel.add(statusLabel, BorderLayout.CENTER);
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(event -> dialog.dispose());
        panel.add(closeButton, BorderLayout.EAST);
        return panel;
    }

    private void loadConfiguration() {
        AppConfiguration configuration = configurationRepository.load();
        proxyEnabledCheckBox.setSelected(configuration.getProxyConfiguration().isEnabled());
        proxyHostField.setText(configuration.getProxyConfiguration().getHost());
        proxyPortField.setText(String.valueOf(configuration.getProxyConfiguration().getPort()));
        hfTokenField.setText(configuration.getHuggingFaceToken());
        downloadDirectoryField.setText(configuration.getModelDownloadDirectory().getAbsolutePath());
    }

    private void saveConfiguration() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(new AppConfiguration(
                current.getOllamaBaseUrl(),
                current.getKeepAlive(),
                new ProxyConfiguration(proxyEnabledCheckBox.isSelected(), proxyHostField.getText(), parseInt(proxyPortField.getText())),
                current.getCertificateTrustConfiguration(),
                hfTokenField.getText(),
                new File(downloadDirectoryField.getText())));
        setStatus("Settings saved.");
    }

    private void pullRemoteModel() {
        saveConfiguration();
        String modelName = remotePullField.getText().trim();
        if (modelName.length() == 0) {
            warn("Enter an Ollama model name, for example qwen2.5-coder:0.5b.");
            return;
        }
        setBusy(true, "Installing " + modelName + " on remote Ollama...");
        askAiService.pullOllamaModel(modelName, new AskAiService.PullListener() {
            public void onProgress(final PullProgress progress) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        if (progress.hasTotal()) {
                            setStatus(progress.getStatus() + " " + progress.getPercent() + "%");
                        } else {
                            setStatus(progress.getStatus());
                        }
                    }
                });
            }

            public void onComplete(final String message) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        setBusy(false, message);
                    }
                });
            }

            public void onError(final Exception ex) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        setBusy(false, "Remote install failed.");
                        error(ex);
                    }
                });
            }
        });
    }

    private void searchModels() {
        saveConfiguration();
        String query = searchField.getText().trim();
        if (query.length() == 0) {
            warn("Enter a HuggingFace search query.");
            return;
        }
        setBusy(true, "Searching HuggingFace...");
        askAiService.searchHuggingFaceModels(query, new AskAiService.HuggingFaceModelListener() {
            public void onModels(final List<HuggingFaceModel> models) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        modelListModel.clear();
                        fileListModel.clear();
                        for (HuggingFaceModel model : models) {
                            modelListModel.addElement(model);
                        }
                        setBusy(false, models.size() + " model(s) found.");
                    }
                });
            }

            public void onError(final Exception ex) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        setBusy(false, "HuggingFace search failed.");
                        error(ex);
                    }
                });
            }
        });
    }

    private void loadFiles() {
        HuggingFaceModel model = modelList.getSelectedValue();
        if (model == null) {
            warn("Select a HuggingFace model first.");
            return;
        }
        setBusy(true, "Loading GGUF files...");
        askAiService.listHuggingFaceFiles(model.getId(), new AskAiService.HuggingFaceFileListener() {
            public void onFiles(final List<HuggingFaceFile> files) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        fileListModel.clear();
                        for (HuggingFaceFile file : files) {
                            fileListModel.addElement(file);
                        }
                        setBusy(false, files.size() + " GGUF file(s) found.");
                    }
                });
            }

            public void onError(final Exception ex) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        setBusy(false, "File listing failed.");
                        error(ex);
                    }
                });
            }
        });
    }

    private void downloadFile() {
        saveConfiguration();
        HuggingFaceFile file = fileList.getSelectedValue();
        if (file == null) {
            warn("Select a GGUF file first.");
            return;
        }
        setBusy(true, "Downloading " + file.getFileName() + "...");
        askAiService.downloadHuggingFaceFile(file, new AskAiService.DownloadListener() {
            public void onProgress(final long completed, final long total) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        if (total > 0L) {
                            setStatus("Downloading " + (completed * 100L / total) + "%");
                        } else {
                            setStatus("Downloading " + completed + " bytes...");
                        }
                    }
                });
            }

            public void onComplete(final File file) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        lastDownloadedFile = file;
                        setBusy(false, "Downloaded: " + file.getAbsolutePath());
                    }
                });
            }

            public void onError(final Exception ex) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        setBusy(false, "Download failed.");
                        error(ex);
                    }
                });
            }
        });
    }

    private void installDownloadedFile() {
        if (lastDownloadedFile == null || !lastDownloadedFile.isFile()) {
            warn("Download a GGUF file first.");
            return;
        }
        String modelName = localModelNameField.getText().trim();
        if (modelName.length() == 0) {
            warn("Enter a target model name.");
            return;
        }
        askAiService.installGgufFile(modelName, lastDownloadedFile, new AskAiService.ActionListener() {
            public void onComplete(String message) {
                setStatus(message);
            }

            public void onError(Exception ex) {
                error(ex);
            }
        });
    }

    private void setBusy(boolean busy, String status) {
        remotePullButton.setEnabled(!busy);
        searchButton.setEnabled(!busy);
        filesButton.setEnabled(!busy);
        downloadButton.setEnabled(!busy);
        installFileButton.setEnabled(!busy);
        setStatus(status);
    }

    private void setStatus(String status) {
        statusLabel.setText(status == null ? "" : status);
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(dialog, message, "AskAI Java 8", JOptionPane.WARNING_MESSAGE);
    }

    private void error(Exception ex) {
        JOptionPane.showMessageDialog(dialog, ex.getMessage(), "AskAI Java 8", JOptionPane.ERROR_MESSAGE);
    }
}
