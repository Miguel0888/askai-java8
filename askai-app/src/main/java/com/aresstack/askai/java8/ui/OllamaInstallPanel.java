package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.hf.HuggingFaceFile;
import com.aresstack.askai.java8.hf.HuggingFaceModel;
import com.aresstack.askai.java8.service.AskAiService;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import java.util.List;

public final class OllamaInstallPanel extends JPanel {

    private final AppConfigurationRepository configurationRepository;
    private final AskAiService askAiService;
    private final JTextField queryField;
    private final JTextField repositoryField;
    private final JTextField revisionField;
    private final JTextField tokenField;
    private final JTextField installAsField;
    private final JComboBox<String> profileBox;
    private final JComboBox<String> quantizationBox;
    private final JCheckBox installAfterDownloadBox;
    private final DefaultListModel<HuggingFaceModel> modelResults;
    private final JList<HuggingFaceModel> modelResultsList;
    private final DefaultListModel<HuggingFaceFile> fileResults;
    private final JList<HuggingFaceFile> filesList;
    private final JProgressBar progressBar;
    private final JTextArea logArea;
    private File lastDownloadedFile;

    public OllamaInstallPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        this.queryField = new JTextField("qwen gguf", 28);
        this.repositoryField = new JTextField(32);
        this.revisionField = new JTextField("main", 10);
        this.tokenField = new JTextField(28);
        this.installAsField = new JTextField(24);
        this.profileBox = new JComboBox<String>(new String[]{"Generic GGUF", "Qwen Instruct", "Llama Instruct"});
        this.quantizationBox = new JComboBox<String>(new String[]{"auto", "q4_K_M", "q4_0", "q8_0"});
        this.installAfterDownloadBox = new JCheckBox("Install into remote Ollama after download", true);
        this.modelResults = new DefaultListModel<HuggingFaceModel>();
        this.modelResultsList = new JList<HuggingFaceModel>(modelResults);
        this.fileResults = new DefaultListModel<HuggingFaceFile>();
        this.filesList = new JList<HuggingFaceFile>(fileResults);
        this.progressBar = new JProgressBar(0, 100);
        this.logArea = new JTextArea(10, 70);
        buildUserInterface();
        loadTokenFromConfiguration();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(buildSearchPanel(), BorderLayout.NORTH);
        top.add(buildConfigPanel(), BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        JPanel lists = new JPanel(new GridLayout(1, 2, 8, 8));
        modelResultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        filesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        modelResultsList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                HuggingFaceModel selected = modelResultsList.getSelectedValue();
                if (selected != null) {
                    repositoryField.setText(selected.getId());
                    installAsField.setText(suggestInstallName(selected.getId()));
                    listFiles(selected.getId());
                }
            }
        });
        lists.add(new JScrollPane(modelResultsList));
        lists.add(new JScrollPane(filesList));
        add(lists, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        progressBar.setStringPainted(true);
        bottom.add(progressBar, BorderLayout.NORTH);
        logArea.setEditable(false);
        bottom.add(new JScrollPane(logArea), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    private JPanel buildSearchPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Hugging Face search"));
        panel.add(new JLabel("Search"));
        panel.add(queryField);
        JButton searchButton = new JButton("Search Hugging Face");
        searchButton.addActionListener(event -> searchModels());
        panel.add(searchButton);
        return panel;
    }

    private JPanel buildConfigPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Download and install"));
        panel.add(new JLabel("Repository"));
        panel.add(repositoryField);
        panel.add(new JLabel("Revision"));
        panel.add(revisionField);
        panel.add(new JLabel("HF token"));
        panel.add(tokenField);
        panel.add(new JLabel("Install as"));
        panel.add(installAsField);
        panel.add(new JLabel("Ollama profile"));
        panel.add(profileBox);
        panel.add(new JLabel("Quantization"));
        panel.add(quantizationBox);
        panel.add(new JLabel("Install"));
        panel.add(installAfterDownloadBox);
        JButton downloadButton = new JButton("Download selected GGUF");
        downloadButton.addActionListener(event -> downloadSelectedFile());
        panel.add(downloadButton);
        JButton installButton = new JButton("Install last downloaded file");
        installButton.addActionListener(event -> installDownloadedFile());
        panel.add(installButton);
        return panel;
    }

    private void searchModels() {
        saveTokenToConfiguration();
        String query = queryField.getText().trim();
        if (query.length() == 0) {
            append("Enter a Hugging Face search query.");
            return;
        }
        append("Searching Hugging Face for: " + query);
        askAiService.searchHuggingFaceModels(query, new AskAiService.HuggingFaceModelListener() {
            public void onModels(final List<HuggingFaceModel> models) {
                onUi(new Runnable() {
                    public void run() {
                        modelResults.clear();
                        for (int i = 0; i < models.size(); i++) {
                            modelResults.addElement(models.get(i));
                        }
                        append("Found " + models.size() + " model repositories.");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        append("ERROR: " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void listFiles(final String modelId) {
        append("Listing GGUF files for " + modelId + " ...");
        askAiService.listHuggingFaceFiles(modelId, new AskAiService.HuggingFaceFileListener() {
            public void onFiles(final List<HuggingFaceFile> files) {
                onUi(new Runnable() {
                    public void run() {
                        fileResults.clear();
                        for (int i = 0; i < files.size(); i++) {
                            fileResults.addElement(files.get(i));
                        }
                        append("Found " + files.size() + " GGUF files.");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        append("ERROR: " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void downloadSelectedFile() {
        final boolean installAfterDownload = installAfterDownloadBox.isSelected();
        HuggingFaceFile selected = filesList.getSelectedValue();
        if (selected == null) {
            append("Select a GGUF file first.");
            return;
        }
        saveTokenToConfiguration();
        append("Downloading " + selected.getFileName() + " ...");
        showProgress(0, "Downloading");
        askAiService.downloadHuggingFaceFile(selected, new AskAiService.DownloadListener() {
            public void onProgress(final long completed, final long total) {
                onUi(new Runnable() {
                    public void run() {
                        if (total > 0L) {
                            int percent = (int) (completed * 100L / total);
                            showProgress(percent, "Downloading " + percent + "%");
                        } else {
                            progressBar.setString("Downloading " + completed + " bytes");
                        }
                    }
                });
            }

            public void onComplete(final File file) {
                onUi(new Runnable() {
                    public void run() {
                        lastDownloadedFile = file;
                        append("Download complete: " + file.getAbsolutePath());
                        showProgress(100, "Files downloaded");
                        if (installAfterDownload) {
                            installDownloadedFile();
                        }
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        append("ERROR: " + ex.getMessage());
                        showProgress(0, "Download failed");
                    }
                });
            }
        });
    }

    private void installDownloadedFile() {
        final String modelName = installAsField.getText().trim();
        if (modelName.length() == 0) {
            append("ERROR: 'Install as' is empty.");
            return;
        }
        if (lastDownloadedFile == null || !lastDownloadedFile.isFile()) {
            append("ERROR: No downloaded GGUF file available.");
            return;
        }
        append("Installing " + lastDownloadedFile.getAbsolutePath() + " as " + modelName + ".");
        showProgress(0, "Installing");
        askAiService.installGgufFile(modelName, lastDownloadedFile, new AskAiService.ActionListener() {
            public void onComplete(final String message) {
                onUi(new Runnable() {
                    public void run() {
                        append(message);
                        showProgress(100, "Installed");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        append("ERROR: " + ex.getMessage());
                        showProgress(0, "Install failed");
                    }
                });
            }
        });
    }

    private void loadTokenFromConfiguration() {
        AppConfiguration configuration = configurationRepository.load();
        tokenField.setText(configuration.getHuggingFaceToken());
    }

    private void saveTokenToConfiguration() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(new AppConfiguration(
                current.getOllamaBaseUrl(),
                current.getKeepAlive(),
                current.getProxyConfiguration(),
                current.getCertificateTrustConfiguration(),
                tokenField.getText(),
                current.getModelDownloadDirectory()));
    }

    private String suggestInstallName(String repoId) {
        String value = repoId;
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) {
            value = value.substring(slash + 1);
        }
        return value.toLowerCase().replace("_", "-").replace(" ", "-");
    }

    private void append(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void showProgress(int percent, String text) {
        progressBar.setValue(Math.max(0, Math.min(100, percent)));
        progressBar.setString(text);
    }

    private static void onUi(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }
}
