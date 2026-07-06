package com.aresstack.askai.java8;

import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.service.DefaultAskAiService;
import com.aresstack.askai.java8.ui.AskAiFrame;

import javax.swing.SwingUtilities;

public final class AskAiJava8App {

    private AskAiJava8App() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                AppConfigurationRepository configurationRepository = new AppConfigurationRepository();
                DefaultAskAiService askAiService = new DefaultAskAiService(configurationRepository);
                AskAiFrame frame = new AskAiFrame(configurationRepository, askAiService);
                frame.showFrame();
            }
        });
    }
}
