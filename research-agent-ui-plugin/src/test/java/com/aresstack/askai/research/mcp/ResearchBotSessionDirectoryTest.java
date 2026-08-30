package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.plugin.api.service.ChatSessionCatalog;
import com.aresstack.askai.plugin.api.service.ChatSessionMetadata;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The PUBLIC connector face is a multi-session directory, not a single attached gateway. This pins the
 * behaviour that the old "last attached session = current session" model got wrong: the implicit target is
 * the chat SELECTED in the UI (it follows a tab switch WITHOUT any session restart or re-attach), an explicit
 * sessionId reaches exactly that session no matter what is selected, and identical titles never merge.
 */
public class ResearchBotSessionDirectoryTest {

    /** A session gateway that records what it was asked and answers with its own name. */
    private static final class Gateway implements ResearchBotSessionGateway {
        private final String name;
        final java.util.List<String> invoked = new java.util.ArrayList<String>();

        Gateway(String name) {
            this.name = name;
        }

        public String execute(String command, String arguments) {
            invoked.add("execute(" + command + "|" + arguments + ")");
            return "handled by " + name;
        }

        public String describeState() {
            return "phase=scoping state=running session=" + name;
        }

        public String describeHistory(boolean raw) {
            return (raw ? "raw " : "summary ") + name;
        }
    }

    /** The host catalog: titles per chat id + the chat the user currently sees. */
    private static final class Catalog implements ChatSessionCatalog {
        private final Map<String, String> titles = new LinkedHashMap<String, String>();
        private String active = "";

        public String getActiveSessionId() {
            return active;
        }

        public ChatSessionMetadata getSession(String sessionId) {
            String title = titles.get(sessionId);
            return title == null ? null : new ChatSessionMetadata(sessionId, title, 0L);
        }
    }

    private final ResearchBotSessionDirectory directory = ResearchBotSessionDirectory.get();
    private final Catalog catalog = new Catalog();
    private InProcessMcpServerRegistry registry;
    private McpEndpointHandle handle;

    @org.junit.After
    public void leaveTheSingletonEmpty() {
        // The directory is a PROCESS singleton: a registration this class leaks becomes another
        // test class's "several sessions are live" — clean up after, not only before.
        directory.clear();
    }

    @Before
    public void publishTheDirectoryAsAnEndpoint() {
        directory.clear(); // the directory is a process singleton — every test starts empty
        directory.setChatSessionCatalog(catalog);
        registry = new InProcessMcpServerRegistry();
        handle = registry.registerEndpoint(new McpEndpointDefinition("public-connector", "Public"));
        registry.updateTools(handle, ResearchBotDirectoryTools.of(directory));
    }

    private McpToolResult call(String tool, String... kv) {
        Map<String, Object> args = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            args.put(kv[i], kv[i + 1]);
        }
        return registry.invoke("public-connector", handle.getToken(), new McpToolCall(tool, args));
    }

    @Test
    public void thePublicFaceOffersTheMultiSessionTools() {
        assertEquals(Arrays.asList("sessions_list", "session_create", "run_command", "session_state",
                        "chat_history", "technical_log", "concept_json"),
                registry.listToolNames("public-connector", handle.getToken()));
        // The catalog is STABLE: with nothing running the tools still exist and answer honestly.
        McpToolResult empty = call("sessions_list");
        assertFalse(empty.isError());
        assertEquals("{\"sessions\":[]}", empty.getText());
        assertTrue(call("session_state").isError());
    }

    @Test
    public void sessionsWithTheSameTitleStayDistinctBecauseTheIdIsTheIdentity() {
        catalog.titles.put("uuid-a", "karnickelgulasch");
        catalog.titles.put("uuid-b", "karnickelgulasch");
        catalog.active = "uuid-b";
        directory.register("uuid-a", "research#uuid-a", new Gateway("A"));
        directory.register("uuid-b", "research#uuid-b", new Gateway("B"));

        String listed = call("sessions_list").getText();
        assertTrue(listed, listed.contains("\"sessionId\":\"uuid-a\""));
        assertTrue(listed, listed.contains("\"sessionId\":\"uuid-b\""));
        assertEquals("both titles are shown, neither is an identity",
                2, listed.split("karnickelgulasch", -1).length - 1);
        // Exactly ONE entry is the selected chat, and the state text is the session's own.
        assertTrue(listed, listed.contains("\"sessionId\":\"uuid-b\",\"title\":\"karnickelgulasch\","
                + "\"current\":true"));
        assertTrue(listed, listed.contains("session=A"));
    }

    @Test
    public void anExplicitSessionIdDrivesThatSessionWhileAnotherChatIsSelected() {
        Gateway a = new Gateway("A");
        Gateway b = new Gateway("B");
        directory.register("uuid-a", "research#uuid-a", a);
        directory.register("uuid-b", "research#uuid-b", b);
        catalog.active = "uuid-a";

        McpToolResult driven = call("run_command", "sessionId", "uuid-b", "command", "continue");
        assertEquals("handled by B", driven.getText());
        assertTrue(a.invoked.isEmpty()); // the visible chat is untouched — addressing is not selecting
        assertEquals(Arrays.asList("execute(continue|null)"), b.invoked);
        assertEquals("summary B", call("chat_history", "sessionId", "uuid-b").getText());
        assertEquals("phase=scoping state=running session=B",
                call("session_state", "sessionId", "uuid-b").getText());
    }

    @Test
    public void withoutASessionIdTheSelectedChatIsAddressedAndFollowsEveryTabSwitch() {
        directory.register("uuid-a", "research#uuid-a", new Gateway("A"));
        directory.register("uuid-b", "research#uuid-b", new Gateway("B"));

        catalog.active = "uuid-a";
        assertEquals("phase=scoping state=running session=A", call("session_state").getText());
        // The regression this whole slice exists for: switching the visible chat switches the implicit
        // target IMMEDIATELY — no session is created, nothing is re-attached anywhere.
        catalog.active = "uuid-b";
        assertEquals("phase=scoping state=running session=B", call("session_state").getText());
        assertEquals("handled by B", call("run_command", "command", "pause").getText());
    }

    @Test
    public void aChatWithoutAResearchSessionIsAnHonestErrorPointingAtSessionsList() {
        directory.register("uuid-b", "research#uuid-b", new Gateway("B"));
        catalog.active = "uuid-plain-chat"; // a normal chat tab, no research running in it

        McpToolResult implicit = call("session_state");
        assertTrue(implicit.isError());
        assertTrue(implicit.getText(), implicit.getText().contains("sessions_list"));
        // ...while the running session stays reachable by its id.
        assertFalse(call("session_state", "sessionId", "uuid-b").isError());
    }

    @Test
    public void anUnknownOrClosedSessionIdIsRejectedInsteadOfSilentlyHittingAnotherSession() {
        Gateway a = new Gateway("A");
        ResearchBotSessionRegistration registrationA =
                directory.register("uuid-a", "research#uuid-a", a);
        directory.register("uuid-b", "research#uuid-b", new Gateway("B"));
        catalog.active = "uuid-b";

        directory.unregister(registrationA); // the chat was closed
        McpToolResult gone = call("run_command", "sessionId", "uuid-a", "command", "continue");
        assertTrue(gone.isError());
        assertTrue(gone.getText(), gone.getText().contains("uuid-a"));
        assertTrue(a.invoked.isEmpty());
        assertFalse(call("sessions_list").getText().contains("uuid-a"));
        assertFalse("the other session is unaffected", call("session_state").isError());
    }

    @Test
    public void aLateCloseOfAReopenedChatNeverRemovesTheNewSession() {
        ResearchBotSessionRegistration first = directory.register("uuid-a", "research#uuid-a",
                new Gateway("old"));
        directory.register("uuid-a", "research#uuid-a", new Gateway("new")); // chat reopened
        directory.unregister(first); // the OLD session finishes closing only now

        assertEquals("phase=scoping state=running session=new",
                call("session_state", "sessionId", "uuid-a").getText());
    }

    /**
     * session_create is the ONE tool that changes what the user sees. It must hand back an id that is
     * immediately usable — i.e. one whose research session is registered — and it must confirm that, rather
     * than returning an id nothing answers on.
     */
    @Test
    public void sessionCreateReturnsAnIdThatIsImmediatelyDriveable() {
        directory.setChatSessionLauncher(
                new com.aresstack.askai.plugin.api.service.ChatSessionLauncher() {
                    public String createChatSession(String agentId, String title) {
                        // The host activates the agent while creating the chat, so by the time it returns
                        // the session has registered itself — reproduced here.
                        catalog.titles.put("uuid-new", title);
                        directory.register("uuid-new", "research#uuid-new", new Gateway("new"));
                        return "uuid-new";
                    }
                });

        McpToolResult created = call("session_create", "title", "wearables");
        assertFalse(created.getText(), created.isError());
        assertTrue(created.getText(), created.getText().contains("sessionId=uuid-new"));
        assertTrue("the answer already carries the new session's state",
                created.getText().contains("session=new"));
        assertEquals("handled by new",
                call("run_command", "sessionId", "uuid-new", "command", "search").getText());
    }

    @Test
    public void aChatThatComesUpWithoutAResearchSessionIsReportedInsteadOfHandingBackADeadId() {
        directory.setChatSessionLauncher(
                new com.aresstack.askai.plugin.api.service.ChatSessionLauncher() {
                    public String createChatSession(String agentId, String title) {
                        return ""; // the host could not open a chat at all
                    }
                });

        McpToolResult failed = call("session_create");
        assertTrue(failed.isError());
        assertTrue(failed.getText(), failed.getText().contains("could not open"));
    }

    @Test
    public void withoutAWritingHostPortSessionCreateSaysSoAndPointsAtTheApp() {
        // The directory is a process singleton; this test relies on no launcher having been published.
        McpToolResult unsupported = call("session_create");
        assertTrue(unsupported.isError());
        assertTrue(unsupported.getText(), unsupported.getText().contains("does not allow creating chats"));
    }

    @Test
    public void switchingTheConnectorOffOnlyStopsTheListenerAndKeepsEverySession() {
        directory.register("uuid-a", "research#uuid-a", new Gateway("A"));
        directory.register("uuid-b", "research#uuid-b", new Gateway("B"));

        com.aresstack.askai.research.connector.ChatGptConnectorRuntime.get().stopListener();

        assertEquals(2, directory.list().size());
        String listed = call("sessions_list").getText();
        assertTrue(listed, listed.contains("uuid-a") && listed.contains("uuid-b"));
    }
}
