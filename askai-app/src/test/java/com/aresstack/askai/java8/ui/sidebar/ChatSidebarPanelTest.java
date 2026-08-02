package com.aresstack.askai.java8.ui.sidebar;

import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The drawer's tab composition: default "Chats" tab first, contributions appended on rebuild. */
public class ChatSidebarPanelTest {

    private static ChatSidebarTab tab(final String title) {
        return new ChatSidebarTab() {
            public String getTitle() {
                return title;
            }

            public JComponent getComponent() {
                return new JLabel(title);
            }
        };
    }

    private static JTabbedPane tabsOf(ChatSidebarPanel panel) {
        for (java.awt.Component child : panel.getComponents()) {
            if (child instanceof JTabbedPane) {
                return (JTabbedPane) child;
            }
        }
        throw new AssertionError("no JTabbedPane in the sidebar");
    }

    @Test
    public void theDefaultTabIsAlwaysFirst() {
        ChatSidebarPanel panel = new ChatSidebarPanel("Chats", new JPanel());
        JTabbedPane tabs = tabsOf(panel);
        assertEquals(1, tabs.getTabCount());
        assertEquals("Chats", tabs.getTitleAt(0));
    }

    @Test
    public void contributedTabsAppearAfterTheDefaultOnRebuild() {
        ChatSidebarPanel panel = new ChatSidebarPanel("Chats", new JPanel());
        final List<ChatSidebarTab> extras = Arrays.asList(tab("Research"), tab("Notes"));
        panel.setExtraTabsSupplier(() -> extras);
        panel.rebuildTabs();
        JTabbedPane tabs = tabsOf(panel);
        assertEquals(3, tabs.getTabCount());
        assertEquals("Chats", tabs.getTitleAt(0));
        assertEquals("Research", tabs.getTitleAt(1));
        assertEquals("Notes", tabs.getTitleAt(2));

        // A later rebuild with fewer contributions does not accumulate stale tabs.
        panel.setExtraTabsSupplier(() -> Arrays.asList(tab("Research")));
        panel.rebuildTabs();
        assertEquals(2, tabsOf(panel).getTabCount());
    }

    @Test
    public void theDrawerStartsUnpinned() {
        ChatSidebarPanel panel = new ChatSidebarPanel("Chats", new JPanel());
        assertFalse(panel.isPinned());
    }

    @Test
    public void aNullSupplierOrNullEntriesAreTolerated() {
        ChatSidebarPanel panel = new ChatSidebarPanel("Chats", new JPanel());
        panel.setExtraTabsSupplier(() -> Arrays.asList(tab("One"), null));
        panel.rebuildTabs();
        assertEquals(2, tabsOf(panel).getTabCount());
        panel.setExtraTabsSupplier(null);
        panel.rebuildTabs();
        assertEquals(1, tabsOf(panel).getTabCount());
        assertTrue(true);
    }
}
