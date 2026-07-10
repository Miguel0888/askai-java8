package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.config.HuggingFaceSearchSuggestion;
import com.aresstack.askai.java8.hf.GgufFile;
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
import javax.swing.JOptionPane;
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
    private final JComboBox<HuggingFaceSearchSuggestion> searchCombo;
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
    private final JButton cancelInstallButton;
    private final JLabel repoCapabilityLabel = new JLabel(" ");
    private final JTextArea logArea;
    private File lastDownloadedFile;
    private AskAiService.InstallTask installTask;

    public OllamaInstallPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        this.searchCombo = new JComboBox<HuggingFaceSearchSuggestion>();
        this.searchCombo.setEditable(true);
        this.searchCombo.setRenderer(new SearchSuggestionRenderer());
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
        this.cancelInstallButton = new JButton(new CancelIcon(11));
        buildCancelButton();
        this.logArea = new JTextArea(12, 80);
        buildUserInterface();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        progressBar.setStringPainted(true);
        JPanel progressRow = new JPanel(new BorderLayout(6, 0));
        progressRow.add(progressBar, BorderLayout.CENTER);
        progressRow.add(cancelInstallButton, BorderLayout.EAST);
        add(progressRow, BorderLayout.SOUTH);
        loadTokenFromConfiguration();
    }

    private JComponent buildTop() {
        JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        searchBar.add(new JLabel("Search"));
        searchCombo.setPreferredSize(new java.awt.Dimension(320, searchCombo.getPreferredSize().height));
        reloadSearchSuggestions();
        searchBar.add(searchCombo);
        searchBar.add(searchButton);
        JButton editSuggestionsButton = new JButton("Edit list...");
        editSuggestionsButton.setToolTipText("Edit the model suggestions shown in the dropdown");
        editSuggestionsButton.addActionListener(event -> editSearchSuggestions());
        searchBar.add(editSuggestionsButton);
        searchButton.addActionListener(event -> searchModels());
        // Enter in the editable combo editor triggers the search, matching the old text field.
        searchCombo.getEditor().addActionListener(event -> searchModels());

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

        GridBagConstraints capabilityConstraints = new GridBagConstraints();
        capabilityConstraints.gridx = 0;
        capabilityConstraints.gridy = 7;
        capabilityConstraints.gridwidth = 2;
        capabilityConstraints.anchor = GridBagConstraints.WEST;
        form.add(repoCapabilityLabel, capabilityConstraints);
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

    /** Fills the dropdown with the configured suggestions, keeping any text the user typed. */
    private void reloadSearchSuggestions() {
        Object typed = searchCombo.getEditor().getItem();
        searchCombo.removeAllItems();
        for (HuggingFaceSearchSuggestion suggestion
                : configurationRepository.load().getHuggingFaceSearchSuggestions()) {
            searchCombo.addItem(suggestion);
        }
        searchCombo.setSelectedItem(typed == null ? "" : typed);
    }

    /** Opens a small editor for the dropdown suggestions (one per line) and persists the list. */
    private void editSearchSuggestions() {
        AppConfiguration current = configurationRepository.load();
        JTextArea editor = new JTextArea(current.getHuggingFaceSearchSuggestionsRaw(), 14, 40);
        JPanel content = new JPanel(new BorderLayout(4, 4));
        content.add(new JLabel("<html>One suggestion per line: <b>&lt;search term&gt; | &lt;input&gt;,&lt;input&gt;</b>"
                + " &mdash; inputs: text, audio, vision.<br>"
                + "Tag audio/vision only when a GGUF repo for that search ships the model's encoder"
                + " (mmproj); otherwise the model is text-only when installed from HuggingFace.</html>"),
                BorderLayout.NORTH);
        content.add(new JScrollPane(editor), BorderLayout.CENTER);
        JButton restoreDefaults = new JButton("Restore defaults");
        restoreDefaults.addActionListener(event -> editor.setText(AppConfiguration.DEFAULT_HF_SEARCH_SUGGESTIONS));
        JPanel southRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        southRow.add(restoreDefaults);
        content.add(southRow, BorderLayout.SOUTH);
        int choice = JOptionPane.showConfirmDialog(this, content,
                "Search suggestions", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        configurationRepository.save(current.withHuggingFaceSearchSuggestions(editor.getText()));
        reloadSearchSuggestions();
        append("Search suggestions updated ("
                + configurationRepository.load().getHuggingFaceSearchSuggestions().size() + " entries).");
    }

    /**
     * Renders each suggestion with the term on the left and the fixed modality icon column
     * (text / audio / vision) right-aligned at the dropdown's right edge.
     */
    private static final class SearchSuggestionRenderer extends JPanel
            implements javax.swing.ListCellRenderer<Object> {

        private final JLabel termLabel = new JLabel();
        private final JLabel iconLabel = new JLabel();

        SearchSuggestionRenderer() {
            super(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            setOpaque(true);
            iconLabel.setToolTipText("Model modalities (text / audio / vision). Audio/vision also needs "
                    + "the model's encoder (mmproj); the installer fetches it from the repo when present.");
            add(termLabel, BorderLayout.CENTER);
            add(iconLabel, BorderLayout.EAST);
        }

        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean isSelected, boolean cellHasFocus) {
            if (value instanceof HuggingFaceSearchSuggestion) {
                HuggingFaceSearchSuggestion suggestion = (HuggingFaceSearchSuggestion) value;
                termLabel.setText(suggestion.getTerm());
                iconLabel.setIcon(ModalityIcons.forModalities(suggestion.getModalities()));
            } else {
                termLabel.setText(value == null ? "" : String.valueOf(value));
                iconLabel.setIcon(null);
            }
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            termLabel.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            termLabel.setFont(list.getFont());
            return this;
        }
    }

    private void searchModels() {
        final String query = String.valueOf(searchCombo.getEditor().getItem()).trim();
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
                        setRepoCapability(" ");
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
        setRepoCapability("Checking repository capabilities ...");
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
                        updateRepoCapability(repoId, files);
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        setRepoCapability(" ");
                        append("Could not load files: " + ex.getMessage());
                    }
                });
            }
        });
    }

    /**
     * Report the repository's real capability from the ground truth: whether it ships a *mmproj*
     * encoder. Text-only repos say so plainly; multimodal repos name the encoder and classify it as
     * audio or vision from the model/encoder name (falling back to "audio/vision" when unclear).
     */
    private void updateRepoCapability(String repoId, List<HuggingFaceFile> files) {
        HuggingFaceFile mmproj = null;
        for (int i = 0; i < files.size(); i++) {
            if (isMmprojName(files.get(i).getFileName())) {
                mmproj = files.get(i);
                break;
            }
        }
        if (mmproj == null) {
            setRepoCapability("<html>This repository is <b>text only</b> — no multimodal encoder "
                    + "(mmproj). Audio/vision needs a repo that ships one, or 'ollama pull'.</html>");
            return;
        }
        String kind = classifyEncoder(repoId + " " + mmproj.getFileName());
        setRepoCapability("<html>This repository is <b>multimodal (" + kind + ")</b> — encoder present: "
                + mmproj.getFileName() + ". You will be offered to install it with the model.</html>");
    }

    /** Guess whether an encoder is for audio or vision from the model/encoder name. */
    private static String classifyEncoder(String haystack) {
        String lower = haystack.toLowerCase();
        boolean audio = lower.contains("audio") || lower.contains("voxtral") || lower.contains("ultravox")
                || lower.contains("asr") || lower.contains("omni") || lower.contains("whisper")
                || lower.contains("qwen2-audio");
        boolean vision = lower.contains("vision") || lower.contains("-vl") || lower.contains("llava")
                || lower.contains("minicpm-v") || lower.contains("moondream") || lower.contains("gemma-3")
                || lower.contains("image");
        if (audio && !vision) {
            return "audio";
        }
        if (vision && !audio) {
            return "vision";
        }
        return "audio/vision";
    }

    private void setRepoCapability(String text) {
        repoCapabilityLabel.setText(text);
    }

    private void downloadSelected(final boolean installAfterDownload) {
        HuggingFaceFile selected = filesList.getSelectedValue();
        if (selected == null) {
            append("Select a GGUF file first.");
            return;
        }
        saveTokenToConfiguration();

        // Multimodal repos ship the audio/vision encoder as a separate *mmproj* GGUF. Offer to
        // fetch it along with the model so the install can be complete.
        HuggingFaceFile companion = null;
        if (!isMmprojName(selected.getFileName())) {
            HuggingFaceFile repoMmproj = findMmprojInFileList();
            if (repoMmproj != null) {
                int answer = JOptionPane.showConfirmDialog(this,
                        "This repository also contains a multimodal encoder:\n" + repoMmproj.getFileName()
                                + "\n\nDownload it too? (Required for audio/vision input.)",
                        "Download multimodal encoder (mmproj)?",
                        JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (answer == JOptionPane.YES_OPTION) {
                    companion = repoMmproj;
                }
            }
        }
        final HuggingFaceFile companionFile = companion;

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
                        if (companionFile != null) {
                            downloadCompanion(companionFile, installAfterDownload);
                        } else {
                            showProgress(100, "Files downloaded");
                            if (installAfterDownload) {
                                installDownloadedFile();
                            }
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

    /** Download the encoder after the model; keeps {@code lastDownloadedFile} on the model file. */
    private void downloadCompanion(HuggingFaceFile companion, final boolean installAfterDownload) {
        append("Downloading encoder " + companion.getFileName() + " ...");
        askAiService.downloadHuggingFaceFile(companion, new AskAiService.DownloadListener() {
            public void onProgress(final long completed, final long total) {
                onUi(new Runnable() {
                    public void run() {
                        if (total > 0L) {
                            int percent = (int) (completed * 100L / total);
                            showProgress(percent, "Downloading encoder " + percent + "%");
                        }
                    }
                });
            }

            public void onComplete(final File file) {
                onUi(new Runnable() {
                    public void run() {
                        append("Encoder downloaded: " + file.getAbsolutePath());
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
                        append("ERROR: encoder download failed: " + ex.getMessage()
                                + " — the model will work for text, but not for audio/vision.");
                        showProgress(0, "Encoder download failed");
                        if (installAfterDownload) {
                            installDownloadedFile();
                        }
                    }
                });
            }
        });
    }

    /** @return the first *mmproj* GGUF in the currently listed repository files, or null. */
    private HuggingFaceFile findMmprojInFileList() {
        for (int i = 0; i < filesModel.getSize(); i++) {
            HuggingFaceFile file = filesModel.getElementAt(i);
            if (isMmprojName(file.getFileName())) {
                return file;
            }
        }
        return null;
    }

    private static boolean isMmprojName(String fileName) {
        return fileName != null && fileName.toLowerCase().contains("mmproj");
    }

    /** @return a *mmproj* GGUF lying next to the model file, or null. */
    private File findLocalMmproj(File modelFile) {
        if (isMmprojName(modelFile.getName())) {
            return null;
        }
        File parent = modelFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return null;
        }
        File[] siblings = parent.listFiles();
        if (siblings == null) {
            return null;
        }
        for (int i = 0; i < siblings.length; i++) {
            String name = siblings[i].getName().toLowerCase();
            if (siblings[i].isFile() && name.contains("mmproj") && name.endsWith(".gguf")) {
                return siblings[i];
            }
        }
        return null;
    }

    /**
     * Shows a popup listing every already-downloaded GGUF file (not just the last one), so a model
     * downloaded earlier but not yet installed remotely can be installed too. Broken/incomplete
     * files are flagged; each row has a right-aligned delete button, and right-click opens a
     * context menu for deleting the download including any leftover partial data.
     */
    private void showDownloadedFilesMenu(final JButton anchor) {
        List<File> files = findDownloadedGgufFiles();
        final JPopupMenu menu = new JPopupMenu();
        if (files.isEmpty()) {
            JMenuItem empty = new JMenuItem("No downloaded GGUF files found");
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            for (int i = 0; i < files.size(); i++) {
                menu.add(buildDownloadRow(menu, anchor, files.get(i)));
            }
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    /** One popup row: install on click, a right-aligned ✕ button, and a right-click context menu. */
    private JComponent buildDownloadRow(final JPopupMenu menu, final JButton anchor, final File file) {
        boolean valid = isValidGguf(file);

        final JButton installButton = new JButton(
                downloadedFileLabel(file) + (valid ? "" : "   [invalid/incomplete]"));
        installButton.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        installButton.setBorderPainted(false);
        installButton.setContentAreaFilled(false);
        installButton.setFocusPainted(false);
        installButton.setToolTipText(valid
                ? "Install this downloaded model"
                : "This file failed GGUF validation (truncated or corrupt) — delete it and re-download");
        if (!valid) {
            installButton.setForeground(new java.awt.Color(0xB0, 0x2E, 0x2E));
        }
        installButton.addActionListener(event -> {
            menu.setVisible(false);
            chooseAndInstall(file);
        });

        JButton deleteButton = new JButton("✕");
        deleteButton.setMargin(new Insets(0, 6, 0, 6));
        deleteButton.setFocusPainted(false);
        deleteButton.setToolTipText("Delete this download (including partial data)");
        deleteButton.addActionListener(event -> deleteDownloadedFile(menu, anchor, file));

        final JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem deleteItem = new JMenuItem("Delete download (incl. data)");
        deleteItem.addActionListener(event -> deleteDownloadedFile(menu, anchor, file));
        contextMenu.add(deleteItem);
        installButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                maybeShowContext(event);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent event) {
                maybeShowContext(event);
            }

            private void maybeShowContext(java.awt.event.MouseEvent event) {
                if (event.isPopupTrigger()) {
                    contextMenu.show(event.getComponent(), event.getX(), event.getY());
                }
            }
        });

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(installButton, BorderLayout.CENTER);
        row.add(deleteButton, BorderLayout.EAST);
        return row;
    }

    /** @return whether the file passes the cheap GGUF header/tensor-bounds validation. */
    private boolean isValidGguf(File file) {
        try {
            GgufFile.validate(file);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Deletes a downloaded model file after confirmation, including its {@code .part} leftover and
     * the model directory when that becomes empty, then reopens the refreshed popup.
     */
    private void deleteDownloadedFile(JPopupMenu menu, JButton anchor, File file) {
        menu.setVisible(false);
        long megabytes = file.length() / (1024L * 1024L);
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete " + file.getName() + " (" + megabytes + " MB)?\n"
                        + "This also removes leftover partial download data (.part).",
                "Delete download", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            showDownloadedFilesMenu(anchor);
            return;
        }
        boolean deleted = !file.isFile() || file.delete();
        File partFile = new File(file.getParentFile(), file.getName() + ".part");
        if (partFile.isFile() && partFile.delete()) {
            append("Deleted partial data: " + partFile.getName());
        }
        File parent = file.getParentFile();
        if (parent != null && parent.isDirectory() && parent.delete()) {
            append("Removed empty model directory: " + parent.getName());
        }
        if (deleted) {
            append("Deleted download: " + file.getAbsolutePath());
            if (file.equals(lastDownloadedFile)) {
                lastDownloadedFile = null;
            }
        } else {
            append("ERROR: Could not delete " + file.getAbsolutePath()
                    + " (file may be in use by another process).");
        }
        showDownloadedFilesMenu(anchor);
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
        // Multimodal models need their separate *mmproj* encoder GGUF installed alongside the
        // language model — otherwise Ollama rejects audio/vision input. Offer any encoder found
        // next to the model file.
        final List<File> companions = new ArrayList<File>();
        File mmproj = findLocalMmproj(lastDownloadedFile);
        if (mmproj != null) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Found a multimodal encoder next to the model:\n" + mmproj.getName()
                            + "\n\nInstall it together with the model? (Required for audio/vision input.)",
                    "Install multimodal encoder (mmproj)?",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                companions.add(mmproj);
                append("Including encoder: " + mmproj.getName());
            }
        }
        append("Installing " + lastDownloadedFile.getAbsolutePath() + " as " + modelName + ".");
        showProgress(0, "Installing");
        setInstallInProgress(true);
        installTask = askAiService.installGgufFileWithCompanions(modelName, lastDownloadedFile, companions,
                new AskAiService.InstallListener() {
            public void onProgress(final String phase, final long completed, final long total) {
                onUi(new Runnable() {
                    public void run() {
                        updateInstallProgress(phase, completed, total);
                    }
                });
            }

            public void onComplete(final String message) {
                onUi(new Runnable() {
                    public void run() {
                        setInstallInProgress(false);
                        append(message);
                        progressBar.setIndeterminate(false);
                        showProgress(100, "Installed");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        setInstallInProgress(false);
                        progressBar.setIndeterminate(false);
                        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                        boolean cancelled = ex instanceof java.io.InterruptedIOException
                                || message.toLowerCase().contains("cancel");
                        append((cancelled ? "Install cancelled." : "ERROR: " + message));
                        showProgress(0, cancelled ? "Install cancelled" : "Install failed");
                    }
                });
            }
        });
    }

    /** Cancel a running install; the service aborts the upload/create. */
    private void cancelInstall() {
        AskAiService.InstallTask task = installTask;
        if (task != null) {
            append("Cancelling install ...");
            task.cancel();
        }
    }

    private void setInstallInProgress(boolean inProgress) {
        cancelInstallButton.setEnabled(inProgress);
        if (!inProgress) {
            installTask = null;
        }
    }

    /** Render one install progress update: a percentage bar for byte phases, indeterminate otherwise. */
    private void updateInstallProgress(String phase, long completed, long total) {
        if (total > 0) {
            int percent = (int) Math.max(0, Math.min(100, completed * 100L / total));
            progressBar.setIndeterminate(false);
            progressBar.setValue(percent);
            progressBar.setString(phase + " " + percent + "% (" + humanBytes(completed)
                    + " / " + humanBytes(total) + ")");
        } else {
            progressBar.setIndeterminate(true);
            progressBar.setString(phase);
        }
    }

    private void buildCancelButton() {
        cancelInstallButton.setToolTipText("Cancel installation");
        cancelInstallButton.setFocusPainted(false);
        cancelInstallButton.setMargin(new Insets(0, 0, 0, 0));
        int size = progressBar.getPreferredSize().height;
        cancelInstallButton.setPreferredSize(new java.awt.Dimension(size, size));
        cancelInstallButton.setEnabled(false);
        cancelInstallButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                cancelInstall();
            }
        });
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0d;
        if (kb < 1024.0d) {
            return String.format("%.0f KB", kb);
        }
        double mb = kb / 1024.0d;
        if (mb < 1024.0d) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0d);
    }

    /** A small square close/cancel icon (an X) painted with Java2D — no image asset needed. */
    private static final class CancelIcon implements javax.swing.Icon {
        private final int size;

        CancelIcon(int size) {
            this.size = size;
        }

        public int getIconWidth() {
            return size;
        }

        public int getIconHeight() {
            return size;
        }

        public void paintIcon(java.awt.Component component, java.awt.Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(component.isEnabled() ? new java.awt.Color(0xC0, 0x2E, 0x2E)
                        : new java.awt.Color(0x9E, 0x9E, 0x9E));
                g.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                int pad = 2;
                g.drawLine(x + pad, y + pad, x + size - pad, y + size - pad);
                g.drawLine(x + size - pad, y + pad, x + pad, y + size - pad);
            } finally {
                g.dispose();
            }
        }
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
                .withSpeechToTextConfiguration(current.getSpeechToTextConfiguration())
                .withHuggingFaceSearchSuggestions(current.getHuggingFaceSearchSuggestionsRaw()));
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
