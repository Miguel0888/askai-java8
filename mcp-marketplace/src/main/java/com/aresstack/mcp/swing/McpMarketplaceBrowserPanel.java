package com.aresstack.mcp.swing;

import com.aresstack.mcp.marketplace.McpInstallOption;
import com.aresstack.mcp.marketplace.McpMarketplaceEntry;
import com.aresstack.mcp.marketplace.McpMarketplaceService;
import com.aresstack.mcp.marketplace.McpMarketplaceSource;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.RoundRectangle2D;
import java.util.Collections;
import java.util.List;

/** Browse MCP marketplace sources in a reusable Swing component. */
public final class McpMarketplaceBrowserPanel extends JPanel {

    public interface InstallationSelectionListener {
        void install(McpMarketplaceEntry entry, McpInstallOption option);
    }

    private static final Color PRIMARY = new Color(0x2979FF);
    private static final Color SURFACE = new Color(0xF5F6F7);
    private static final Color BORDER = new Color(0xC4C8CE);
    private static final Color MUTED = new Color(0x6F757D);

    private final McpMarketplaceService marketplaceService;
    private final InstallationSelectionListener installationListener;
    private final RoundedSearchField searchField;
    private final JComboBox<SourceChoice> sourceBox;
    private final DefaultListModel<McpMarketplaceEntry> resultModel;
    private final JList<McpMarketplaceEntry> resultList;
    private final JLabel titleLabel;
    private final JTextArea descriptionArea;
    private final JLabel metadataLabel;
    private final JComboBox<McpInstallOption> installOptionBox;
    private final JButton installButton;
    private final JButton refreshButton;
    private final JLabel statusLabel;

    public McpMarketplaceBrowserPanel(McpMarketplaceService marketplaceService,
                                      InstallationSelectionListener installationListener) {
        if (marketplaceService == null) {
            throw new IllegalArgumentException("marketplaceService must not be null");
        }
        this.marketplaceService = marketplaceService;
        this.installationListener = installationListener;
        this.searchField = new RoundedSearchField();
        this.sourceBox = new JComboBox<>();
        this.resultModel = new DefaultListModel<>();
        this.resultList = new JList<>(resultModel);
        this.titleLabel = new JLabel("Select an MCP server");
        this.descriptionArea = createDescriptionArea();
        this.metadataLabel = new JLabel(" ");
        this.installOptionBox = new JComboBox<>();
        this.installButton = createPrimaryButton("Use configuration");
        this.refreshButton = new JButton("Refresh");
        this.statusLabel = new JLabel(" ");

        buildUi();
        populateSources();
        wireActions();
        search(false);
    }

    private void buildUi() {
        setLayout(new BorderLayout(12, 12));
        setBorder(new EmptyBorder(14, 14, 14, 14));
        setPreferredSize(new Dimension(980, 640));

        add(buildToolbar(), BorderLayout.NORTH);

        JScrollPane listScroll = new JScrollPane(resultList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setCellRenderer(new EntryCellRenderer());
        resultList.setFixedCellHeight(88);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, buildDetailsPanel());
        splitPane.setResizeWeight(0.52);
        splitPane.setDividerLocation(500);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        add(splitPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    private JComponent buildToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setOpaque(false);
        searchField.setPreferredSize(new Dimension(520, 42));
        toolbar.add(searchField, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        sourceBox.setPreferredSize(new Dimension(220, 34));
        actions.add(sourceBox);
        actions.add(refreshButton);
        toolbar.add(actions, BorderLayout.EAST);
        return toolbar;
    }

    private JComponent buildDetailsPanel() {
        RoundedPanel details = new RoundedPanel();
        details.setLayout(new BorderLayout(10, 10));
        details.setBorder(new EmptyBorder(20, 20, 20, 20));

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 19f));
        details.add(titleLabel, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setOpaque(false);
        center.add(descriptionArea, BorderLayout.CENTER);
        metadataLabel.setForeground(MUTED);
        center.add(metadataLabel, BorderLayout.SOUTH);
        details.add(center, BorderLayout.CENTER);

        JPanel footer = new JPanel(new GridLayout(0, 1, 0, 8));
        footer.setOpaque(false);
        footer.add(new JLabel("Installation configuration"));
        footer.add(installOptionBox);
        footer.add(installButton);
        details.add(footer, BorderLayout.SOUTH);
        showSelection(null);
        return details;
    }

    private void populateSources() {
        DefaultComboBoxModel<SourceChoice> model = new DefaultComboBoxModel<>();
        model.addElement(new SourceChoice("all", "All sources"));
        for (McpMarketplaceSource source : marketplaceService.getSources()) {
            model.addElement(new SourceChoice(source.getId(), source.getName()));
        }
        sourceBox.setModel(model);
    }

    private void wireActions() {
        searchField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                search(false);
            }
        });
        sourceBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                search(false);
            }
        });
        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                search(true);
            }
        });
        resultList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showSelection(resultList.getSelectedValue());
            }
        });
        installButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                notifyInstallationSelection();
            }
        });
    }

    private void search(final boolean forceRefresh) {
        final SourceChoice selectedSource = (SourceChoice) sourceBox.getSelectedItem();
        final String sourceId = selectedSource != null ? selectedSource.id : "all";
        final String query = searchField.getText();
        setBusy(true, "Loading marketplace entries…");

        new SwingWorker<List<McpMarketplaceEntry>, Void>() {
            @Override
            protected List<McpMarketplaceEntry> doInBackground() {
                return marketplaceService.search(query, sourceId, forceRefresh);
            }

            @Override
            protected void done() {
                try {
                    replaceResults(get());
                } catch (Exception exception) {
                    replaceResults(Collections.<McpMarketplaceEntry>emptyList());
                    statusLabel.setText("Marketplace loading failed: " + exception.getMessage());
                } finally {
                    setBusy(false, null);
                }
            }
        }.execute();
    }

    private void replaceResults(List<McpMarketplaceEntry> entries) {
        resultModel.clear();
        for (McpMarketplaceEntry entry : entries) {
            resultModel.addElement(entry);
        }
        statusLabel.setText(entries.size() + " MCP server(s)");
        if (!entries.isEmpty()) {
            resultList.setSelectedIndex(0);
        } else {
            showSelection(null);
        }
    }

    private void showSelection(McpMarketplaceEntry entry) {
        installOptionBox.removeAllItems();
        if (entry == null) {
            titleLabel.setText("Select an MCP server");
            descriptionArea.setText("Search the configured registries and inspect normalized installation candidates.");
            metadataLabel.setText(" ");
            installButton.setEnabled(false);
            return;
        }
        titleLabel.setText(value(entry.getDisplayName(), entry.getName()));
        descriptionArea.setText(value(entry.getDescription(), "No description available."));
        metadataLabel.setText(entry.getSourceName() + "  •  "
                + (entry.isOfficial() ? "official" : "community") + "  •  "
                + entry.getTags().toString());
        for (McpInstallOption option : entry.getInstallOptions()) {
            installOptionBox.addItem(option);
        }
        installOptionBox.setRenderer(new InstallOptionRenderer());
        installButton.setEnabled(entry.isInstallable() && installationListener != null);
    }

    private void notifyInstallationSelection() {
        McpMarketplaceEntry entry = resultList.getSelectedValue();
        McpInstallOption option = (McpInstallOption) installOptionBox.getSelectedItem();
        if (entry != null && option != null && installationListener != null) {
            installationListener.install(entry, option);
        }
    }

    private void setBusy(boolean busy, String status) {
        searchField.setEnabled(!busy);
        sourceBox.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        resultList.setEnabled(!busy);
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        if (status != null) {
            statusLabel.setText(status);
        }
    }

    private static JTextArea createDescriptionArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(area.getFont().deriveFont(14f));
        return area;
    }

    private static JButton createPrimaryButton(String text) {
        JButton button = new JButton(text);
        button.setForeground(Color.WHITE);
        button.setBackground(PRIMARY);
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(9, 14, 9, 14));
        return button;
    }

    private static String value(String preferred, String fallback) {
        return preferred != null && !preferred.trim().isEmpty() ? preferred : fallback;
    }

    private static final class SourceChoice {
        private final String id;
        private final String name;

        private SourceChoice(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class EntryCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
            McpMarketplaceEntry entry = (McpMarketplaceEntry) value;
            String title = McpMarketplaceBrowserPanel.value(entry.getDisplayName(), entry.getName());
            String description = McpMarketplaceBrowserPanel.value(entry.getDescription(), "No description");
            label.setText("<html><b>" + escape(title) + "</b><br><span style='color:#70757d'>"
                    + escape(trim(description, 110)) + "</span><br><small>"
                    + escape(entry.getSourceName()) + (entry.isOfficial() ? " · official" : "")
                    + "</small></html>");
            label.setBorder(new EmptyBorder(8, 10, 8, 10));
            return label;
        }
    }

    private static final class InstallOptionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof McpInstallOption) {
                McpInstallOption option = (McpInstallOption) value;
                label.setText(option.getLabel() + "  —  " + option.getType());
            }
            return label;
        }
    }

    private static final class RoundedSearchField extends JTextField {
        private RoundedSearchField() {
            setOpaque(false);
            setBorder(new EmptyBorder(8, 16, 8, 16));
            setToolTipText("Search MCP servers");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setColor(hasFocus() ? Color.WHITE : SURFACE);
            copy.fill(new RoundRectangle2D.Double(1, 1, getWidth() - 3, getHeight() - 3, 18, 18));
            copy.setColor(hasFocus() ? PRIMARY : BORDER);
            copy.setStroke(new BasicStroke(hasFocus() ? 1.7f : 1f));
            copy.draw(new RoundRectangle2D.Double(1, 1, getWidth() - 3, getHeight() - 3, 18, 18));
            copy.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class RoundedPanel extends JPanel {
        private RoundedPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setColor(SURFACE);
            copy.fill(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 20, 20));
            copy.setColor(BORDER);
            copy.draw(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 20, 20));
            copy.dispose();
            super.paintComponent(graphics);
        }
    }

    private static String trim(String text, int maximumLength) {
        return text.length() <= maximumLength ? text : text.substring(0, maximumLength - 1) + "…";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
