package com.aresstack.askai.java8.ui.chat;

import com.aresstack.askai.java8.ui.PlusIcon;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hosts independent chat sessions in tabs. The last tab is a fixed, disabled placeholder whose tab
 * component is an icon-only "＋" button (MainframeMate pattern): the button — not the tab selection —
 * creates a new chat, which is always inserted immediately before the plus tab. Each chat carries a stable
 * {@link ChatSessionId}; tabs are mapped to sessions by that id, never by tab index (indices shift on
 * close). At least one chat stays open.
 */
public final class ChatWorkspacePanel extends JPanel {

    /** Builds the per-tab chat session for a freshly created id (kept out of the host for testability). */
    public interface ChatSessionFactory {
        ChatSessionComponent create(ChatSessionId id);
    }

    private final JTabbedPane tabs = new JTabbedPane();
    private final ChatSessionFactory factory;
    private final Map<ChatSessionId, ChatSessionComponent> sessionsById =
            new LinkedHashMap<ChatSessionId, ChatSessionComponent>();

    public ChatWorkspacePanel(ChatSessionFactory factory) {
        super(new BorderLayout());
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        this.factory = factory;
        add(tabs, BorderLayout.CENTER);
        addPlusTab();
        tabs.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent event) {
                keepSelectionOffPlusTab();
            }
        });
        openNewChat(); // never start empty
    }

    /** Create a new chat, insert its tab before the plus tab and select it. */
    public ChatSessionComponent openNewChat() {
        ChatSessionId id = ChatSessionId.create();
        ChatSessionComponent session = factory.create(id);
        int insertIndex = Math.max(tabs.getTabCount() - 1, 0); // just before the (always last) plus tab
        tabs.insertTab(null, null, session.getComponent(), id.toString(), insertIndex);
        tabs.setTabComponentAt(insertIndex, createChatTabHeader(id));
        sessionsById.put(id, session);
        tabs.setSelectedIndex(insertIndex);
        return session;
    }

    /** Close the chat with this id: abort its work, remove its tab, and never leave the workspace empty. */
    public void closeSession(ChatSessionId id) {
        ChatSessionComponent session = sessionsById.remove(id);
        if (session == null) {
            return;
        }
        int index = tabs.indexOfComponent(session.getComponent());
        try {
            session.disposeSession();
        } finally {
            if (index >= 0) {
                tabs.removeTabAt(index);
            }
        }
        if (sessionsById.isEmpty()) {
            openNewChat();
        } else {
            keepSelectionOffPlusTab();
        }
    }

    /** @return the currently selected chat session (never the plus tab), or null if none. */
    public ChatSessionComponent activeSession() {
        Component selected = tabs.getSelectedComponent();
        for (ChatSessionComponent session : sessionsById.values()) {
            if (session.getComponent() == selected) {
                return session;
            }
        }
        return null;
    }

    /** @return a snapshot of all open sessions (e.g. for a global catalog refresh or shutdown). */
    public List<ChatSessionComponent> sessions() {
        return new ArrayList<ChatSessionComponent>(sessionsById.values());
    }

    // ------------------------------------------------------------------ internals

    private void addPlusTab() {
        JButton plus = new JButton(new PlusIcon(12));
        plus.setToolTipText("New chat");
        plus.setFocusable(false);
        plus.setContentAreaFilled(false);
        plus.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        plus.addActionListener(event -> openNewChat());
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        header.setOpaque(false);
        header.add(plus);

        tabs.addTab(null, new JPanel()); // inert placeholder content, never shown
        int last = tabs.getTabCount() - 1;
        tabs.setTabComponentAt(last, header);
        tabs.setEnabledAt(last, false); // the plus tab is never a selectable chat
    }

    private Component createChatTabHeader(final ChatSessionId id) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        header.setOpaque(false);
        JLabel label = new JLabel(id.shortLabel());
        label.setToolTipText(id.toString()); // the full UUID stays in the tooltip
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        header.add(label);

        JButton close = new JButton("×");
        close.setToolTipText("Close chat");
        close.setFocusable(false);
        close.setContentAreaFilled(false);
        close.setMargin(new Insets(0, 0, 0, 0));
        close.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        close.addActionListener(event -> closeSession(id)); // resolve by id, not by a captured index
        header.add(close);
        return header;
    }

    private void keepSelectionOffPlusTab() {
        int plusIndex = tabs.getTabCount() - 1;
        if (tabs.getSelectedIndex() == plusIndex && plusIndex > 0) {
            tabs.setSelectedIndex(plusIndex - 1);
        }
    }

    // ------------------------------------------------------------------ test accessors (package-private)

    JTabbedPane tabsForTest() {
        return tabs;
    }

    int openSessionCount() {
        return sessionsById.size();
    }

    boolean hasSession(ChatSessionId id) {
        return sessionsById.containsKey(id);
    }

    List<ChatSessionId> openSessionIds() {
        return new ArrayList<ChatSessionId>(sessionsById.keySet());
    }
}
