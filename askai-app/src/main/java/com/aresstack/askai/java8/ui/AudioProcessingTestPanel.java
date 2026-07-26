package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.audio.preview.AudioPreviewPlaybackService;
import com.aresstack.askai.java8.audio.preview.AudioProcessingTestController;
import com.aresstack.askai.java8.audio.preview.AudioTestRecordingStore;
import com.aresstack.askai.java8.audio.preview.JavaSoundAudioPreviewPlaybackService;
import com.aresstack.askai.java8.audio.preview.WavAudioTestSource;
import com.aresstack.askai.java8.speech.JavaSoundMicrophoneRecorder;
import com.aresstack.askai.java8.speech.MicrophoneRecorder;
import com.aresstack.askai.java8.speech.RawRecording;
import com.aresstack.audio.application.DefaultAudioProcessingPreviewService;
import com.aresstack.audio.application.DefaultProcessedWaveExportService;
import com.aresstack.audio.application.ProcessedAudioPreview;
import com.aresstack.audio.profile.AudioProcessingProfile;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * The DSP test/preview area under the pipeline editor: pick a local WAV or record a microphone test wave,
 * process the current (possibly unsaved) pipeline snapshot through the productive preview path, play the
 * original or processed audio, and export the processed result as a WAV. All heavy work runs off the EDT
 * via {@link AudioProcessingTestController}; this panel only wires buttons and reflects state.
 */
public final class AudioProcessingTestPanel extends JPanel {

    private final AudioProcessingTestController controller;
    private final AudioTestRecordingStore recordingStore = new AudioTestRecordingStore();
    private final MicrophoneRecorder recorder = new JavaSoundMicrophoneRecorder();
    private final AudioPreviewPlaybackService rawPlayback = new JavaSoundAudioPreviewPlaybackService();
    private final File tempDir = new File(System.getProperty("java.io.tmpdir"), "askai-audio-tests-temp");
    private final ExecutorService executor;

    private final JLabel sourceLabel = new JLabel("No test file selected");
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton processAndPlayButton = new JButton("Process and play");
    private final JButton processButton = new JButton("Process");
    private final JButton playOriginalButton = new JButton("Play original");
    private final JButton playProcessedButton = new JButton("Play processed");
    private final JButton stopButton = new JButton("Stop");
    private final JButton saveButton = new JButton("Save processed WAV…");

    private File lastDirectory;

    public AudioProcessingTestPanel(Supplier<AudioProcessingProfile> snapshotSupplier) {
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "askai-audio-preview");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.controller = new AudioProcessingTestController(
                new DefaultAudioProcessingPreviewService(),
                new JavaSoundAudioPreviewPlaybackService(),
                new DefaultProcessedWaveExportService(),
                snapshotSupplier, executor, new EdtListener());
        buildUserInterface();
        refreshControls();
    }

    /** Notify the controller that the editor pipeline changed, so a preview result is marked outdated. */
    public void pipelineChanged() {
        controller.pipelineChanged();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(6, 4));
        setBorder(BorderFactory.createTitledBorder("Test & preview"));

        JPanel sourceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton selectFile = new JButton("Select test file…");
        selectFile.addActionListener(event -> selectTestFile());
        JButton record = new JButton("Record test wave…");
        record.addActionListener(event -> recordTestWave());
        sourceRow.add(selectFile);
        sourceRow.add(record);
        sourceRow.add(sourceLabel);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        processAndPlayButton.addActionListener(event -> controller.processAndPlay());
        processButton.addActionListener(event -> controller.process());
        playOriginalButton.addActionListener(event -> controller.playOriginal());
        playProcessedButton.addActionListener(event -> controller.playProcessed());
        stopButton.addActionListener(event -> controller.stop());
        saveButton.addActionListener(event -> saveProcessed());
        actionRow.add(processAndPlayButton);
        actionRow.add(processButton);
        actionRow.add(playOriginalButton);
        actionRow.add(playProcessedButton);
        actionRow.add(stopButton);
        actionRow.add(saveButton);

        JPanel top = new JPanel(new BorderLayout());
        top.add(sourceRow, BorderLayout.NORTH);
        top.add(actionRow, BorderLayout.CENTER);
        add(top, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    private void selectTestFile() {
        JFileChooser chooser = new JFileChooser();
        if (lastDirectory != null) {
            chooser.setCurrentDirectory(lastDirectory);
        }
        chooser.setDialogTitle("Select a WAV test file");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("WAV files (*.wav)", "wav"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        lastDirectory = file.getParentFile();
        controller.setSource(new WavAudioTestSource(file, false));
        sourceLabel.setText("Source: " + file.getName());
    }

    private void saveProcessed() {
        ProcessedAudioPreview preview = controller.getPreview();
        if (preview == null) {
            setStatus("Nothing to save yet — process the pipeline first.");
            return;
        }
        if (controller.isOutdated()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "The preview is from an older pipeline. Re-process the current pipeline before saving?",
                    "Result outdated", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice == JOptionPane.CANCEL_OPTION) {
                return;
            }
            if (choice == JOptionPane.YES_OPTION) {
                controller.process(); // refresh, then the user saves again once processed
                setStatus("Re-processing… save again when it is ready.");
                return;
            }
        }
        JFileChooser chooser = new JFileChooser();
        if (lastDirectory != null) {
            chooser.setCurrentDirectory(lastDirectory);
        }
        chooser.setDialogTitle("Save processed WAV");
        chooser.setFileFilter(new FileNameExtensionFilter("WAV files (*.wav)", "wav"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase().endsWith(".wav")) {
            target = new File(target.getParentFile(), target.getName() + ".wav");
        }
        if (target.exists() && JOptionPane.showConfirmDialog(this,
                "Overwrite the existing file " + target.getName() + "?", "Overwrite",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        lastDirectory = target.getParentFile();
        controller.export(target);
        setStatus("Saving processed WAV…");
    }

    // ------------------------------------------------------------------ recording

    private void recordTestWave() {
        controller.noteRecording(true);
        final MicrophoneRecorder.Session session;
        try {
            if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
                throw new java.io.IOException("Cannot create the temp recording directory.");
            }
            session = recorder.start("", tempDir); // system default device
        } catch (Exception ex) {
            controller.noteRecording(false);
            JOptionPane.showMessageDialog(this, "Could not start recording:\n" + message(ex),
                    "Recording", JOptionPane.ERROR_MESSAGE);
            return;
        }
        // Blocking modal: record until the user stops, then offer play / save / discard.
        int stop = JOptionPane.showConfirmDialog(this,
                "Recording… speak your test sentence, then press OK to stop (Cancel discards).",
                "Recording", JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (stop != JOptionPane.OK_OPTION) {
            session.discard();
            controller.noteRecording(false);
            setStatus("Recording cancelled.");
            return;
        }
        RawRecording raw;
        try {
            raw = session.stop();
        } catch (Exception ex) {
            controller.noteRecording(false);
            JOptionPane.showMessageDialog(this, "Recording failed:\n" + message(ex),
                    "Recording", JOptionPane.ERROR_MESSAGE);
            return;
        }
        controller.noteRecording(false);
        offerRecording(raw);
    }

    private void offerRecording(RawRecording raw) {
        Object[] options = {"Save and use", "Play", "Discard"};
        while (true) {
            int choice = JOptionPane.showOptionDialog(this,
                    "Raw test recording ready (" + raw.getCaptureFormat() + ").",
                    "Test recording", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            if (choice == 1) { // Play
                try {
                    WavAudioTestSource temp = new WavAudioTestSource(raw.getFile(), true);
                    rawPlayback.play(temp.readBuffer().getSamples(), temp.readBuffer().getFormat(), null);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Could not play the recording:\n" + message(ex),
                            "Recording", JOptionPane.ERROR_MESSAGE);
                }
                continue;
            }
            rawPlayback.stop();
            if (choice == 0) { // Save and use
                String name = JOptionPane.showInputDialog(this, "Name for this test recording:",
                        "dsp-test-recording");
                if (name == null) {
                    continue;
                }
                try {
                    File saved = recordingStore.saveConfirmed(raw.getFile(), name);
                    controller.setSource(new WavAudioTestSource(saved, true));
                    sourceLabel.setText("Source: " + saved.getName() + " (recording)");
                    setStatus("Saved test recording to " + saved.getAbsolutePath());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Could not save the recording:\n" + message(ex),
                            "Recording", JOptionPane.ERROR_MESSAGE);
                }
                return;
            }
            // Discard
            if (raw.getFile() != null && raw.getFile().exists() && !raw.getFile().delete()) {
                raw.getFile().deleteOnExit();
            }
            setStatus("Recording discarded.");
            return;
        }
    }

    // ------------------------------------------------------------------ state → UI

    private final class EdtListener implements AudioProcessingTestController.Listener {
        public void stateChanged(final AudioProcessingTestController.State state) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    setStatus(describe(state));
                    refreshControls();
                }
            });
        }

        public void previewUpdated(ProcessedAudioPreview preview, boolean outdated) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    refreshControls();
                }
            });
        }

        public void failed(final String message) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    setStatus("Failed: " + message);
                    refreshControls();
                }
            });
        }
    }

    private void refreshControls() {
        boolean hasSource = controller.getSource() != null;
        boolean hasPreview = controller.getPreview() != null;
        boolean recording = controller.getState() == AudioProcessingTestController.State.RECORDING;
        processAndPlayButton.setEnabled(hasSource && !recording);
        processButton.setEnabled(hasSource && !recording);
        playOriginalButton.setEnabled(hasSource && !recording);
        playProcessedButton.setEnabled(hasPreview && !recording);
        saveButton.setEnabled(hasPreview && !recording);
        stopButton.setEnabled(!recording);
    }

    private void setStatus(String text) {
        statusLabel.setText(text == null || text.isEmpty() ? " " : text);
    }

    private static String describe(AudioProcessingTestController.State state) {
        switch (state) {
            case NO_SOURCE:
                return "No test file selected.";
            case READY:
                return "Ready.";
            case RECORDING:
                return "Recording…";
            case PROCESSING:
                return "Processing…";
            case PROCESSED:
                return "Processed.";
            case PLAYING_ORIGINAL:
                return "Playing original…";
            case PLAYING_PROCESSED:
                return "Playing processed…";
            case RESULT_OUTDATED:
                return "Result outdated — pipeline changed since this preview.";
            case CANCELLED:
                return "Cancelled.";
            case FAILED:
                return "Failed.";
            default:
                return " ";
        }
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.toString() : ex.getMessage();
    }

    /** Stops playback and shuts down the background executor (call when the editor/app closes). */
    public void dispose() {
        controller.stop();
        rawPlayback.stop();
        executor.shutdownNow();
    }

    JComponent getComponent() {
        return this;
    }
}
