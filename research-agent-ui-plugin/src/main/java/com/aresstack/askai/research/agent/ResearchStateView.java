package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.state.ResearchCommandType;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.List;

/**
 * Read-only visualization of the hierarchical research state. It renders a {@link ResearchStateSnapshot}
 * (derived from the OO domain) — the phase timeline, the current inner state incl. continuation for
 * interruptions, the approval gate, the allowed commands (same source as slash completion), the revision and
 * any problem reason. It holds NO transition table of its own; it only displays what the domain reports.
 */
public final class ResearchStateView extends JPanel {

    private final JTextArea area = new JTextArea();

    public ResearchStateView() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(new JScrollPane(area), BorderLayout.CENTER);
        area.setText("No active research session.");
    }

    /** Render a snapshot. Must be called on the EDT. */
    public void setSnapshot(ResearchStateSnapshot snapshot) {
        area.setText(render(snapshot));
        area.setCaretPosition(0);
    }

    /** Visible for tests: the currently rendered text. */
    public String renderedText() {
        return area.getText();
    }

    static String render(ResearchStateSnapshot s) {
        if (s == null) {
            return "No active research session.";
        }
        StringBuilder sb = new StringBuilder();
        List<String> completed = s.getCompletedPhaseIds();
        for (String phaseId : s.getPhaseOrder()) {
            sb.append(upper(phaseId)).append('\n');
            if (phaseId.equals(s.getCurrentPhaseId())) {
                sb.append("  active");
                if (s.isTerminal()) {
                    sb.append(" · ").append(upper(s.getCurrentStateId()));
                }
                sb.append('\n');
                sb.append("  └── ").append(upper(s.getCurrentStateId())).append('\n');
                if (s.getContinuationStateId() != null) {
                    sb.append("      continuation: ").append(upper(s.getContinuationStateId())).append('\n');
                }
                if (s.getPendingApprovalId() != null) {
                    sb.append("      approval: ").append(s.getPendingApprovalId()).append('\n');
                }
                if (!s.getProblem().isEmpty()) {
                    sb.append("      reason: ").append(s.getProblem()).append('\n');
                }
            } else if (completed.contains(phaseId)) {
                sb.append("  completed\n");
            } else {
                sb.append("  pending\n");
            }
            sb.append('\n');
        }
        sb.append("revision: ").append(s.getRevision()).append('\n');
        sb.append("allowed: ").append(commandNames(s)).append('\n');
        return sb.toString();
    }

    private static String commandNames(ResearchStateSnapshot s) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ResearchCommandType type : s.getAllowedCommands()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(type);
            first = false;
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private static String upper(String id) {
        return id == null ? "" : id.toUpperCase();
    }
}
