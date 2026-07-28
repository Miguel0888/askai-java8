package com.aresstack.askai.research.acp;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.acp.AcpConnection;
import com.aresstack.askai.acp.AcpConnectionState;
import com.aresstack.askai.acp.AcpEndpointDescriptor;
import com.aresstack.askai.acp.AcpException;
import com.aresstack.askai.acp.AcpPromptState;
import com.aresstack.askai.acp.AcpSession;
import com.aresstack.askai.acp.AcpSessionState;
import com.aresstack.askai.acp.AcpUpdate;
import com.aresstack.askai.acp.AcpUpdateListener;
import com.aresstack.askai.acp.AgentLaunchSpec;
import com.aresstack.askai.acp.AgentProcessHandle;
import com.aresstack.askai.acp.PromptHandle;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.state.ResearchCommandType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The ACP adapter against a fake connector: env hand-off, mapping, cancel, rollback, no state-machine drive. */
public class AcpResearchSessionBackendTest {

    private static final AcpEndpointDescriptor RESEARCH =
            new AcpEndpointDescriptor("research.s1.g1", "http://127.0.0.1:1234/mcp/x/tok", "streamable", "tok");

    private static final class Rec implements ResearchSessionListener {
        final List<ResearchBackendEvent> events = new ArrayList<ResearchBackendEvent>();

        public void onEvent(ResearchBackendEvent event) {
            events.add(event);
        }
    }

    /** Fake connector/session capturing the launch env and simulating streamed updates. */
    private static final class FakeConnector implements AcpAgentConnector {
        AgentLaunchSpec seen;
        FakeSession session = new FakeSession();
        boolean failConnect;
        boolean closed;

        public AcpConnection connect(AgentLaunchSpec spec) throws AcpException {
            this.seen = spec;
            if (failConnect) {
                throw new AcpException(AcpException.Phase.INITIALIZE, "boom", null);
            }
            return new AcpConnection() {
                public AcpConnectionState getState() {
                    return AcpConnectionState.READY;
                }

                public AgentProcessHandle getProcess() {
                    return new AgentProcessHandle() {
                        public boolean isAlive() {
                            return !closed;
                        }

                        public void destroyForcibly() {
                            closed = true;
                        }
                    };
                }

                public AcpSession newSession() {
                    return session;
                }

                public void close() {
                    closed = true;
                }
            };
        }
    }

    private static final class FakeSession implements AcpSession {
        AcpUpdateListener listener;
        boolean cancelled;

        public String getSessionId() {
            return "acp-1";
        }

        public AcpSessionState getState() {
            return AcpSessionState.ACTIVE;
        }

        public PromptHandle prompt(String text, AcpUpdateListener l) {
            this.listener = l;
            return new PromptHandle() {
                public String getPromptId() {
                    return "p1";
                }

                public AcpPromptState getState() {
                    return AcpPromptState.RUNNING;
                }

                public void cancel() {
                    cancelled = true;
                }
            };
        }

        public void close() {
        }
    }

    @Test
    public void startHandsEndpointsAsStructuredEnvironmentWithoutTokenLeak() {
        FakeConnector connector = new FakeConnector();
        AcpResearchSessionBackend backend = new AcpResearchSessionBackend(connector,
                new AgentLaunchSpec("java", null, null), RESEARCH, null);
        Rec rec = new Rec();
        ResearchSessionHandle handle = backend.createSession(
                new ResearchProjectRequest("s1", "p1", "t"), rec);
        assertNotNull(handle);
        // Endpoints travel as structured env, never prompt text.
        assertEquals(RESEARCH.getUrl(), connector.seen.getEnv().get("ASKAI_RESEARCH_MCP_URL"));
        assertEquals("streamable", connector.seen.getEnv().get("ASKAI_RESEARCH_MCP_TRANSPORT"));
        // Missing browser endpoint is HONESTLY visible (no silent STATIC_HTTP fallback).
        boolean reported = false;
        for (ResearchBackendEvent e : rec.events) {
            if (e.getType() == ResearchBackendEventType.ACTIVITY
                    && e.getText().contains("BROWSER_NOT_AVAILABLE")) {
                reported = true;
                assertFalse("token must not leak into events", e.getText().contains("tok"));
            }
        }
        assertTrue(reported);
    }

    @Test
    public void updatesMapIntoBackendEventsWithMonotonicSequence() {
        FakeConnector connector = new FakeConnector();
        AcpResearchSessionBackend backend = new AcpResearchSessionBackend(connector,
                new AgentLaunchSpec("java", null, null), RESEARCH, RESEARCH);
        Rec rec = new Rec();
        ResearchSessionHandle handle = backend.createSession(
                new ResearchProjectRequest("s1", "p1", "t"), rec);
        backend.submitPrompt(handle, new ResearchPrompt("go", ""));

        connector.session.listener.onUpdate(new AcpUpdate("acp-1", "p1", 1, AcpUpdate.Kind.THOUGHT, "hmm"));
        connector.session.listener.onUpdate(new AcpUpdate("acp-1", "p1", 2, AcpUpdate.Kind.MESSAGE, "found"));
        connector.session.listener.onTerminal("p1", AcpPromptState.COMPLETED, "END_TURN");

        assertEquals(3, rec.events.size());
        assertEquals(ResearchBackendEventType.ACTIVITY, rec.events.get(0).getType());
        assertEquals(ResearchBackendEventType.ASSISTANT_MESSAGE, rec.events.get(1).getType());
        // The terminal MUST arrive typed COMPLETED — the session clears its turn-in-flight flag
        // (composer unblock) only on this type; a plain ASSISTANT_MESSAGE would wedge the composer.
        assertEquals(ResearchBackendEventType.COMPLETED, rec.events.get(2).getType());
        long last = 0;
        for (ResearchBackendEvent e : rec.events) {
            assertTrue("monotonic backend sequence", e.getSequenceNumber() > last);
            last = e.getSequenceNumber();
        }
    }

    @Test
    public void cancelStopsPromptNotProcessAndAdapterNeverDrivesStateMachine() {
        FakeConnector connector = new FakeConnector();
        AcpResearchSessionBackend backend = new AcpResearchSessionBackend(connector,
                new AgentLaunchSpec("java", null, null), RESEARCH, RESEARCH);
        Rec rec = new Rec();
        ResearchSessionHandle handle = backend.createSession(
                new ResearchProjectRequest("s1", "p1", "t"), rec);
        backend.submitPrompt(handle, new ResearchPrompt("go", ""));
        backend.cancel(handle);
        assertTrue(connector.session.cancelled);
        assertFalse("process must survive a prompt cancel", connector.closed);
        // Pure adapter: no functional transitions ever.
        assertFalse(backend.canExecute(handle, ResearchCommandType.APPROVE_OUTLINE));
        // FAILED terminal maps to an ERROR event (process death classified, not swallowed).
        connector.session.listener.onTerminal("p1", AcpPromptState.FAILED, "agent died");
        assertEquals(ResearchBackendEventType.ERROR, rec.events.get(rec.events.size() - 1).getType());
    }

    @Test
    public void connectFailureRollsBackAndCloseSilencesLateEvents() {
        FakeConnector failing = new FakeConnector();
        failing.failConnect = true;
        AcpResearchSessionBackend backend = new AcpResearchSessionBackend(failing,
                new AgentLaunchSpec("java", null, null), RESEARCH, RESEARCH);
        Rec rec = new Rec();
        backend.createSession(new ResearchProjectRequest("s1", "p1", "t"), rec);
        assertEquals(1, rec.events.size());
        assertEquals(ResearchBackendEventType.ERROR, rec.events.get(0).getType());
        assertTrue(rec.events.get(0).getTechnicalDetail().contains("INITIALIZE"));

        FakeConnector ok = new FakeConnector();
        AcpResearchSessionBackend backend2 = new AcpResearchSessionBackend(ok,
                new AgentLaunchSpec("java", null, null), RESEARCH, RESEARCH);
        Rec rec2 = new Rec();
        ResearchSessionHandle h2 = backend2.createSession(new ResearchProjectRequest("s2", "p", "t"), rec2);
        backend2.submitPrompt(h2, new ResearchPrompt("go", ""));
        AcpUpdateListener listener = ok.session.listener;
        backend2.close(h2);
        assertTrue("close shuts the connection down", ok.closed);
        int before = rec2.events.size();
        listener.onUpdate(new AcpUpdate("acp-1", "p1", 9, AcpUpdate.Kind.MESSAGE, "late"));
        assertEquals("no events after close", before, rec2.events.size());
    }
}
