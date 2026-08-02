package com.aresstack.askai.java8.ui.sidebar;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The chat sidebar (drawer). It hosts one PANE per tab — plain CardLayout panes, deliberately no
 * Swing {@code JTabbedPane}: switching happens through the Java2D {@link SidebarTabRibbon} that
 * unfolds next to the hamburger. The fixed default pane (the app's "Chats" list) comes first; any
 * {@link ChatSidebarTab} contributions supplied at open time follow — the seam through which plugins
 * (e.g. Research) add their own panes later.
 *
 * <p>The header shows the active pane's title and offers the pushpin toggle: pinned, the drawer
 * stays open next to the transcript; unpinned, the owner closes it when the mouse leaves. The panel
 * itself is dumb about that policy — it only exposes {@link #isPinned()} and a close callback.</p>
 */
public final class ChatSidebarPanel extends JPanel {

    private final CardLayout paneLayout = new CardLayout();
    private final JPanel panes = new JPanel(paneLayout);
    private final JLabel headerTitle = new JLabel();
    private final JToggleButton pinToggle =
            new JToggleButton(com.aresstack.askai.java8.ui.ChatComposerPanel.createPushPinIcon());
    private final String defaultTabTitle;
    private final JComponent defaultTabComponent;
    private final List<String> titles = new ArrayList<String>();

    private String activeTitle;
    private Supplier<List<ChatSidebarTab>> extraTabsSupplier;
    private Runnable closeHandler;

    public ChatSidebarPanel(String defaultTabTitle, JComponent defaultTabComponent) {
        super(new BorderLayout());
        this.defaultTabTitle = defaultTabTitle;
        this.defaultTabComponent = defaultTabComponent;
        this.activeTitle = defaultTabTitle;
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, getBackground().darker()));
        panes.setOpaque(false);
        add(buildHeader(), BorderLayout.NORTH);
        add(panes, BorderLayout.CENTER);
        rebuildTabs();
        setPreferredSize(new Dimension(280, 10));
    }

    /** Plugins' panes arrive lazily through this supplier; re-read every time the drawer opens. */
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

    /** The pane titles in order — the default pane first, then the current contributions. */
    public List<String> tabTitles() {
        return Collections.unmodifiableList(new ArrayList<String>(titles));
    }

    /** The title of the pane currently shown. */
    public String activeTab() {
        return activeTitle;
    }

    /** Show the pane with this title (no-op for unknown titles). */
    public void showTab(String title) {
        if (title == null || !titles.contains(title)) {
            return;
        }
        activeTitle = title;
        paneLayout.show(panes, title);
        headerTitle.setText(title);
    }

    /** Rebuild the pane set: the default pane first, then the current contributions. */
    public void rebuildTabs() {
        panes.removeAll();
        titles.clear();
        Map<String, JComponent> byTitle = new LinkedHashMap<String, JComponent>();
        byTitle.put(defaultTabTitle, defaultTabComponent);
        List<ChatSidebarTab> extras = extraTabsSupplier == null
                ? Collections.<ChatSidebarTab>emptyList() : extraTabsSupplier.get();
        if (extras != null) {
            for (ChatSidebarTab tab : extras) {
                if (tab != null && tab.getTitle() != null && !byTitle.containsKey(tab.getTitle())) {
                    byTitle.put(tab.getTitle(), tab.getComponent());
                }
            }
        }
        for (Map.Entry<String, JComponent> entry : byTitle.entrySet()) {
            titles.add(entry.getKey());
            panes.add(entry.getValue(), entry.getKey());
        }
        if (!titles.contains(activeTitle)) {
            activeTitle = defaultTabTitle; // a vanished contribution falls back to the default pane
        }
        showTab(activeTitle);
        panes.revalidate();
        panes.repaint();
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        headerTitle.setText(defaultTabTitle);
        header.add(headerTitle, BorderLayout.WEST);

        pinToggle.setToolTipText("Pin the sidebar open (otherwise it closes when the mouse leaves)");
        pinToggle.setFocusable(false);
        pinToggle.setMargin(new java.awt.Insets(2, 4, 2, 4));

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
