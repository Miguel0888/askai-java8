package com.aresstack.askai.java8.ui.chat;

import com.aresstack.askai.java8.history.ChatHistoryStore;
import com.aresstack.askai.java8.history.ChatMessageRecord;
import com.aresstack.askai.java8.history.ChatRecord;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The chat list's project grouping and search (issue #36 line): chats carry an optional PROJECT,
 * project groups render at the TOP of the drawer list, and the header search bar filters every
 * section live by title and project name.
 */
public class ChatListProjectsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ChatHistoryStore store;

    private ChatWorkspacePanel build() throws Exception {
        store = new ChatHistoryStore(folder.newFolder("chats"));
        saveChat("Oldtimer Preise", "Autos");
        saveChat("Kaufberatung W124", "Autos");
        saveChat("Rezept Lasagne", null);
        return new ChatWorkspacePanel(new ChatWorkspacePanel.ChatSessionFactory() {
            public ChatSessionComponent create(ChatSessionId id) {
                return new FakeSession(id);
            }
        }, null, store);
    }

    private void saveChat(String title, String project) {
        ChatRecord record = new ChatRecord(UUID.randomUUID().toString(), System.currentTimeMillis());
        record.setTitle(title);
        record.setProject(project);
        record.getMessages().add(new ChatMessageRecord(null, ChatMessageRecord.ROLE_USER,
                "hi", System.currentTimeMillis(), null, null));
        store.save(record);
    }

    @Test
    public void projectPersistsThroughTheStoreRoundTrip() throws Exception {
        store = new ChatHistoryStore(folder.newFolder("roundtrip"));
        saveChat("Oldtimer Preise", "Autos");
        ChatRecord reloaded = store.list().get(0);
        assertEquals("Autos", reloaded.getProject());

        reloaded.setProject("   "); // blank clears the assignment
        assertEquals(null, reloaded.getProject());
    }

    @Test
    public void projectGroupsRenderBetweenActiveAndTheTimeGroupedHistory() throws Exception {
        final ChatWorkspacePanel workspace = build();
        onEdt(new Runnable() {
            public void run() {
                List<String> entries = workspace.chatListEntriesForTest();
                int activeHeader = indexContaining(entries, "AKTIV");
                int projectHeader = indexContaining(entries, "Autos");
                int projectChat = indexContaining(entries, "Oldtimer Preise");
                int timeHeader = indexContaining(entries, "HEUTE"); // records were saved just now
                int looseChat = indexContaining(entries, "Rezept Lasagne");
                assertTrue("open sessions render under AKTIV first", activeHeader >= 0);
                assertTrue("the project heading exists", projectHeader >= 0);
                assertTrue("project chats sit under their heading, before the history groups",
                        activeHeader < projectHeader && projectHeader < projectChat
                                && projectChat < timeHeader);
                assertTrue("unassigned saved chats land in their time group", timeHeader < looseChat);
            }
        });
    }

    @Test
    public void theSearchBarFiltersByTitleAndProjectName() throws Exception {
        final ChatWorkspacePanel workspace = build();
        onEdt(new Runnable() {
            public void run() {
                workspace.chatFilterForTest().setText("lasagne");
                List<String> byTitle = workspace.chatListEntriesForTest();
                assertTrue(indexContaining(byTitle, "Rezept Lasagne") >= 0);
                assertFalse("non-matching project chats are hidden",
                        indexContaining(byTitle, "Oldtimer Preise") >= 0);

                workspace.chatFilterForTest().setText("autos");
                List<String> byProject = workspace.chatListEntriesForTest();
                assertTrue("the project NAME matches every chat in the project",
                        indexContaining(byProject, "Oldtimer Preise") >= 0
                                && indexContaining(byProject, "Kaufberatung W124") >= 0);
                assertFalse(indexContaining(byProject, "Rezept Lasagne") >= 0);

                workspace.chatFilterForTest().setText("zzz-nothing");
                assertTrue(indexContaining(workspace.chatListEntriesForTest(),
                        "No matching chats") >= 0);
            }
        });
    }

    private static int indexContaining(List<String> entries, String needle) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) != null && entries.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    // --- helpers ---

    private static final class FakeSession implements ChatSessionComponent {
        private final ChatSessionId id;
        private final Component component = new javax.swing.JPanel();

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
