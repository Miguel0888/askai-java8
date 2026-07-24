package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.service.AskAiService;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;

/**
 * The Setup view's model-search container: two clearly separated sources in tabs — HuggingFace
 * (search, analyze, convert/import) and the Ollama Library (scrape ollama.com, pick a tag, pull it
 * on the remote server). The two result lists are never mixed because their install actions differ.
 * The HuggingFace tab is the unchanged {@link OllamaInstallPanel}.
 */
public final class ModelSearchPanel extends JPanel {

    public ModelSearchPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        super(new BorderLayout());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Hugging Face", new OllamaInstallPanel(configurationRepository, askAiService));
        tabs.addTab("Ollama Library", new OllamaLibraryPanel(askAiService));
        add(tabs, BorderLayout.CENTER);
    }
}
