package com.aresstack.askai.java8.ui.chat;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Panel;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The workspace keeps a single disabled plus tab last, inserts chats before it, and closes by id. */
public class ChatWorkspacePanelTest {

    private final List<FakeSession> created = new ArrayList<FakeSession>();

    private ChatWorkspacePanel build() {
        created.clear();
        return new ChatWorkspacePanel(new ChatWorkspacePanel.ChatSessionFactory() {
            public ChatSessionComponent create(ChatSessionId id) {
                FakeSession session = new FakeSession(id);
                created.add(session);
                return session;
            }
        });
    }

    @Test
    public void startsWithOneChatAndADisabledLastPlusTab() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                assertEquals(1, workspace.openSessionCount());
                JTabbedPane tabs = workspace.tabsForTest();
                assertEquals("one chat + plus", 2, tabs.getTabCount());
                assertFalse("plus tab is disabled", tabs.isEnabledAt(tabs.getTabCount() - 1));
                assertTrue("chat tab is enabled", tabs.isEnabledAt(0));
            }
        });
    }

    @Test
    public void newChatsAreInsertedBeforeTheAlwaysLastPlusTab() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                workspace.openNewChat();
                workspace.openNewChat();
                JTabbedPane tabs = workspace.tabsForTest();
                assertEquals(3, workspace.openSessionCount());
                assertEquals(4, tabs.getTabCount());
                assertEquals("exactly one (disabled) plus tab", 1, disabledTabs(tabs));
                assertFalse(tabs.isEnabledAt(tabs.getTabCount() - 1));
            }
        });
    }

    @Test
    public void thePlusButtonCreatesExactlyOneChat() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                int before = workspace.openSessionCount();
                JTabbedPane tabs = workspace.tabsForTest();
                JButton plus = findButton((Container) tabs.getTabComponentAt(tabs.getTabCount() - 1));
                assertNotNull("plus button present", plus);
                plus.doClick();
                assertEquals(before + 1, workspace.openSessionCount());
            }
        });
    }

    @Test
    public void thePlusTabNeverBecomesTheActiveChat() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                workspace.openNewChat();
                JTabbedPane tabs = workspace.tabsForTest();
                int plusIndex = tabs.getTabCount() - 1;
                tabs.setSelectedIndex(plusIndex);
                assertNotEquals("selection moved off the plus tab", plusIndex, tabs.getSelectedIndex());
                assertNotNull("an active real chat session exists", workspace.activeSession());
            }
        });
    }

    @Test
    public void eachChatHasAUniqueIdSharedByTabAndSession() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                ChatSessionComponent second = workspace.openNewChat();
                List<ChatSessionId> ids = workspace.openSessionIds();
                assertEquals(2, ids.size());
                assertNotEquals(ids.get(0), ids.get(1));
                assertEquals("active session is the freshly opened one",
                        second.getSessionId(), workspace.activeSession().getSessionId());
            }
        });
    }

    @Test
    public void closingRemovesExactlyThatSessionAndAbortsItsWork() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                ChatSessionComponent a = workspace.openNewChat(); // there is already an initial one
                ChatSessionComponent b = workspace.openNewChat();
                ChatSessionComponent c = workspace.openNewChat();

                workspace.closeSession(b.getSessionId());

                assertFalse(workspace.hasSession(b.getSessionId()));
                assertTrue(workspace.hasSession(a.getSessionId()));
                assertTrue(workspace.hasSession(c.getSessionId()));
                assertTrue("closed session was disposed", ((FakeSession) b).disposed);
                assertFalse("other sessions untouched", ((FakeSession) a).disposed);
                assertFalse(((FakeSession) c).disposed);
            }
        });
    }

    @Test
    public void closingTheLastChatOpensAFreshOne() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                // close every currently-open session
                for (ChatSessionId id : workspace.openSessionIds()) {
                    workspace.closeSession(id);
                }
                assertEquals("never empty", 1, workspace.openSessionCount());
                assertFalse("the new chat has a fresh id", workspace.hasSession(created.get(0).id));
            }
        });
    }

    @Test
    public void closeIsResolvedByIdNotIndex() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                ChatSessionComponent a = workspace.openNewChat();
                ChatSessionComponent b = workspace.openNewChat();
                ChatSessionComponent c = workspace.openNewChat();
                workspace.closeSession(a.getSessionId()); // first-inserted, now not index 0-sensitive
                workspace.closeSession(c.getSessionId()); // last real chat
                assertTrue(workspace.hasSession(b.getSessionId()));
                assertFalse(workspace.hasSession(a.getSessionId()));
                assertFalse(workspace.hasSession(c.getSessionId()));
            }
        });
    }

    @Test
    public void theActiveSessionListenerFiresWithEachNewlySelectedTab() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                final List<ChatSessionId> notified = new ArrayList<ChatSessionId>();
                workspace.setActiveSessionListener(new ChatWorkspacePanel.ActiveSessionListener() {
                    public void activeSessionChanged(ChatSessionId id) {
                        notified.add(id);
                    }
                });
                // Registering fires immediately with the current selection.
                assertEquals(1, notified.size());
                ChatSessionId first = notified.get(0);

                ChatSessionComponent second = workspace.openNewChat(); // selecting it fires again
                assertEquals(second.getSessionId(), notified.get(notified.size() - 1));

                JTabbedPane tabs = workspace.tabsForTest();
                tabs.setSelectedIndex(0); // back to the first tab
                assertEquals(first, notified.get(notified.size() - 1));
            }
        });
    }

    @Test
    public void closingTheLastTabNotifiesTheFreshReplacementId() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                final List<ChatSessionId> notified = new ArrayList<ChatSessionId>();
                workspace.setActiveSessionListener(new ChatWorkspacePanel.ActiveSessionListener() {
                    public void activeSessionChanged(ChatSessionId id) {
                        notified.add(id);
                    }
                });
                ChatSessionId original = workspace.openSessionIds().get(0);

                for (ChatSessionId id : workspace.openSessionIds()) {
                    workspace.closeSession(id); // closing the last one opens a fresh replacement
                }

                ChatSessionId replacement = workspace.openSessionIds().get(0);
                assertNotEquals("the replacement tab has a new id", original, replacement);
                assertEquals("the listener was told about the fresh tab",
                        replacement, notified.get(notified.size() - 1));
            }
        });
    }

    // --- helpers ---

    private static int disabledTabs(JTabbedPane tabs) {
        int count = 0;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (!tabs.isEnabledAt(i)) {
                count++;
            }
        }
        return count;
    }

    private static JButton findButton(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton) {
                return (JButton) child;
            }
            if (child instanceof Container) {
                JButton nested = findButton((Container) child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static final class FakeSession implements ChatSessionComponent {
        private final ChatSessionId id;
        private final Component component = new javax.swing.JPanel();
        private boolean disposed;

        FakeSession(ChatSessionId id) {
            this.id = id;
        }

        public ChatSessionId getSessionId() {
            return id;
        }

        public Component getComponent() {
            return component;
        }

        public void disposeSession() {
            disposed = true;
        }
    }

    private static void onEdt(Runnable runnable) throws Exception {
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ex.getCause();
            }
            if (ex.getCause() instanceof Error) {
                throw (Error) ex.getCause();
            }
            throw ex;
        }
    }
}
