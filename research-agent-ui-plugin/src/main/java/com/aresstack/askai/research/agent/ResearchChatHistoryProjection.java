package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.ChatMessageSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders {@code chat_history} as a PROJECTION of two sources that are each authoritative for their own
 * part: the host's persisted chat messages (the conversation, in the order the user sees it) and the
 * {@link ResearchPhaseJournal} (which research phase a message belongs to, plus the phase outcomes).
 * <p>
 * Consequences that are deliberate:
 * <ul>
 * <li>the FULL conversation is returned, including everything from before the current process started —
 *     the record no longer begins at session start,</li>
 * <li>a message the journal does not know stays {@code phase unknown} and is still rendered in full;
 *     nothing is dropped and nothing is guessed,</li>
 * <li>{@code info} breadcrumbs (e.g. "Websuche: …") are part of the visible history and are kept.</li>
 * </ul>
 */
public final class ResearchChatHistoryProjection {

    /** The phase label for messages the journal has no attribution for. */
    static final String UNKNOWN_PHASE = "unknown";

    private ResearchChatHistoryProjection() {
    }

    /**
     * @param messages       the persisted conversation, in order (host truth)
     * @param journal        the research attribution (may be empty)
     * @param currentPhaseId the phase the session is in right now — rendered in full even when raw=false
     * @param raw            true = every message of every phase, false = finished phases as one summary
     */
    public static String render(List<ChatMessageSnapshot> messages, ResearchPhaseJournal journal,
                                String currentPhaseId, boolean raw) {
        if (messages == null || messages.isEmpty()) {
            return "(this chat has no persisted messages yet)";
        }
        String current = currentPhaseId == null ? "" : currentPhaseId;
        // Consecutive messages of the same phase form one block, so the rendering follows the conversation
        // order instead of regrouping it — a phase the workflow returns to appears twice, as it happened.
        List<Block> blocks = new ArrayList<Block>();
        for (ChatMessageSnapshot message : messages) {
            String phase = journal == null ? "" : journal.phaseOf(message.getMessageId());
            String label = phase.isEmpty() ? UNKNOWN_PHASE : phase;
            if (blocks.isEmpty() || !blocks.get(blocks.size() - 1).phase.equals(label)) {
                blocks.add(new Block(label));
            }
            blocks.get(blocks.size() - 1).messages.add(message);
        }
        StringBuilder sb = new StringBuilder();
        for (Block block : blocks) {
            boolean isCurrent = block.phase.equals(current);
            sb.append("== phase ").append(block.phase).append(isCurrent ? " (current)" : "").append('\n');
            String outcome = journal == null ? "" : journal.outcomeOf(block.phase);
            boolean detail = raw || isCurrent;
            if (!detail) {
                sb.append("  summary: ").append(outcome.isEmpty()
                        ? block.messages.size() + " messages (raw=true for details)"
                        : outcome + " [" + block.messages.size() + " messages]").append('\n');
                continue;
            }
            if (!outcome.isEmpty()) {
                sb.append("  outcome: ").append(outcome).append('\n');
            }
            for (ChatMessageSnapshot message : block.messages) {
                sb.append("  [").append(message.getRole()).append("] ")
                        .append(message.getText().replace("\n", "\n      ")).append('\n');
            }
        }
        return sb.toString();
    }

    /** One run of consecutive messages sharing a phase. */
    private static final class Block {
        private final String phase;
        private final List<ChatMessageSnapshot> messages = new ArrayList<ChatMessageSnapshot>();

        private Block(String phase) {
            this.phase = phase;
        }
    }
}
