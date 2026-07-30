package com.aresstack.askai.java8.ui;

import com.aresstack.askai.agent.model.reranker.RerankerModelCatalog;
import com.aresstack.askai.java8.AskAiModel;
import com.aresstack.askai.java8.config.AiModelSelections;
import com.aresstack.askai.java8.service.VirtualOllamaContainerService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The central "AI models" settings screen: AskAI owns the reranker and embeddings model selections for
 * ALL plugins, so they are chosen here — not in any plugin's own settings. The MAIN (chat/generation)
 * model is set in the chat window and is only shown read-only here. Selections are model NAMES;
 * endpoints are resolved by the host when it hands a descriptor to a plugin.
 */
public final class AiModelsPanel extends JPanel {

    /** Combo entry for "no explicit selection". */
    private static final String NONE = "";

    private final AskAiModel model;
    private final RerankerModelCatalog rerankerCatalog; // nullable: no local runtime → no rerank models
    private final VirtualOllamaContainerService ollamaService;

    private final JLabel mainModelValue = new JLabel();
    private final JComboBox<String> rerankerCombo = new JComboBox<String>();
    private final JComboBox<String> embeddingsCombo = new JComboBox<String>();
    private final JLabel status = new JLabel(" ");

    public AiModelsPanel(AskAiModel model, RerankerModelCatalog rerankerCatalog,
                         VirtualOllamaContainerService ollamaService) {
        this.model = model;
        this.rerankerCatalog = rerankerCatalog;
        this.ollamaService = ollamaService;
        AiModelSelections selections = model.getAiModelSelections();
        // Seed each combo with the persisted selection so nothing is lost before the list loads.
        seedCombo(rerankerCombo, selections.getRerankerModel());
        seedCombo(embeddingsCombo, selections.getEmbeddingsModel());
        buildUserInterface();
        refreshMainModelLabel();
        reloadAvailableModels();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("AI models (managed centrally for all plugins)"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        addRow(form, constraints, 0, "Main model (set in the chat window)", mainModelValue);
        addRow(form, constraints, 1, "Reranker model", rerankerCombo);
        addRow(form, constraints, 2, "Embeddings model", embeddingsCombo);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(event -> save());
        JButton refreshButton = new JButton("Refresh models");
        refreshButton.addActionListener(event -> reloadAvailableModels());
        buttons.add(saveButton);
        buttons.add(refreshButton);

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(form, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(status, BorderLayout.SOUTH);
    }

    private void addRow(JPanel form, GridBagConstraints constraints, int row, String label, Component field) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0.0d;
        form.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        form.add(field, constraints);
    }

    private void refreshMainModelLabel() {
        String main = model.getAiModelSelections().getMainModel();
        mainModelValue.setText(main == null || main.trim().length() == 0
                ? "— (choose a model in the chat window)" : main);
    }

    /** Loads the installed rerank/embedding model names off the EDT, then repopulates on the EDT. */
    private void reloadAvailableModels() {
        status.setText("Loading installed models ...");
        new Thread(new Runnable() {
            public void run() {
                final List<String> rerankers = loadRerankModelNames();
                final List<String> embeddings = loadEmbeddingModelNames();
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        populate(rerankerCombo, rerankers);
                        populate(embeddingsCombo, embeddings);
                        status.setText(" ");
                    }
                });
            }
        }, "ai-models-refresh").start();
    }

    private List<String> loadRerankModelNames() {
        if (rerankerCatalog == null) {
            return Collections.emptyList();
        }
        try {
            return rerankerCatalog.listInstalledRerankModels();
        } catch (RuntimeException failure) {
            return Collections.emptyList();
        }
    }

    private List<String> loadEmbeddingModelNames() {
        try {
            return ollamaService.loadEmbeddingModelNamesNow();
        } catch (Exception failure) {
            return Collections.emptyList();
        }
    }

    /** Rebuilds a combo as NONE + the available names, preserving the currently selected value. */
    private void populate(JComboBox<String> combo, List<String> available) {
        String current = (String) combo.getSelectedItem();
        List<String> items = new ArrayList<String>();
        items.add(NONE);
        for (String name : available) {
            if (!items.contains(name)) {
                items.add(name);
            }
        }
        // Keep a persisted selection that is not (currently) installed so the user never loses it silently.
        if (current != null && current.length() > 0 && !items.contains(current)) {
            items.add(current);
        }
        combo.removeAllItems();
        for (String item : items) {
            combo.addItem(item);
        }
        combo.setSelectedItem(current == null ? NONE : current);
    }

    private void seedCombo(JComboBox<String> combo, String current) {
        combo.addItem(NONE);
        if (current != null && current.length() > 0) {
            combo.addItem(current);
            combo.setSelectedItem(current);
        } else {
            combo.setSelectedItem(NONE);
        }
    }

    private void save() {
        String reranker = (String) rerankerCombo.getSelectedItem();
        String embeddings = (String) embeddingsCombo.getSelectedItem();
        model.persistRerankerAndEmbeddingsModels(reranker, embeddings);
        status.setText("Saved.");
    }
}
