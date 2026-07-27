package com.aresstack.askai.acp;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Sequence monotonicity, exactly-one-terminal, late-update drop, listener-exception isolation. */
public class PromptDispatcherTest {

    private static final class Rec implements AcpUpdateListener {
        final List<Long> sequences = new ArrayList<Long>();
        final List<AcpPromptState> terminals = new ArrayList<AcpPromptState>();
        boolean throwOnUpdate;

        public void onUpdate(AcpUpdate update) {
            sequences.add(update.getSequenceNumber());
            if (throwOnUpdate) {
                throw new IllegalStateException("consumer bug");
            }
        }

        public void onTerminal(String promptId, AcpPromptState state, String detail) {
            terminals.add(state);
        }
    }

    @Test
    public void updatesAreMonotonicThenExactlyOneTerminal() {
        Rec rec = new Rec();
        PromptDispatcher d = new PromptDispatcher("s1", "p1", rec);
        assertTrue(d.update(AcpUpdate.Kind.MESSAGE, "a"));
        assertTrue(d.update(AcpUpdate.Kind.THOUGHT, "b"));
        assertTrue(d.update(AcpUpdate.Kind.MESSAGE, "c"));
        assertTrue(d.terminal(AcpPromptState.COMPLETED, ""));
        assertEquals(java.util.Arrays.asList(1L, 2L, 3L), rec.sequences);
        assertEquals(1, rec.terminals.size());

        // Late SDK callbacks after the terminal are dropped; a second terminal is a no-op.
        assertFalse(d.update(AcpUpdate.Kind.MESSAGE, "late"));
        assertFalse(d.terminal(AcpPromptState.CANCELLED, "late cancel"));
        assertEquals(3, rec.sequences.size());
        assertEquals(1, rec.terminals.size());
        assertEquals(AcpPromptState.COMPLETED, d.getState());
    }

    @Test
    public void cancelVersusCompletionRaceYieldsOneTerminal() {
        Rec rec = new Rec();
        PromptDispatcher d = new PromptDispatcher("s1", "p1", rec);
        assertTrue(d.cancelling());
        assertTrue(d.cancelling() == false); // idempotent-ish: second cancelling rejected, state unchanged
        // Completion arriving while CANCELLING is a legal single terminal.
        assertTrue(d.terminal(AcpPromptState.COMPLETED, ""));
        assertFalse(d.terminal(AcpPromptState.CANCELLED, ""));
        assertEquals(java.util.Arrays.asList(AcpPromptState.COMPLETED), rec.terminals);
    }

    @Test
    public void listenerExceptionDoesNotKillTheDispatcher() {
        Rec rec = new Rec();
        rec.throwOnUpdate = true;
        PromptDispatcher d = new PromptDispatcher("s1", "p1", rec);
        assertTrue(d.update(AcpUpdate.Kind.MESSAGE, "a")); // listener throws, dispatcher survives
        assertTrue(d.update(AcpUpdate.Kind.MESSAGE, "b"));
        assertTrue(d.terminal(AcpPromptState.FAILED, "agent died"));
        assertEquals(2, rec.sequences.size());
    }
}
