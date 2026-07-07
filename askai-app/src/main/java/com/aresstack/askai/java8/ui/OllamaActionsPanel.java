package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.service.AskAiService;
import io.github.ollama4j.models.PullProgress;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

public final class OllamaActionsPanel extends JPanel {
    private final AskAiService askAiService;
    private final JTextField modelField;
    private final JTextArea logArea;

    public OllamaActionsPanel(AskAiService askAiService) {
        this.askAiService = askAiService;
        this.modelField = new JTextField(28);
        this.logArea = new JTextArea(14, 80);
        buildUserInterface();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.add(new JLabel("Ollama model"));
        toolbar.add(modelField);
        JButton pullButton = new JButton("Pull model");
        pullButton.addActionListener(event -> pullModel());
        toolbar.add(pullButton);
        add(toolbar, BorderLayout.NORTH);
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);
    }

    private void pullModel() {
        final String modelName = modelField.getText().trim();
        if (modelName.length() == 0) {
            append("Enter a model name, e.g. qwen2.5-coder:0.5b.");
            return;
        }
        append("Pulling " + modelName + " ...");
        askAiService.pullOllamaModel(modelName, new AskAiService.PullListener() {
            public void onProgress(final PullProgress progress) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        append(progress.hasTotal() ? progress.getStatus() + " " + progress.getPercent() + "%" : progress.getStatus());
                    }
                });
            }

            public void onComplete(final String message) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        append(message);
                    }
                });
            }

            public void onError(final Exception ex) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        append("ERROR: " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void append(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
