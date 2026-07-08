package com.aresstack.askai.java8.ui;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;

/**
 * Documents the intent and boundaries of the spike.
 */
public final class OllamaAboutPanel extends JPanel {

    public OllamaAboutPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JTextArea textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setText("AskAI\n\n"
                + "Goal:\n"
                + "  Reuse the tested DirectML Workbench download path, then upload local model files "
                + "as Ollama blobs and create a remote Ollama model.\n\n"
                + "Recommended spike:\n"
                + "  Gemma 3 270M IT SafeTensors -> Remote Ollama -> Chat tab.\n\n"
                + "Important boundary:\n"
                + "  Qwen SafeTensors is included only as an experimental/negative path. "
                + "The cleaner Qwen route should be GGUF later.\n\n"
                + "Ollama server:\n"
                + "  Configure Base URL in the Config tab, e.g. http://10.126.26.41:11434.\n");
        add(new JScrollPane(textArea), BorderLayout.CENTER);
    }
}
