package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.hf.HuggingFaceFile;
import com.aresstack.askai.java8.hf.HuggingFaceModel;
import com.aresstack.askai.java8.service.AskAiService;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class OllamaInstallPanel extends JPanel {

    private static final String PROFILE_AUTO = "Auto (recommended)";
    private static final String PROFILE_QWEN = "Qwen ChatML";
    private static final String PROFILE_DEFAULT = "Default";

    private final AppConfigurationRepository configurationRepository;
    private final AskAiService askAiService;
    private final JTextField searchField;
    private final JButton searchButton;
    private final DefaultListModel<HuggingFaceModel> resultsModel;
    private final JList<HuggingFaceModel> resultsList;
    private final DefaultListModel<HuggingFaceFile> filesModel;
    private final JList<HuggingFaceFile> filesList;
    private final JTextField repoField;
    private final JTextField revisionField;
    private final JTextField tokenField;
    private final JTextField installAsField;
    private final JTextField quantizationField;
    private final JComboBox<String> profileCombo;
    private final JProgressBar progressBar;
    private final JTextArea logArea;
    private File lastDownloadedFile;

    public OllamaInstallPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        this.searchField = new JTextField(28);
        this.searchButton = new JButton("Search Hugging Face");
        this.resultsModel = new DefaultListModel<HuggingFaceModel>();
        this.resultsList = new JList<HuggingFaceModel>(resultsModel);
        this.filesModel = new DefaultListModel<HuggingFaceFile>();
        this.filesList = new JList<HuggingFaceFile>(filesModel);
        this.repoField = new JTextField(30);
        this.revisionField = new JTextField("main", 10);
        this.tokenField = new JTextField(24);
        this.installAsField = new JTextField(24);
        this.quantizationField = new JTextField("Q4_K_M", 10);
        this.profileCombo = new JComboBox<String>(new String[]{PROFILE_AUTO, PROFILE_QWEN, PROFILE_DEFAULT});
        this.progressBar = new JProgressBar(0, 100);
        this.logArea = new JTextArea(12, 80);
        buildUserInterface();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        progressBar.setStringPainted(true);
        add(progressBar, BorderLayout.SOUTH);
        loadTokenFromConfiguration();
    }

    private JComponent buildTop() {
        JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        searchBar.add(new JLabel("Search"));
        searchBar.add(searchField);
        searchBar.add(searchButton);
        searchButton.addActionListener(event -> searchModels());
        searchField.addActionListener(event -> searchModels());

        resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsList.setVisibleRowCount(5);
        resultsList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                onResultSelected();
            }
        });
        filesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        filesList.setVisibleRowCount(5);

        JPanel listsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 0.5d;
        constraints.weighty = 1.0d;
        constraints.fill = GridBagConstraints.BOTH;
        JScrollPane resultsScroll = new JScrollPane(resultsList);
        resultsScroll.setBorder(BorderFactory.createTitledBorder("Hugging Face models"));
        listsPanel.add(resultsScroll, constraints);
        constraints.gridx = 1;
        JScrollPane filesScroll = new JScrollPane(filesList);
        filesScroll.setBorder(BorderFactory.createTitledBorder("GGUF files"));
        listsPanel.add(filesScroll, constraints);

        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.add(searchBar, BorderLayout.NORTH);
        top.add(listsPanel, BorderLayout.CENTER);
        top.add(buildForm(), BorderLayout.SOUTH);
        return top;
    }

    private JComponent buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Install"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(3, 4, 3, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;

        addRow(form, constraints, 0, "Repository", repoField);
        addRow(form, constraints, 1, "Revision / branch", revisionField);
        addRow(form, constraints, 2, "HF token (gated, optional)", tokenField);
        addRow(form, constraints, 3, "Install as", installAsField);
        addRow(form, constraints, 4, "Ollama profile", profileCombo);
        addRow(form, constraints, 5, "Quantization", quantizationField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton filesButton = new JButton("Load GGUF files");
        JButton downloadButton = new JButton("Download");
        JButton fullInstallButton = new JButton("Download and install");
        JButton importLastButton = new JButton("Install downloaded file");
        final JButton importMenuButton = new JButton("▾");
        importMenuButton.setToolTipText("Install another already-downloaded model");
        filesButton.addActionListener(event -> loadFiles());
        downloadButton.addActionListener(event -> downloadSelected(false));
        fullInstallButton.addActionListener(event -> downloadSelected(true));
        importLastButton.addActionListener(event -> installDownloadedFile());
        importMenuButton.addActionListener(event -> showDownloadedFilesMenu(importMenuButton));
        // A split button: primary installs the current download, the arrow lists all downloads.
        JPanel installSplit = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        installSplit.add(importLastButton);
        installSplit.add(importMenuButton);
        buttons.add(filesButton);
        buttons.add(downloadButton);
        buttons.add(fullInstallButton);
        buttons.add(installSplit);

        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.gridx = 0;
        buttonConstraints.gridy = 6;
        buttonConstraints.gridwidth = 2;
        buttonConstraints.anchor = GridBagConstraints.WEST;
        form.add(buttons, buttonConstraints);
        return form;
    }

    private JComponent buildCenter() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Log"));
        return scroll;
    }

    private void addRow(JPanel form, GridBagConstraints constraints, int row, String label, java.awt.Component field) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0.0d;
        form.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        form.add(field, constraints);
    }

    private void searchModels() {
        final String query = searchField.getText().trim();
        if (query.length() == 0) {
            append("Enter a search term, e.g. qwen2.5 coder 0.5b.");
            return;
        }
        saveTokenToConfiguration();
        searchButton.setEnabled(false);
        append("Searching Hugging Face for \"" + query + "\" ...");
        askAiService.searchHuggingFaceModels(query, new AskAiService.HuggingFaceModelListener() {
            public void onModels(final List<HuggingFaceModel> models) {
                onUi(new Runnable() {
                    public void run() {
                        searchButton.setEnabled(true);
                        resultsModel.clear();
                        filesModel.clear();
                        for (HuggingFaceModel model : models) {
                            resultsModel.addElement(model);
                        }
                        append("Found " + models.size() + " model(s). Select one to install.");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        searchButton.setEnabled(true);
                        append("Search failed: " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void onResultSelected() {
        HuggingFaceModel selected = resultsList.getSelectedValue();
        if (selected == null) {
            return;
        }
        repoField.setText(selected.getId());
        installAsField.setText(suggestInstallName(selected.getId()));
        profileCombo.setSelectedItem(PROFILE_AUTO);
        loadFiles();
    }

    private void loadFiles() {
        final String repoId = repoField.getText().trim();
        if (repoId.length() == 0) {
            append("Pick a model from the list or type a repository id.");
            return;
        }
        saveTokenToConfiguration();
        append("Loading GGUF files for " + repoId + " ...");
        askAiService.listHuggingFaceFiles(repoId, new AskAiService.HuggingFaceFileListener() {
            public void onFiles(final List<HuggingFaceFile> files) {
                onUi(new Runnable() {
                    public void run() {
                        filesModel.clear();
                        for (HuggingFaceFile file : files) {
                            filesModel.addElement(file);
                        }
                        append("Found " + files.size() + " GGUF file(s).");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        append("Could not load files: " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void downloadSelected(final boolean installAfterDownload) {
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

    /**
     * Shows a popup listing every already-downloaded GGUF file (not just the last one), so a model
     * downloaded earlier but not yet installed remotely can be installed too.
     */
    private void showDownloadedFilesMenu(JButton anchor) {
        List<File> files = findDownloadedGgufFiles();
        JPopupMenu menu = new JPopupMenu();
        if (files.isEmpty()) {
            JMenuItem empty = new JMenuItem("No downloaded GGUF files found");
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            for (int i = 0; i < files.size(); i++) {
                final File file = files.get(i);
                JMenuItem item = new JMenuItem(downloadedFileLabel(file));
                item.addActionListener(event -> chooseAndInstall(file));
                menu.add(item);
            }
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void chooseAndInstall(File file) {
        lastDownloadedFile = file;
        // Match the install name to the chosen file, since installing it under another model's name
        // would be wrong.
        installAsField.setText(suggestInstallNameForFile(file));
        append("Selected downloaded file: " + file.getAbsolutePath());
        installDownloadedFile();
    }

    /** @return all downloaded {@code .gguf} files under the model download directory, newest first. */
    private List<File> findDownloadedGgufFiles() {
        List<File> found = new ArrayList<File>();
        collectGgufFiles(configurationRepository.load().getModelDownloadDirectory(), found, 0);
        Collections.sort(found, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return found;
    }

    private void collectGgufFiles(File directory, List<File> out, int depth) {
        if (directory == null || !directory.isDirectory() || depth > 4) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            if (child.isDirectory()) {
                collectGgufFiles(child, out, depth + 1);
            } else if (child.isFile() && child.getName().toLowerCase().endsWith(".gguf")) {
                out.add(child);
            }
        }
    }

    private String downloadedFileLabel(File file) {
        File parent = file.getParentFile();
        String relative = (parent != null ? parent.getName() + "/" : "") + file.getName();
        long megabytes = file.length() / (1024L * 1024L);
        return relative + "  (" + megabytes + " MB)";
    }

    private String suggestInstallNameForFile(File file) {
        String name = file.getName();
        int dot = name.toLowerCase().lastIndexOf(".gguf");
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        String cleaned = name.toLowerCase().replaceAll("[^a-z0-9._-]", "-");
        return cleaned.length() == 0 ? "model" : cleaned;
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
                current.getHttpClientConfiguration(),
                current.getDefaultQuantization(),
                tokenField.getText(),
                current.getModelDownloadDirectory())
                .withSpeechToTextConfiguration(current.getSpeechToTextConfiguration()));
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
