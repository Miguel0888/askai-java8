package com.aresstack.askai.java8.ui;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;

public final class OllamaAboutPanel extends JPanel {

    public OllamaAboutPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JTextArea textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setText("AskAI Java 8\n\nJava 8 compatible AskAI build with embedded ollama4j-java8.\n");
        add(new JScrollPane(textArea), BorderLayout.CENTER);
    }
}
