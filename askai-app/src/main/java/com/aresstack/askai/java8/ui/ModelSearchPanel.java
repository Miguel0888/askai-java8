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

    private final JTabbedPane tabs;
    private final OllamaInstallPanel huggingFacePanel;

    public ModelSearchPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        super(new BorderLayout());
        this.huggingFacePanel = new OllamaInstallPanel(configurationRepository, askAiService);
        this.tabs = new JTabbedPane();
        tabs.addTab("Hugging Face", huggingFacePanel);
        tabs.addTab("Ollama Library", new OllamaLibraryPanel(askAiService));
        add(tabs, BorderLayout.CENTER);
    }

    /** Selects the Hugging Face tab and runs a search for {@code query} (used to find model add-ons). */
    public void openHuggingFaceSearch(String query) {
        tabs.setSelectedComponent(huggingFacePanel);
        huggingFacePanel.searchFor(query);
    }

    /**
     * Selects the Hugging Face tab and enters add-on mode for {@code existingModelName}: the chosen encoder
     * GGUF is attached to that already-installed model via {@code from}/{@code adapters}, not installed anew.
     */
    public void openHuggingFaceAddOnSearch(String existingModelName, String query) {
        tabs.setSelectedComponent(huggingFacePanel);
        huggingFacePanel.openHuggingFaceAddOnSearch(existingModelName, query);
    }
}
