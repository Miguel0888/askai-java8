package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.tts.PiperInstaller;
import com.aresstack.askai.java8.tts.PiperTtsStore;
import com.aresstack.askai.java8.tts.PiperVoice;
import com.aresstack.askai.java8.tts.PiperVoiceCatalog;
import com.aresstack.askai.java8.tts.TextToSpeechSettings;
import com.aresstack.askai.java8.tts.TtsSettingsStore;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The Model Browser "Speech Output" tab: install a curated, natural-sounding read-aloud voice
 * (Piper, HuggingFace {@code rhasspy/piper-voices}) with one click. The engine and every voice run
 * ENTIRELY on the CPU — the GPU stays free for the chat models (GPU acceleration: coming soon).
 * After a successful install the user is asked whether to use the voice right away; either way it
 * stays selectable in the chat settings under "Audio &amp; Dictation". Mirrors
 * {@link NlpModelsPanel}: pure UI orchestration, installs off the EDT, errors shown, never
 * swallowed.
 */
public final class SpeechOutputModelsPanel extends JPanel {

    public static final String TAB_TITLE = "Speech Output";

    /** The one action the panel performs; {@link PiperInstaller#install} matches it. */
    public interface InstallAction {
        void install(PiperVoice voice, PiperInstaller.Progress progress) throws Exception;
    }

    /** The post-install question, injectable so tests never open a dialog. */
    public interface AdoptPrompt {
        boolean confirmUseNow(PiperVoice voice);
    }

    private final PiperTtsStore store;
    private final TtsSettingsStore settings;
    private final InstallAction installer;
    private final AdoptPrompt adoptPrompt;
    private final Executor background;
    private final Executor ui;

    private final Map<String, JLabel> statusByVoice = new LinkedHashMap<String, JLabel>();
    private final Map<String, JButton> buttonByVoice = new LinkedHashMap<String, JButton>();
    private final JLabel error = new JLabel(" ");

    /** Productive wiring: real installer + a background thread + the EDT + a JOptionPane prompt. */
    public SpeechOutputModelsPanel(PiperTtsStore store, TtsSettingsStore settings) {
        this(store, settings, installActionOver(store, settings), null,
                Executors.newSingleThreadExecutor(daemon("tts-install")), edt());
    }

    SpeechOutputModelsPanel(PiperTtsStore store, TtsSettingsStore settings, InstallAction installer,
                            AdoptPrompt adoptPrompt, Executor background, Executor ui) {
        super(new BorderLayout(8, 8));
        this.store = store;
        this.settings = settings;
        this.installer = installer;
        this.adoptPrompt = adoptPrompt != null ? adoptPrompt : dialogPrompt();
        this.background = background;
        this.ui = ui;
        buildUserInterface();
        refresh();
    }

    private void buildUserInterface() {
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBorder(BorderFactory.createTitledBorder("Read-aloud voices (Piper)"));
        JLabel intro = new JLabel("<html>The default speech output is the Windows voice — it needs"
                + " no download. These model voices sound far more natural and run entirely on the"
                + " <b>CPU</b>, so your GPU stays free for the chat models."
                + " GPU acceleration: <i>coming soon</i>.</html>");
        JPanel introRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        introRow.add(intro);
        rows.add(introRow);
        for (PiperVoice voice : PiperVoiceCatalog.curated()) {
            rows.add(buildRow(voice));
        }
        add(rows, BorderLayout.NORTH);
        add(error, BorderLayout.SOUTH);
    }

    private JPanel buildRow(final PiperVoice voice) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.add(new JLabel(voice.getLanguage() + " — " + voice.getDisplayName()
                + "  ·  ~" + voice.getApproximateSizeMb() + " MB"));
        JLabel status = new JLabel();
        JButton install = new JButton("Install");
        install.addActionListener(event -> onInstall(voice));
        statusByVoice.put(voice.getId(), status);
        buttonByVoice.put(voice.getId(), install);
        row.add(Box.createHorizontalStrut(8));
        row.add(status);
        row.add(install);
        return row;
    }

    /** Recompute installed/selected state for every row. */
    void refresh() {
        String selected = settings.load().getEngine() == TextToSpeechSettings.Engine.PIPER
                ? settings.load().getVoiceId() : "";
        for (PiperVoice voice : PiperVoiceCatalog.curated()) {
            JLabel status = statusByVoice.get(voice.getId());
            JButton button = buttonByVoice.get(voice.getId());
            if (status == null || button == null) {
                continue;
            }
            boolean isInstalled = store.isVoiceInstalled(voice);
            status.setText(!isInstalled ? "Not installed"
                    : voice.getId().equals(selected) ? "Installed · in use" : "Installed");
            button.setVisible(!isInstalled);
            button.setEnabled(!isInstalled);
        }
    }

    private void onInstall(final PiperVoice voice) {
        final JButton button = buttonByVoice.get(voice.getId());
        final JLabel status = statusByVoice.get(voice.getId());
        if (button != null) {
            button.setEnabled(false);
        }
        error.setText(" ");
        background.execute(new Runnable() {
            public void run() {
                Exception failure = null;
                try {
                    installer.install(voice, new PiperInstaller.Progress() {
                        public void onProgress(final String stage, final long done, final long total) {
                            ui.execute(new Runnable() {
                                public void run() {
                                    if (status != null) {
                                        status.setText(total > 0
                                                ? stage + " " + (100 * done / total) + "%"
                                                : stage + " …");
                                    }
                                }
                            });
                        }
                    });
                } catch (Exception ex) {
                    failure = ex;
                }
                final Exception outcome = failure;
                ui.execute(new Runnable() {
                    public void run() {
                        if (outcome == null) {
                            offerAdoption(voice);
                            refresh();
                        } else {
                            error.setText("Install failed: " + describe(outcome));
                            refresh();
                        }
                    }
                });
            }
        });
    }

    /** The frustration-free hand-over: use the fresh voice right away, or leave the default. */
    private void offerAdoption(PiperVoice voice) {
        if (!adoptPrompt.confirmUseNow(voice)) {
            return;
        }
        try {
            settings.save(settings.load()
                    .withEngine(TextToSpeechSettings.Engine.PIPER)
                    .withVoiceId(voice.getId()));
        } catch (java.io.IOException notSaved) {
            error.setText("Voice installed, but selecting it failed: " + notSaved.getMessage());
        }
    }

    private static String describe(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    // ------------------------------------------------------------------ test hooks

    JButton installButton(String voiceId) {
        return buttonByVoice.get(voiceId);
    }

    String statusText(String voiceId) {
        JLabel label = statusByVoice.get(voiceId);
        return label == null ? null : label.getText();
    }

    String errorText() {
        return error.getText();
    }

    // ------------------------------------------------------------------ productive wiring helpers

    private AdoptPrompt dialogPrompt() {
        return new AdoptPrompt() {
            public boolean confirmUseNow(PiperVoice voice) {
                return JOptionPane.showConfirmDialog(SpeechOutputModelsPanel.this,
                        "Use \"" + voice + "\" for speech output now?\n\n"
                                + "You can change this anytime in the chat settings (gear)\n"
                                + "under \"Audio & Dictation\".",
                        "Voice installed", JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
            }
        };
    }

    private static InstallAction installActionOver(PiperTtsStore store, TtsSettingsStore settings) {
        final PiperInstaller installer = new PiperInstaller(store, settings);
        return new InstallAction() {
            public void install(PiperVoice voice, PiperInstaller.Progress progress) throws Exception {
                installer.install(voice, progress);
            }
        };
    }

    private static Executor edt() {
        return new Executor() {
            public void execute(Runnable command) {
                SwingUtilities.invokeLater(command);
            }
        };
    }

    private static java.util.concurrent.ThreadFactory daemon(final String name) {
        return new java.util.concurrent.ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
