package com.aresstack.askai.research.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The session's PHASE-ATTRIBUTED chat record for headless clients: every visible message is stamped with the
 * phase it happened in, and a phase can carry ONE outcome summary (e.g. the run-outcome narrative). The
 * default rendering compresses finished phases to their summary (outcome + message count) and prints the
 * CURRENT phase in full; {@code raw=true} prints every recorded entry of every phase. In-memory only: after
 * a restart the record starts empty (the host owns the persisted GUI transcript) — honest, documented, and
 * sufficient for driving a live session. Thread-safe via synchronization (writers: EDT + event pipeline).
 */
public final class ResearchTranscript {

    /** One visible message: role is user / assistant / info / problem. */
    public static final class Entry {
        final String role;
        final String text;

        Entry(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    private static final class PhaseLog {
        final List<Entry> entries = new ArrayList<Entry>();
        String outcomeSummary = "";
    }

    /** Insertion-ordered: phases appear in the order they first produced a message. */
    private final Map<String, PhaseLog> byPhase = new LinkedHashMap<String, PhaseLog>();

    public synchronized void record(String phaseId, String role, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        log(phaseId).entries.add(new Entry(role, text.trim()));
    }

    /** The phase's ONE summary (latest wins) — e.g. the structured run-outcome narrative. */
    public synchronized void recordOutcome(String phaseId, String summary) {
        if (summary == null || summary.trim().isEmpty()) {
            return;
        }
        log(phaseId).outcomeSummary = summary.trim();
    }

    /**
     * Render the record: finished phases as ONE summary block (outcome + message count), the CURRENT phase
     * in full detail; {@code raw=true} renders every entry of every phase instead.
     */
    public synchronized String describe(String currentPhaseId, boolean raw) {
        if (byPhase.isEmpty()) {
            return "(no messages recorded in this session yet — the record starts at session start)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, PhaseLog> phase : byPhase.entrySet()) {
            String phaseId = phase.getKey();
            PhaseLog log = phase.getValue();
            boolean detail = raw || phaseId.equals(currentPhaseId);
            sb.append("== phase ").append(phaseId)
              .append(phaseId.equals(currentPhaseId) ? " (current)" : "").append('\n');
            if (!detail) {
                sb.append("  summary: ").append(log.outcomeSummary.isEmpty()
                        ? log.entries.size() + " messages (raw=true for details)"
                        : log.outcomeSummary + " [" + log.entries.size() + " messages]").append('\n');
                continue;
            }
            if (!log.outcomeSummary.isEmpty()) {
                sb.append("  outcome: ").append(log.outcomeSummary).append('\n');
            }
            for (Entry entry : log.entries) {
                sb.append("  [").append(entry.role).append("] ")
                  .append(entry.text.replace("\n", "\n      ")).append('\n');
            }
        }
        return sb.toString();
    }

    private PhaseLog log(String phaseId) {
        String key = phaseId == null || phaseId.isEmpty() ? "unknown" : phaseId;
        PhaseLog log = byPhase.get(key);
        if (log == null) {
            log = new PhaseLog();
            byPhase.put(key, log);
        }
        return log;
    }
}
