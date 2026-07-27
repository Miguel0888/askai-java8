package com.aresstack.mcp.swing;

import com.aresstack.mcp.marketplace.McpInstallOption;
import com.aresstack.mcp.marketplace.McpMarketplaceEntry;
import com.aresstack.mcp.marketplace.McpMarketplaceService;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;

/** Launch the standalone Swing browser. */
public final class McpMarketplaceDemo {
    private McpMarketplaceDemo() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                installSystemLookAndFeel();
                showBrowser();
            }
        });
    }

    private static void showBrowser() {
        JFrame frame = new JFrame("MCP Marketplace");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(new McpMarketplaceBrowserPanel(new McpMarketplaceService(),
                new McpMarketplaceBrowserPanel.InstallationSelectionListener() {
                    @Override
                    public void install(McpMarketplaceEntry entry, McpInstallOption option) {
                        JOptionPane.showMessageDialog(frame, describe(option),
                                "Selected: " + entry.getDisplayName(), JOptionPane.INFORMATION_MESSAGE);
                    }
                }), BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static String describe(McpInstallOption option) {
        if (option.getUrl() != null) {
            return option.getType() + " " + option.getUrl();
        }
        return option.getCommand() + " " + option.getArgs();
    }

    private static void installSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep the cross-platform Swing look and feel when the system look cannot be loaded.
        }
    }
}
