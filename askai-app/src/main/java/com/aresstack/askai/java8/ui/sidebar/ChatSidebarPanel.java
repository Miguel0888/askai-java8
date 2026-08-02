package com.aresstack.askai.java8.ui.sidebar;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * The chat sidebar (drawer) opened by the hamburger button in the top-left of the chat area. It hosts
 * a fixed default tab (the app's "Chats" list) plus any {@link ChatSidebarTab} contributions supplied
 * at open time — the seam through which plugins (e.g. Research) will add their own tabs later.
 *
 * <p>The header offers a PIN toggle: pinned, the drawer stays open next to the transcript; unpinned,
 * the owner closes it again after the user picks a chat or clicks into the transcript. The panel
 * itself is dumb about that policy — it only exposes {@link #isPinned()} and a close callback.</p>
 */
public final class ChatSidebarPanel extends JPanel {

    private final JTabbedPane tabs = new JTabbedPane();
    private final JToggleButton pinToggle = new JToggleButton("Pin");
    private final String defaultTabTitle;
    private final JComponent defaultTabComponent;

    private Supplier<List<ChatSidebarTab>> extraTabsSupplier;
    private Runnable closeHandler;

    public ChatSidebarPanel(String defaultTabTitle, JComponent defaultTabComponent) {
        super(new BorderLayout());
        this.defaultTabTitle = defaultTabTitle;
        this.defaultTabComponent = defaultTabComponent;
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, getBackground().darker()));
        add(buildHeader(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        rebuildTabs();
        setPreferredSize(new Dimension(280, 10));
    }

    /** Plugins' tabs arrive lazily through this supplier; re-read every time the drawer opens. */
    public void setExtraTabsSupplier(Supplier<List<ChatSidebarTab>> supplier) {
        this.extraTabsSupplier = supplier;
    }

    public void setCloseHandler(Runnable closeHandler) {
        this.closeHandler = closeHandler;
    }

    /** True while the user keeps the drawer pinned open. */
    public boolean isPinned() {
        return pinToggle.isSelected();
    }

    /** Rebuild the tab set: the default tab first, then the current contributions. */
    public void rebuildTabs() {
        int selected = tabs.getSelectedIndex();
        tabs.removeAll();
        tabs.addTab(defaultTabTitle, defaultTabComponent);
        List<ChatSidebarTab> extras = extraTabsSupplier == null
                ? Collections.<ChatSidebarTab>emptyList() : extraTabsSupplier.get();
        if (extras != null) {
            for (ChatSidebarTab tab : extras) {
                if (tab != null) {
                    tabs.addTab(tab.getTitle(), tab.getComponent());
                }
            }
        }
        if (selected >= 0 && selected < tabs.getTabCount()) {
            tabs.setSelectedIndex(selected);
        }
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        JLabel title = new JLabel("Menu");
        header.add(title, BorderLayout.WEST);

        pinToggle.setToolTipText("Keep the sidebar open (otherwise it closes after use)");
        pinToggle.setFocusable(false);
        pinToggle.setMargin(new java.awt.Insets(2, 6, 2, 6));

        JButton close = new JButton("✕");
        close.setToolTipText("Close the sidebar");
        close.setFocusable(false);
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.addActionListener(event -> {
            if (closeHandler != null) {
                closeHandler.run();
            }
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        right.add(pinToggle);
        right.add(close);
        header.add(right, BorderLayout.EAST);
        return header;
    }
}
