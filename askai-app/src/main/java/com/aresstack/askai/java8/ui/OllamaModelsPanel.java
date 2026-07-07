package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.service.AskAiService;
import io.github.ollama4j.models.Model;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;

public final class OllamaModelsPanel extends JPanel {

    private final AskAiService askAiService;
    private final DefaultListModel<String> models;
    private final JTextArea detailsArea;

    public OllamaModelsPanel(AskAiService askAiService) {
        this.askAiService = askAiService;
        this.models = new DefaultListModel<String>();
        this.detailsArea = new JTextArea(8, 80);
        buildUserInterface();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JList<String> list = new JList<String>(models);
        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Installed Ollama models"));
        add(scrollPane, BorderLayout.CENTER);
        detailsArea.setEditable(false);
        add(new JScrollPane(detailsArea), BorderLayout.SOUTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(event -> onShown());
        buttons.add(refreshButton);
        add(buttons, BorderLayout.NORTH);
    }

    public void onShown() {
        detailsArea.setText("Loading installed models ...\n");
        askAiService.listModels(new AskAiService.ModelListListener() {
            public void onModels(final List<Model> result) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        models.clear();
                        for (Model model : result) {
                            models.addElement(model.getName());
                        }
                        detailsArea.append("Loaded " + result.size() + " model(s).\n");
                    }
                });
            }

            public void onError(final Exception ex) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        detailsArea.append("ERROR: " + ex.getMessage() + "\n");
                    }
                });
            }
        });
    }
}
