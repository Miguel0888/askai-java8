package com.aresstack.askai.acp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The guarded lifecycles: valid chains pass, invalid transitions are rejected without mutation. */
public class AcpStatesTest {

    @Test
    public void connectionHappyPathAndInvalids() {
        AcpStates.Connection c = new AcpStates.Connection();
        assertTrue(c.to(AcpConnectionState.INITIALIZING));
        assertTrue(c.to(AcpConnectionState.READY));
        assertTrue(c.to(AcpConnectionState.CLOSED));
        assertFalse("CLOSED is final", c.to(AcpConnectionState.READY));
        assertEquals(AcpConnectionState.CLOSED, c.get());

        AcpStates.Connection f = new AcpStates.Connection();
        assertTrue(f.to(AcpConnectionState.INITIALIZING));
        assertTrue(f.to(AcpConnectionState.FAILED));
        assertFalse("FAILED -> READY must be rejected", f.to(AcpConnectionState.READY));
    }

    @Test
    public void sessionHappyPathAndInvalids() {
        AcpStates.Session s = new AcpStates.Session();
        assertTrue(s.to(AcpSessionState.ACTIVE));
        assertTrue(s.to(AcpSessionState.CLOSING));
        assertTrue(s.to(AcpSessionState.CLOSED));
        assertFalse("CLOSED -> ACTIVE must be rejected", s.to(AcpSessionState.ACTIVE));
    }

    @Test
    public void promptMatrixWithSingleTerminal() {
        AcpStates.Prompt p = new AcpStates.Prompt();
        assertTrue(p.to(AcpPromptState.RUNNING));
        assertTrue(p.to(AcpPromptState.CANCELLING));
        assertTrue(p.to(AcpPromptState.CANCELLED));
        assertFalse("terminal is final", p.to(AcpPromptState.COMPLETED));

        AcpStates.Prompt done = new AcpStates.Prompt();
        assertTrue(done.to(AcpPromptState.RUNNING));
        assertTrue(done.to(AcpPromptState.COMPLETED));
        assertFalse("COMPLETED -> CANCELLING must be rejected", done.to(AcpPromptState.CANCELLING));
        assertEquals(AcpPromptState.COMPLETED, done.get());
    }
}
