package com.aresstack.askai.java8.batch.ui;

import com.aresstack.askai.java8.audio.format.SupportedAudioFormats;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionEvent;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionEventPublisher;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionRequest;
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
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Swing view for selecting files, models and profiles and observing batch progress. */
public final class BatchTranscriptionPanel extends JPanel {

    private final BatchTranscriptionController controller;
    private final DefaultListModel<File> audioFiles = new DefaultListModel<File>();
    private final DefaultListModel<String> models = new DefaultListModel<String>();
    private final JList<String> modelList = new JList<String>(models);
    private final JList<AudioProcessingProfile> profileList;
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel("Ready");
    private final JTextArea log = new JTextArea();
    private final JButton startButton = new JButton("Start batch");
    private final JButton cancelButton = new JButton("Cancel");
    private final BatchTranscriptionEventPublisher.Subscription subscription;

    public BatchTranscriptionPanel(BatchTranscriptionController controller,
                                   List<String> availableModels,
                                   List<AudioProcessingProfile> availableProfiles) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        for (String model : availableModels) models.addElement(model);
        this.profileList = new JList<AudioProcessingProfile>(
                availableProfiles.toArray(new AudioProcessingProfile[availableProfiles.size()]));
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
        JList<File> fileList = new JList<File>(audioFiles);
        modelList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        profileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JPanel selections = new JPanel(new GridLayout(1, 3, 8, 8));
        selections.add(section("Audio files", new JScrollPane(fileList)));
        selections.add(section("Audio AI models", new JScrollPane(modelList)));
        selections.add(section("Audio profiles", new JScrollPane(profileList)));

        JButton addFiles = new JButton("Add audio files...");
        addFiles.addActionListener(event -> chooseAudioFiles());
        startButton.addActionListener(event -> startBatch());
        cancelButton.addActionListener(event -> controller.cancel());
        cancelButton.setEnabled(false);
        JPanel actions = new JPanel();
        actions.add(addFiles);
        actions.add(startButton);
        actions.add(cancelButton);

        log.setEditable(false);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, selections, new JScrollPane(log));
        split.setResizeWeight(0.55d);
        add(actions, BorderLayout.NORTH);
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
     * {@link SwingUtilities#invokeLater}). Retains any still-available selection.
     */
    public void setAvailableModels(List<String> availableModels) {
        List<String> previouslySelected = modelList.getSelectedValuesList();
        models.clear();
        for (String model : availableModels) models.addElement(model);
        List<Integer> indices = new ArrayList<Integer>();
        for (int i = 0; i < models.size(); i++) {
            if (previouslySelected.contains(models.get(i))) indices.add(i);
        }
        int[] selection = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) selection[i] = indices.get(i);
        modelList.setSelectedIndices(selection);
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
}
