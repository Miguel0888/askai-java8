package com.aresstack.askai.java8;

import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.service.DefaultAskAiService;
import com.aresstack.askai.java8.ui.AskAiFrame;

import javax.swing.SwingUtilities;

public final class AskAiJava8App {

    private AskAiJava8App() {
    }

    public static void main(String[] args) {
        final AppConfigurationRepository configurationRepository = new AppConfigurationRepository();
        // Must be set before any networking (java.net.InetAddress reads it once, at class init).
        if (configurationRepository.load().getHttpClientConfiguration().isPreferIpv6()) {
            System.setProperty("java.net.preferIPv6Addresses", "true");
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                DefaultAskAiService askAiService = new DefaultAskAiService(configurationRepository);
                AskAiFrame frame = new AskAiFrame(configurationRepository, askAiService);
                frame.showFrame();
            }
        });
    }
}
