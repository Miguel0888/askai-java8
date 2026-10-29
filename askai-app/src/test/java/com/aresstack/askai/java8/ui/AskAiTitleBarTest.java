package com.aresstack.askai.java8.ui;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.search.AgentSearchContribution;
import com.aresstack.comiccontrols.control.ComicSearchTag;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The own title bar: title left, the active agent's search as a host-owned field, hidden without one. */
public class AskAiTitleBarTest {

    private static final Runnable NOTHING = new Runnable() {
        @Override
        public void run() {
        }
    };

    @Test
    public void showsTheWindowTitle() {
        AskAiTitleBar bar = new AskAiTitleBar("AskAI", NOTHING, NOTHING);
        assertEquals("AskAI", bar.getTitle());
        bar.setTitle("AskAI – Chat");
        assertEquals("AskAI – Chat", bar.getTitle());
    }

    @Test
    public void theSearchIsHiddenWithoutAnAgentContribution() {
        AskAiTitleBar bar = new AskAiTitleBar("AskAI", NOTHING, NOTHING);
        assertNull(bar.searchField());
        bar.bindSearch(null, null);
        assertNull(bar.searchField());
    }

    @Test
    public void theAgentDefinesWhatTheSearchDoes() {
        final List<String> searched = new ArrayList<String>();
        final AgentSession session = (AgentSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AgentSession.class}, (proxy, method, args) -> null);
        AgentSearchContribution search = new AgentSearchContribution() {
            public boolean supports(AgentSession s) {
                return true;
            }

            public String getPlaceholder() {
                return "Find documents…";
            }

            public String getTooltip() {
                return "Search my documents";
            }

            public void search(AgentSession s, String query) {
                assertTrue(s == session);
                searched.add(query);
            }
        };
        AskAiTitleBar bar = new AskAiTitleBar("AskAI", NOTHING, NOTHING);
        Supplier<AgentSession> sessions = () -> session;
        bar.bindSearch(search, sessions);
        ComicSearchTag field = (ComicSearchTag) bar.searchField();
        assertNotNull("the agent's search is shown", field);

        field.getTextField().setText("  wearables  ");
        field.getTextField().postActionEvent(); // Enter
        assertEquals("[wearables]", searched.toString());
        assertEquals("the host clears the field after a search", "", field.getText());

        bar.bindSearch(search, sessions);
        assertTrue("rebinding the same contribution keeps the field", field == bar.searchField());
        bar.bindSearch(null, sessions);
        assertNull("no active agent search: hidden", bar.searchField());
    }
}
