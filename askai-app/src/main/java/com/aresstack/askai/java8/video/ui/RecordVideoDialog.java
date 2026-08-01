package com.aresstack.askai.java8.video.ui;

import com.aresstack.askai.java8.video.MediaRecorderProvider;
import com.aresstack.askai.java8.video.RecordingProfile;
import com.aresstack.askai.java8.video.RecordingSource;
import com.aresstack.askai.java8.video.VideoRecordingController;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * The generic Record Video dialog (opened from Help → Record Video…). It is the ONLY video class that
 * touches Swing and it works exclusively through {@link VideoRecordingController} — no JCodec/VLC/FFmpeg
 * types here. It hands the controller a NEUTRAL {@link RecordingSource} (bounds), never an AskAiFrame.
 */
public final class RecordVideoDialog extends JDialog {

    private final VideoRecordingController controller;
    private final Rectangle ownerWindowBounds;

    private final JComboBox<String> sourceCombo = new JComboBox<String>();
    private final JComboBox<String> backendCombo = new JComboBox<String>();
    private final JTextField outputField = new JTextField(28);
    private final JButton chooseOutput = new JButton("Choose…");
    private final JButton startButton = new JButton("Start Recording");
    private final JButton stopButton = new JButton("Stop Recording");
    private final JButton openFolder = new JButton("Open Folder");
    private final JLabel status = new JLabel(" ");

    private List<MediaRecorderProvider> available;
    private Path lastOutput;

    public RecordVideoDialog(Window owner, VideoRecordingController controller, Rectangle ownerBounds,
                             String defaultFileName) {
        super(owner, "Record Video", ModalityType.MODELESS);
        this.controller = controller;
        this.ownerWindowBounds = ownerBounds;
        buildUi(defaultFileName);
        wireController();
        applyState(controller.getState());
    }

    private void buildUi(String defaultFileName) {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        sourceCombo.addItem("AskAI Window");
        sourceCombo.addItem("Screen / Monitor");
        form.add(row("Source:", sourceCombo));

        available = controller.availableProviders();
        for (int i = 0; i < available.size(); i++) {
            backendCombo.addItem(available.get(i).getDisplayName());
        }
        // Preselect the controller's default (JCodec) if present.
        for (int i = 0; i < available.size(); i++) {
            if (available.get(i).getId().equals(controller.getSelectedProvider().getId())) {
                backendCombo.setSelectedIndex(i);
            }
        }
        form.add(row("Backend:", backendCombo));

        JPanel out = new JPanel(new BorderLayout(6, 0));
        outputField.setText(defaultOutputPath(defaultFileName));
        out.add(outputField, BorderLayout.CENTER);
        out.add(chooseOutput, BorderLayout.EAST);
        form.add(row("Output:", out));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(startButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(stopButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(openFolder);
        form.add(Box.createVerticalStrut(8));
        form.add(buttons);
        form.add(Box.createVerticalStrut(6));
        form.add(status);

        add(form, BorderLayout.CENTER);
        setMinimumSize(new Dimension(460, 220));
        pack();
        setLocationRelativeTo(getOwner());

        chooseOutput.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                chooseOutput();
            }
        });
        startButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                startRecording();
            }
        });
        stopButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                controller.stop();
            }
        });
        openFolder.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                openOutputFolder();
            }
        });
        if (backendCombo.getItemCount() == 0) {
            status.setText("No recording backend is available.");
            startButton.setEnabled(false);
        }
    }

    private void wireController() {
        controller.setListener(new VideoRecordingController.Listener() {
            public void onStateChanged(final VideoRecordingController.State state) {
                onEdt(new Runnable() {
                    public void run() {
                        applyState(state);
                    }
                });
            }

            public void onRecordingStarted(RecordingProfile profile) {
                onEdt(new Runnable() {
                    public void run() {
                        status.setText("Recording…");
                    }
                });
            }

            public void onRecordingStopped(final Path outputFile) {
                onEdt(new Runnable() {
                    public void run() {
                        lastOutput = outputFile;
                        status.setText("Saved: " + outputFile);
                    }
                });
            }

            public void onError(final String message) {
                onEdt(new Runnable() {
                    public void run() {
                        status.setText(message);
                    }
                });
            }
        });
    }

    private void startRecording() {
        // Persist the backend choice first (rejected by the controller while recording).
        int backendIndex = backendCombo.getSelectedIndex();
        if (backendIndex >= 0 && backendIndex < available.size()) {
            controller.selectProvider(available.get(backendIndex).getId());
        }
        Path output = Paths.get(outputField.getText().trim());
        RecordingSource source = buildSource();
        if (source == null) {
            status.setText("The capture source has no valid bounds.");
            return;
        }
        RecordingProfile profile;
        try {
            profile = RecordingProfile.builder()
                    .source(source)
                    .outputFile(output)
                    .fps(15)
                    .build();
        } catch (RuntimeException invalid) {
            status.setText(invalid.getMessage());
            return;
        }
        controller.start(profile);
    }

    private RecordingSource buildSource() {
        boolean window = sourceCombo.getSelectedIndex() == 0;
        if (window) {
            if (ownerWindowBounds == null || ownerWindowBounds.width <= 0
                    || ownerWindowBounds.height <= 0) {
                return null;
            }
            return RecordingSource.window(ownerWindowBounds, "AskAI Window");
        }
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        return RecordingSource.screen(screen, "Screen");
    }

    private void applyState(VideoRecordingController.State state) {
        boolean recording = state == VideoRecordingController.State.RECORDING;
        startButton.setEnabled(!recording && backendCombo.getItemCount() > 0);
        stopButton.setEnabled(recording);
        sourceCombo.setEnabled(!recording);
        backendCombo.setEnabled(!recording);
        outputField.setEnabled(!recording);
        chooseOutput.setEnabled(!recording);
    }

    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(outputField.getText().trim()));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            outputField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void openOutputFolder() {
        Path target = lastOutput != null ? lastOutput : Paths.get(outputField.getText().trim());
        File folder = target.getParent() == null ? null : target.getParent().toFile();
        if (folder != null && folder.isDirectory()) {
            try {
                java.awt.Desktop.getDesktop().open(folder);
            } catch (Exception ex) {
                status.setText("Could not open the folder: " + ex.getMessage());
            }
        }
    }

    private static String defaultOutputPath(String defaultFileName) {
        String dir = System.getProperty("user.home", ".");
        return Paths.get(dir, defaultFileName).toString();
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private static JPanel row(String label, java.awt.Component field) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(90, 24));
        p.add(l, BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return p;
    }
}
