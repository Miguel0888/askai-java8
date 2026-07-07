package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.service.AskAiService;

import javax.swing.Box;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public final class AskAiFrame {

    private static final String CHAT_VIEW = "chat";
    private static final String MODELS_VIEW = "models";
    private static final String ACTIONS_VIEW = "actions";
    private static final String INSTALL_VIEW = "install";
    private static final String CONNECTIONS_VIEW = "connections";
    private static final String NETWORK_VIEW = "network";
    private static final String ABOUT_VIEW = "about";

    private final AppConfigurationRepository configurationRepository;
    private final AskAiService askAiService;
    private final JFrame frame;
    private final JLabel connectionStatusLabel;
    private final CardLayout contentLayout;
    private final JPanel contentPanel;
    private final OllamaChatPanel chatPanel;
    private final OllamaModelsPanel modelsPanel;
    private final OllamaConfigPanel configPanel;

    public AskAiFrame(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        this.frame = new JFrame("AskAI");
        this.connectionStatusLabel = new JLabel();
        this.contentLayout = new CardLayout();
        this.contentPanel = new JPanel(contentLayout);
        this.chatPanel = new OllamaChatPanel(configurationRepository, askAiService);
        this.modelsPanel = new OllamaModelsPanel(askAiService);
        this.configPanel = new OllamaConfigPanel(configurationRepository);
    }

    public void showFrame() {
        configureFrame();
        showScreen(CHAT_VIEW);
        frame.setVisible(true);
    }

    private void configureFrame() {
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setJMenuBar(createMenuBar());
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(createContentPanel(), BorderLayout.CENTER);
        frame.setSize(1180, 820);
        frame.setMinimumSize(new Dimension(980, 680));
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent event) {
                askAiService.shutdown();
            }
        });
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        menuBar.add(createTopLevelMenu("Chat", CHAT_VIEW));
        menuBar.add(createTopLevelMenu("Models", MODELS_VIEW));
        menuBar.add(createTopLevelMenu("Actions", ACTIONS_VIEW));
        menuBar.add(createConfigurationMenu());
        menuBar.add(createHelpMenu());
        menuBar.add(Box.createHorizontalGlue());
        menuBar.add(connectionStatusLabel);
        refreshConnectionStatus(CHAT_VIEW);
        return menuBar;
    }

    private JMenu createTopLevelMenu(String title, String screenName) {
        final JMenu menu = new JMenu(title);
        menu.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) {
                showScreen(screenName);
                menu.setSelected(false);
            }
        });
        return menu;
    }

    private JMenu createConfigurationMenu() {
        JMenu configurationMenu = new JMenu("Configuration");
        configurationMenu.add(createScreenItem("Install", INSTALL_VIEW));
        configurationMenu.add(createScreenItem("Connections", CONNECTIONS_VIEW));
        configurationMenu.add(createScreenItem("Network", NETWORK_VIEW));
        return configurationMenu;
    }

    private JMenu createHelpMenu() {
        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(createScreenItem("About", ABOUT_VIEW));
        return helpMenu;
    }

    private JMenuItem createScreenItem(String title, String screenName) {
        JMenuItem item = new JMenuItem(title);
        item.addActionListener(event -> showScreen(screenName));
        return item;
    }

    private JPanel createContentPanel() {
        contentPanel.add(chatPanel, CHAT_VIEW);
        contentPanel.add(modelsPanel, MODELS_VIEW);
        contentPanel.add(new OllamaActionsPanel(askAiService), ACTIONS_VIEW);
        contentPanel.add(new OllamaInstallPanel(configurationRepository, askAiService), INSTALL_VIEW);
        contentPanel.add(configPanel, CONNECTIONS_VIEW);
        contentPanel.add(new ProxyPanel(configurationRepository), NETWORK_VIEW);
        contentPanel.add(new OllamaAboutPanel(), ABOUT_VIEW);
        return contentPanel;
    }

    private void showScreen(String screenName) {
        contentLayout.show(contentPanel, screenName);
        if (CHAT_VIEW.equals(screenName)) {
            chatPanel.onShown();
        }
        if (MODELS_VIEW.equals(screenName)) {
            modelsPanel.onShown();
        }
        if (CONNECTIONS_VIEW.equals(screenName)) {
            configPanel.load();
        }
        refreshConnectionStatus(screenName);
    }

    private void refreshConnectionStatus(String screenName) {
        connectionStatusLabel.setText("Ollama - " + configurationRepository.load().getOllamaBaseUrl() + " - " + resolveScreenTitle(screenName));
    }

    private String resolveScreenTitle(String screenName) {
        if (CHAT_VIEW.equals(screenName)) {
            return "Chat";
        }
        if (MODELS_VIEW.equals(screenName)) {
            return "Models";
        }
        if (ACTIONS_VIEW.equals(screenName)) {
            return "Actions";
        }
        if (INSTALL_VIEW.equals(screenName)) {
            return "Install";
        }
        if (CONNECTIONS_VIEW.equals(screenName)) {
            return "Connections";
        }
        if (NETWORK_VIEW.equals(screenName)) {
            return "Network";
        }
        if (ABOUT_VIEW.equals(screenName)) {
            return "About";
        }
        return "AskAI";
    }
}
