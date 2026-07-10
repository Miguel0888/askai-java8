package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.AskAiModel;
import com.aresstack.askai.java8.client.OllamaModelInfo;
import com.aresstack.askai.java8.client.OllamaRunningModelInfo;
import com.aresstack.askai.java8.service.OllamaService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.List;

/**
 * Shows installed and currently loaded Ollama models as rich object cards. Installed and running
 * are two separate views selected from the "Models" menu (no in-panel tabs).
 */
public final class OllamaModelsPanel extends JPanel {

    private static final String INSTALLED_CARD = "installed";
    private static final String RUNNING_CARD = "running";

    private final AskAiModel model;
    private final OllamaService ollamaService;
    private final CardLayout cardLayout;
    private final JPanel cards;
    private final JPanel installedCardsPanel;
    private final JPanel runningCardsPanel;
    private final JLabel installedStatusLabel;
    private final JLabel runningStatusLabel;
    private final JLabel informationLabel;
    private boolean serverInformationLoaded;

    public OllamaModelsPanel(AskAiModel model, OllamaService ollamaService) {
        this.model = model;
        this.ollamaService = ollamaService;
        this.cardLayout = new CardLayout();
        this.cards = new JPanel(cardLayout);
        this.installedCardsPanel = createCardsPanel();
        this.runningCardsPanel = createCardsPanel();
        this.installedStatusLabel = new JLabel("Installed models are not loaded yet.");
        this.runningStatusLabel = new JLabel("Running models are not loaded yet.");
        this.informationLabel = new JLabel("Ollama server information is not loaded yet.");
        buildUserInterface();
    }

    /** Show the installed-models view and refresh it (the "Models > Installed" entry). */
    public void showInstalled() {
        cardLayout.show(cards, INSTALLED_CARD);
        ensureServerInformation();
        refreshInstalledModels();
    }

    /** Show the running-models view and refresh it (the "Models > Running Models" entry). */
    public void showRunning() {
        cardLayout.show(cards, RUNNING_CARD);
        ensureServerInformation();
        refreshRunningModels();
    }

    private void ensureServerInformation() {
        if (!serverInformationLoaded) {
            serverInformationLoaded = true;
            refreshServerInformation();
        }
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        cards.add(createInstalledModelsCard(), INSTALLED_CARD);
        cards.add(createRunningModelsCard(), RUNNING_CARD);
        add(cards, BorderLayout.CENTER);
        informationLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        add(informationLabel, BorderLayout.SOUTH);
    }

    private JPanel createInstalledModelsCard() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(createInstalledToolbar(), BorderLayout.NORTH);
        panel.add(new JScrollPane(installedCardsPanel), BorderLayout.CENTER);
        showInstalledPlaceholder("Open Models > Installed or click Refresh to load installed models.");
        return panel;
    }

    private JPanel createRunningModelsCard() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(createRunningToolbar(), BorderLayout.NORTH);
        panel.add(new JScrollPane(runningCardsPanel), BorderLayout.CENTER);
        showRunningPlaceholder("Open Models > Running Models or click Refresh to load running models.");
        return panel;
    }

    private JPanel createInstalledToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(event -> refreshInstalledModels());
        toolbar.add(refreshButton);
        toolbar.add(installedStatusLabel);
        return toolbar;
    }

    private JPanel createRunningToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(event -> refreshRunningModels());
        toolbar.add(refreshButton);
        toolbar.add(runningStatusLabel);
        return toolbar;
    }

    private static JPanel createCardsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return panel;
    }

    private void refreshInstalledModels() {
        installedStatusLabel.setText("Loading installed models from " + model.getOllamaBaseUrl() + " ...");
        showInstalledPlaceholder("Loading installed models ...");
        ollamaService.listInstalledModels(new OllamaService.InstalledModelsListener() {
            @Override
            public void onInstalledModels(final List<OllamaModelInfo> models) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        showInstalledModels(models);
                        installedStatusLabel.setText("Loaded " + models.size() + " installed models.");
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        showInstalledPlaceholder("Could not load installed models: " + ex.getMessage());
                        installedStatusLabel.setText("Error while loading installed models.");
                    }
                });
            }
        });
    }

    private void refreshRunningModels() {
        runningStatusLabel.setText("Loading running models from " + model.getOllamaBaseUrl() + " ...");
        showRunningPlaceholder("Loading running models ...");
        ollamaService.listRunningModels(new OllamaService.RunningModelsListener() {
            @Override
            public void onRunningModels(final List<OllamaRunningModelInfo> models) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        showRunningModels(models);
                        runningStatusLabel.setText("Loaded " + models.size() + " running models.");
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        showRunningPlaceholder("Could not load running models: " + ex.getMessage());
                        runningStatusLabel.setText("Error while loading running models.");
                    }
                });
            }
        });
    }

    private void refreshServerInformation() {
        informationLabel.setText("Loading Ollama server information ...");
        ollamaService.getServerVersion(new OllamaService.ServerVersionListener() {
            @Override
            public void onServerVersion(final String version) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        informationLabel.setText(version == null || version.isEmpty()
                                ? "Ollama server: " + model.getOllamaBaseUrl()
                                : "Ollama server: " + model.getOllamaBaseUrl() + " | version " + version);
                    }
                });
            }

            @Override
            public void onError(Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        informationLabel.setText("Ollama server: " + model.getOllamaBaseUrl());
                    }
                });
            }
        });
    }

    private void showInstalledModels(List<OllamaModelInfo> models) {
        installedCardsPanel.removeAll();
        if (models.isEmpty()) {
            addPlaceholder(installedCardsPanel, "No installed models returned by Ollama.");
        } else {
            for (final OllamaModelInfo modelInfo : models) {
                installedCardsPanel.add(OllamaModelCard.installed(modelInfo, new Runnable() {
                    @Override
                    public void run() {
                        confirmAndDelete(modelInfo.getDisplayName());
                    }
                }));
                installedCardsPanel.add(Box.createVerticalStrut(6));
            }
        }
        refreshCards(installedCardsPanel);
    }

    private void showRunningModels(List<OllamaRunningModelInfo> models) {
        runningCardsPanel.removeAll();
        if (models.isEmpty()) {
            addPlaceholder(runningCardsPanel, "No running models returned by Ollama.");
        } else {
            for (OllamaRunningModelInfo modelInfo : models) {
                runningCardsPanel.add(OllamaModelCard.running(modelInfo));
                runningCardsPanel.add(Box.createVerticalStrut(6));
            }
        }
        refreshCards(runningCardsPanel);
    }

    private void showInstalledPlaceholder(String message) {
        installedCardsPanel.removeAll();
        addPlaceholder(installedCardsPanel, message);
        refreshCards(installedCardsPanel);
    }

    private void showRunningPlaceholder(String message) {
        runningCardsPanel.removeAll();
        addPlaceholder(runningCardsPanel, message);
        refreshCards(runningCardsPanel);
    }

    private void confirmAndDelete(final String modelName) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete model '" + modelName + "' from Ollama?",
                "Delete model",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        installedStatusLabel.setText("Deleting " + modelName + " ...");
        ollamaService.deleteModel(modelName, new OllamaService.ActionListener() {
            @Override
            public void onComplete(final String message) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        installedStatusLabel.setText(message);
                        refreshInstalledModels();
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        installedStatusLabel.setText("Could not delete " + modelName + ": " + ex.getMessage());
                    }
                });
            }
        });
    }

    private static void addPlaceholder(JPanel target, String message) {
        JLabel label = new JLabel(message);
        label.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        target.add(label);
    }

    private static void refreshCards(JPanel panel) {
        panel.revalidate();
        panel.repaint();
    }

    private static void onUi(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }
}
