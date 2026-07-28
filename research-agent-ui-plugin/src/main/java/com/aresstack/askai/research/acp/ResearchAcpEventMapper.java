package com.aresstack.askai.research.acp;

import com.aresstack.askai.acp.AcpPromptState;
import com.aresstack.askai.acp.AcpUpdate;
import com.aresstack.askai.research.backend.ResearchActivityKind;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;

/**
 * THE single place that maps neutral ACP events onto {@link ResearchBackendEvent}s — no mapping logic in the
 * session, UI or backend. Sequence numbers stay the backend's per-session monotonic counter (assigned by the
 * caller); the ACP update's own sequence is carried in the detail for diagnostics. Tokens never appear in any
 * mapped text.
 */
public final class ResearchAcpEventMapper {

    private ResearchAcpEventMapper() {
    }

    /** ACP content/thought/other update → a backend event builder (caller stamps envelope + sequence). */
    public static ResearchBackendEvent.Builder mapUpdate(AcpUpdate update) {
        switch (update.getKind()) {
            case THOUGHT:
                return ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                        .activity("acp-th-" + update.getPromptId(), ResearchActivityKind.THINKING_UPDATE,
                                "Thinking", update.getText());
            case MESSAGE:
                return ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                        .text(update.getText());
            case OTHER:
            default:
                // Custom/unknown research extensions surface as tool-style activity, never crash the flow.
                return ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                        .activity("acp-x-" + update.getPromptId(), ResearchActivityKind.TOOL_UPDATE,
                                "Agent event", update.getText());
        }
    }

    /** ACP terminal → completion/failure builder ({@code null} for CANCELLED: cancel is user-driven, silent). */
    public static ResearchBackendEvent.Builder mapTerminal(AcpPromptState state, String detail) {
        switch (state) {
            case COMPLETED:
                // MUST be the COMPLETED event type (not a plain assistant message): the session clears
                // its turn-in-flight flag on it — otherwise the composer stays busy forever after
                // "Agent turn completed." (user-reported). The text still renders as a normal bubble.
                return ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED)
                        .text("Agent turn completed.");
            case FAILED:
                return ResearchBackendEvent.builder(ResearchBackendEventType.ERROR)
                        .messages("The research agent failed.", detail == null ? "" : detail);
            case CANCELLED:
            default:
                return null;
        }
    }
}
