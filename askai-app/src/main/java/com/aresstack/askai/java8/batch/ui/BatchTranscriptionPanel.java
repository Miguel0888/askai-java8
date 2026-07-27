package com.aresstack.askai.java8.batch.ui;

import com.aresstack.askai.java8.audio.format.SupportedAudioFormats;
import com.aresstack.askai.java8.batch.service.BatchProfileCatalogLoadedEvent;
import com.aresstack.askai.java8.batch.service.BatchSelectionCatalogLoadedEvent;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionEvent;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionEventPublisher;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionRequest;
import com.aresstack.askai.java8.ui.MarkdownPreviewTabs;
import com.aresstack.askai.java8.ui.RefreshIcon;
import com.aresstack.askai.java8.ui.ToggleSelectionList;
import com.aresstack.audio.profile.AudioProcessingProfile;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Swing view for selecting files, models and profiles and observing batch progress. */
public final class BatchTranscriptionPanel extends JPanel {

    private final BatchTranscriptionController controller;
    private final BatchSelectionRefresher refresher;
    private final DefaultListModel<File> audioFiles = new DefaultListModel<File>();
    private final JList<File> fileList = new ToggleSelectionList<File>(audioFiles);
    private final DefaultListModel<String> models = new DefaultListModel<String>();
    private final JList<String> modelList = new ToggleSelectionList<String>(models);
    private final DefaultListModel<AudioProcessingProfile> profiles = new DefaultListModel<AudioProcessingProfile>();
    private final JList<AudioProcessingProfile> profileList = new ToggleSelectionList<AudioProcessingProfile>(profiles);
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel("Ready");
    private final JTextArea log = new JTextArea();
    private final JButton startButton = new JButton("Start batch");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton refreshButton = new JButton();
    private MarkdownPreviewTabs bottomTabs;
    private final BatchTranscriptionEventPublisher.Subscription subscription;

    // Refresh state — all touched only on the EDT.
    private boolean refreshing;
    private int pendingRefreshLoads;
    private String modelRefreshError;
    private String profileRefreshError;

    public BatchTranscriptionPanel(BatchTranscriptionController controller,
                                   List<String> availableModels,
                                   List<AudioProcessingProfile> availableProfiles,
                                   BatchSelectionRefresher refresher) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        this.refresher = refresher;
        for (String model : availableModels) models.addElement(model);
        for (AudioProcessingProfile profile : availableProfiles) profiles.addElement(profile);
        this.subscription = controller.observe(new Consumer<BatchTranscriptionEvent>() {
            public void accept(final BatchTranscriptionEvent event) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() { apply(event); }
                });
            }
        });
        buildUi();
    }

    private void buildUi() {
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        // Selection mode is set by ToggleSelectionList; single clicks toggle rows without Ctrl/Shift.
        fileList.setToolTipText("Click a file to preview a matching .md; double-click to open it in a pinned tab.");
        fileList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) { onFileListClick(event); }
        });
        JPanel selections = new JPanel(new GridLayout(1, 3, 8, 8));
        selections.add(section("Audio files", new JScrollPane(fileList)));
        selections.add(section("Audio AI models", new JScrollPane(modelList)));
        selections.add(section("Audio profiles", new JScrollPane(profileList)));

        JButton addFiles = new JButton("Add audio files...");
        addFiles.addActionListener(event -> chooseAudioFiles());
        startButton.addActionListener(event -> startBatch());
        cancelButton.addActionListener(event -> controller.cancel());
        cancelButton.setEnabled(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actions.add(addFiles);
        actions.add(startButton);
        actions.add(cancelButton);

        // Refresh control, top-right, matching the Chat panel's refresh button.
        int refreshSize = startButton.getPreferredSize().height;
        refreshButton.setIcon(new RefreshIcon(refreshSize - 6));
        refreshButton.setToolTipText("Refresh models and profiles");
        refreshButton.setFocusPainted(false);
        refreshButton.setMargin(new Insets(0, 0, 0, 0));
        refreshButton.setPreferredSize(new Dimension(refreshSize, refreshSize));
        refreshButton.addActionListener(event -> refresh());
        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightControls.add(refreshButton);

        JPanel header = new JPanel(new BorderLayout());
        header.add(actions, BorderLayout.WEST);
        header.add(rightControls, BorderLayout.EAST);

        log.setEditable(false);
        bottomTabs = new MarkdownPreviewTabs("Log", new JScrollPane(log), MarkdownPreviewTabs.markdownRenderer());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, selections, bottomTabs);
        split.setResizeWeight(0.55d);
        add(header, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.add(status, BorderLayout.WEST);
        footer.add(progress, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    private JPanel section(String title, java.awt.Component component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Replace the offered audio models. Only audio-capable model names should be passed in. Must be
     * invoked on the EDT (the batch catalog service publishes off the EDT, so callers forward via
     * {@link SwingUtilities#invokeLater}). Retains any still-available selection by model name.
     */
    public void setAvailableModels(List<String> availableModels) {
        List<String> previouslySelected = modelList.getSelectedValuesList();
        models.clear();
        for (String model : availableModels) models.addElement(model);
        List<Integer> indices = new ArrayList<Integer>();
        for (int i = 0; i < models.size(); i++) {
            if (previouslySelected.contains(models.get(i))) indices.add(i);
        }
        modelList.setSelectedIndices(toIntArray(indices));
    }

    /**
     * Replace the offered audio profiles. Must be invoked on the EDT. Retains any still-available
     * selection by the stable profile <b>id</b> (not the display name), so a renamed profile stays
     * selected and a deleted profile drops out; new profiles are made available but not auto-selected.
     */
    public void setAvailableProfiles(List<AudioProcessingProfile> availableProfiles) {
        Set<String> previouslySelectedIds = new HashSet<String>();
        for (AudioProcessingProfile profile : profileList.getSelectedValuesList()) {
            previouslySelectedIds.add(profile.getId());
        }
        profiles.clear();
        for (AudioProcessingProfile profile : availableProfiles) profiles.addElement(profile);
        List<Integer> indices = new ArrayList<Integer>();
        for (int i = 0; i < profiles.size(); i++) {
            if (previouslySelectedIds.contains(profiles.get(i).getId())) indices.add(i);
        }
        profileList.setSelectedIndices(toIntArray(indices));
    }

    /**
     * Reload the audio models and profiles from their live sources so background changes (installed or
     * removed models, added/renamed/deleted profiles) become visible. The two loads are independent: one
     * failing still applies the other. A refresh already in progress is ignored (no second parallel run).
     */
    public void refresh() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        modelRefreshError = null;
        profileRefreshError = null;
        pendingRefreshLoads = 2;
        refreshButton.setEnabled(false);
        status.setText("Refreshing models and profiles...");
        refresher.loadModels(new Consumer<BatchSelectionCatalogLoadedEvent>() {
            public void accept(final BatchSelectionCatalogLoadedEvent event) {
                onUi(new Runnable() {
                    public void run() { applyModelRefresh(event); }
                });
            }
        });
        refresher.loadProfiles(new Consumer<BatchProfileCatalogLoadedEvent>() {
            public void accept(final BatchProfileCatalogLoadedEvent event) {
                onUi(new Runnable() {
                    public void run() { applyProfileRefresh(event); }
                });
            }
        });
    }

    private void applyModelRefresh(BatchSelectionCatalogLoadedEvent event) {
        if (event.isSuccessful()) {
            setAvailableModels(event.getAudioModelNames());
        } else {
            modelRefreshError = event.getMessage();
        }
        finishOneRefreshLoad();
    }

    private void applyProfileRefresh(BatchProfileCatalogLoadedEvent event) {
        if (event.isSuccessful()) {
            setAvailableProfiles(event.getProfiles());
        } else {
            profileRefreshError = event.getMessage();
        }
        finishOneRefreshLoad();
    }

    private void finishOneRefreshLoad() {
        if (--pendingRefreshLoads > 0) {
            return;
        }
        refreshing = false;
        refreshButton.setEnabled(true);
        status.setText(refreshSummary());
    }

    private String refreshSummary() {
        StringBuilder text = new StringBuilder();
        if (modelRefreshError != null) {
            text.append("Model refresh failed: ").append(modelRefreshError);
        }
        if (profileRefreshError != null) {
            if (text.length() > 0) text.append(" | ");
            text.append("Profile refresh failed: ").append(profileRefreshError);
        }
        return text.length() > 0 ? text.toString() : "Models and profiles refreshed.";
    }

    private void onUi(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private void chooseAudioFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(SupportedAudioFormats.fileChooserDescription(),
                SupportedAudioFormats.extensionArray()));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File file : chooser.getSelectedFiles()) audioFiles.addElement(file);
        }
    }

    /** Single click on a file previews its matching {@code .md}; a double click pins it in its own tab. */
    private void onFileListClick(MouseEvent event) {
        int index = fileList.locationToIndex(event.getPoint());
        if (index < 0 || index >= audioFiles.size()) {
            return;
        }
        Rectangle bounds = fileList.getCellBounds(index, index);
        if (bounds == null || !bounds.contains(event.getPoint())) {
            return; // click landed on empty space
        }
        openMarkdownFor(audioFiles.get(index), event.getClickCount() >= 2);
    }

    private void openMarkdownFor(File audio, boolean pinned) {
        File markdown = markdownSiblingOf(audio);
        if (!markdown.isFile()) {
            status.setText("No " + markdown.getName() + " found next to " + audio.getName() + ".");
            return;
        }
        String content = readMarkdown(markdown);
        if (pinned) {
            bottomTabs.pin(markdown.getAbsolutePath(), markdown.getName(), content);
        } else {
            bottomTabs.preview(markdown.getName(), content);
        }
    }

    /** The sibling Markdown file with the same base name (e.g. {@code sample-001.wav -> sample-001.md}). */
    static File markdownSiblingOf(File audio) {
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(audio.getParentFile(), base + ".md");
    }

    private static String readMarkdown(File markdown) {
        try {
            return new String(Files.readAllBytes(markdown.toPath()), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "Could not read `" + markdown.getName() + "`: " + ex.getMessage();
        }
    }

    private void startBatch() {
        List<File> files = new ArrayList<File>();
        for (int i = 0; i < audioFiles.size(); i++) files.add(audioFiles.get(i));
        List<String> models = modelList.getSelectedValuesList();
        List<AudioProcessingProfile> profiles = profileList.getSelectedValuesList();
        try {
            controller.start(new BatchTranscriptionRequest(files, models, profiles, "auto", ""));
        } catch (RuntimeException ex) {
            status.setText(ex.getMessage());
        }
    }

    private void apply(BatchTranscriptionEvent event) {
        progress.setMaximum(Math.max(1, event.getTotalItems()));
        progress.setValue(event.getCompletedItems());
        status.setText(event.getMessage());
        log.append(format(event) + System.lineSeparator());
        boolean running = event.getType() != BatchTranscriptionEvent.Type.BATCH_COMPLETED
                && event.getType() != BatchTranscriptionEvent.Type.BATCH_CANCELLED;
        startButton.setEnabled(!running);
        cancelButton.setEnabled(running);
        if (!running) controller.markFinished();
    }

    private String format(BatchTranscriptionEvent event) {
        StringBuilder text = new StringBuilder("[").append(event.getType()).append("] ");
        if (event.getModelName().length() > 0) text.append(event.getModelName()).append(" | ");
        if (event.getAudioFile() != null) text.append(event.getAudioFile().getName()).append(" | ");
        if (event.getProfileName().length() > 0) text.append(event.getProfileName()).append(" | ");
        return text.append(event.getMessage()).toString();
    }

    public void dispose() { subscription.unsubscribe(); }

    // Package-private accessors for tests in this package (selection/model state verification).
    JList<String> modelListComponent() { return modelList; }

    JList<AudioProcessingProfile> profileListComponent() { return profileList; }
}
