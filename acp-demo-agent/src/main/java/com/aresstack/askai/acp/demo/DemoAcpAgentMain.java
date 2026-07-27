package com.aresstack.askai.acp.demo;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * External Java-8 demo ACP agent (STDIO transport). STDOUT carries ONLY the ACP protocol; every log line
 * goes to STDERR. On a prompt it streams a thought + several message chunks, then ends the turn; a prompt
 * containing "slow" spins until cancelled (cancel support). Unknown custom notifications are tolerated by
 * the SDK dispatcher and simply not handled here — they must never kill the process.
 */
@AcpAgent(name = "askai-demo-agent", version = "0.1")
public final class DemoAcpAgentMain {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public static void main(String[] args) {
        System.err.println("[demo-agent] starting");
        AcpAgentSupport.create(new DemoAcpAgentMain())
                .transport(new StdioAcpAgentTransport())
                .build().run();
        System.err.println("[demo-agent] terminated");
    }

    @Initialize
    public AcpSchema.InitializeResponse initialize() {
        System.err.println("[demo-agent] initialize");
        return AcpSchema.InitializeResponse.ok();
    }

    @NewSession
    public AcpSchema.NewSessionResponse newSession() {
        System.err.println("[demo-agent] new session");
        return new AcpSchema.NewSessionResponse("demo-session-1", null, null);
    }

    @Cancel
    public void cancel() {
        System.err.println("[demo-agent] cancel received");
        cancelled.set(true);
    }

    @Prompt
    public AcpSchema.PromptResponse prompt(SyncPromptContext ctx, AcpSchema.PromptRequest request) {
        cancelled.set(false);
        String text = request.text() == null ? "" : request.text();
        System.err.println("[demo-agent] prompt: " + text);
        ctx.sendThought("thinking about: " + text);
        for (int i = 1; i <= 3; i++) {
            if (cancelled.get()) {
                return new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED);
            }
            ctx.sendMessage("chunk " + i + " for '" + text + "'");
        }
        if (text.contains("slow")) {
            // Spin (bounded) until cancel arrives, so cancel-during-streaming is testable without sleeps.
            for (int i = 0; i < 1_000_000 && !cancelled.get(); i++) {
                ctx.sendMessage("slow " + i);
                if (i > 3 && cancelled.get()) {
                    break;
                }
            }
            if (cancelled.get()) {
                return new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED);
            }
        }
        return AcpSchema.PromptResponse.endTurn();
    }
}
