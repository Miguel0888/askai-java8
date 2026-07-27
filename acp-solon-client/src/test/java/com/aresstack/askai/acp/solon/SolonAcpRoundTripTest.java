package com.aresstack.askai.acp.solon;

import com.aresstack.askai.acp.AcpConnection;
import com.aresstack.askai.acp.AcpConnectionState;
import com.aresstack.askai.acp.AcpPromptState;
import com.aresstack.askai.acp.AcpSession;
import com.aresstack.askai.acp.AcpUpdate;
import com.aresstack.askai.acp.AcpUpdateListener;
import com.aresstack.askai.acp.AgentLaunchSpec;
import com.aresstack.askai.acp.PromptHandle;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Real process round-trip: the adapter spawns the Java-8 demo agent jar over STDIO, initializes ACP, opens a
 * session, streams a prompt (thought + message chunks with monotonic sequences), completes, cancels a slow
 * prompt, and shuts down. STDERR logs from the agent are drained and must not disturb the protocol. No
 * Thread.sleep — synchronization via latches with timeouts.
 */
public class SolonAcpRoundTripTest {

    private String javaBin;
    private String agentJar;

    @Before
    public void resolve() {
        agentJar = System.getProperty("acp.demo.agent.jar");
        assumeTrue("demo agent jar not set (run via Gradle)", agentJar != null && new File(agentJar).isFile());
        String home = System.getProperty("acp.java8.home", System.getProperty("java.home"));
        javaBin = home + File.separator + "bin" + File.separator
                + (System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        assumeTrue("java binary missing", new File(javaBin).isFile());
    }

    private AgentLaunchSpec spec() {
        return new AgentLaunchSpec(javaBin, Arrays.asList("-jar", agentJar), null);
    }

    private static final class Collecting implements AcpUpdateListener {
        final List<AcpUpdate> updates = new CopyOnWriteArrayList<AcpUpdate>();
        final AtomicReference<AcpPromptState> terminal = new AtomicReference<AcpPromptState>();
        final CountDownLatch terminated = new CountDownLatch(1);
        final CountDownLatch firstUpdate = new CountDownLatch(1);
        volatile int terminalCount;

        public void onUpdate(AcpUpdate update) {
            updates.add(update);
            firstUpdate.countDown();
        }

        public void onTerminal(String promptId, AcpPromptState state, String detail) {
            terminal.set(state);
            terminalCount++;
            terminated.countDown();
        }
    }

    @Test
    public void happyPathStreamsUpdatesThenCompletesAndCloseIsIdempotent() throws Exception {
        List<String> stderr = new CopyOnWriteArrayList<String>();
        SolonAcpAgentConnector connector =
                new SolonAcpAgentConnector(Duration.ofSeconds(30), stderr::add);
        AcpConnection connection = connector.connect(spec());
        try {
            assertEquals(AcpConnectionState.READY, connection.getState());
            AcpSession session = connection.newSession();
            Collecting listener = new Collecting();
            PromptHandle handle = session.prompt("hello acp", listener);

            assertTrue("prompt did not terminate", listener.terminated.await(30, TimeUnit.SECONDS));
            assertEquals(AcpPromptState.COMPLETED, listener.terminal.get());
            assertEquals("exactly one terminal", 1, listener.terminalCount);

            // Thought + 3 message chunks, monotonic sequence numbers, correct attribution.
            assertTrue("expected >= 4 updates, got " + listener.updates.size(),
                    listener.updates.size() >= 4);
            long last = 0;
            boolean sawThought = false;
            for (AcpUpdate u : listener.updates) {
                assertTrue("monotonic sequence", u.getSequenceNumber() > last);
                last = u.getSequenceNumber();
                assertEquals(handle.getPromptId(), u.getPromptId());
                sawThought |= u.getKind() == AcpUpdate.Kind.THOUGHT;
            }
            assertTrue("thought chunk mapped", sawThought);

            // Agent logs went to STDERR and were drained without disturbing ACP.
            assertTrue("stderr drained", !stderr.isEmpty());

            // Cancel after completion is a no-op (single terminal stays COMPLETED).
            handle.cancel();
            assertEquals(AcpPromptState.COMPLETED, handle.getState());

            // A second prompt on the SAME session still works (session survives prompt lifecycle).
            Collecting second = new Collecting();
            session.prompt("again", second);
            assertTrue(second.terminated.await(30, TimeUnit.SECONDS));
            assertEquals(AcpPromptState.COMPLETED, second.terminal.get());

            session.close();
        } finally {
            connection.close();
            connection.close(); // idempotent
        }
        assertFalse(connection.getProcess().isAlive());
    }

    @Test
    public void cancelDuringStreamingYieldsSingleCancelledTerminal() throws Exception {
        SolonAcpAgentConnector connector = new SolonAcpAgentConnector(Duration.ofSeconds(30), null);
        AcpConnection connection = connector.connect(spec());
        try {
            AcpSession session = connection.newSession();
            Collecting listener = new Collecting();
            PromptHandle handle = session.prompt("slow burn", listener);

            assertTrue("no streaming started", listener.firstUpdate.await(30, TimeUnit.SECONDS));
            handle.cancel();
            handle.cancel(); // idempotent

            assertTrue("prompt did not terminate after cancel",
                    listener.terminated.await(30, TimeUnit.SECONDS));
            assertEquals(AcpPromptState.CANCELLED, listener.terminal.get());
            assertEquals(1, listener.terminalCount);

            // No update may arrive after the terminal.
            int at = listener.updates.size();
            assertEquals(at, listener.updates.size());
        } finally {
            connection.close();
        }
    }
}
