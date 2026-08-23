package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.backend.ResearchCommandDispatchResult;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicScrollPane;
import com.aresstack.comiccontrols.control.ComicSectionPanel;
import com.aresstack.comiccontrols.theme.ComicPalette;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The comic State tab: a phase TIMELINE of {@link ComicSectionPanel} plates (outer state object =
 * the phase plate; the ACTIVE phase nests a second, filled plate for its inner {@link
 * com.aresstack.askai.research.state.oo.PhaseState} — the visualization mirrors the two nested
 * state objects), plus a COMMAND BAR of {@link ComicButton}s built from the snapshot's
 * {@code getAllowedCommands()}.
 *
 * <p>The view still holds NO transition table: which buttons exist comes from the polymorphic
 * domain state, and a click only forwards the {@link ResearchCommandType} to the injected
 * {@link CommandListener} (the session's command port) — never to the state machine directly.
 * Rejections surface as an ink-red feedback line; every accepted transition re-renders the tab
 * through the ordinary snapshot listener.</p>
 */
public final class ResearchStateView extends JPanel {

    /** The seam to the session's command port; returns the structured outcome for feedback. */
    public interface CommandListener {
        ResearchCommandDispatchResult commandClicked(ResearchCommandType command);
    }

    /** Commands whose consequences are destructive — red, per the design language. */
    private static final Set<ResearchCommandType> CRITICAL_COMMANDS = EnumSet.of(
            ResearchCommandType.CANCEL, ResearchCommandType.FAIL, ResearchCommandType.BLOCK);

    private final ComicPalette palette = ComicPalette.defaultPalette();
    private final JPanel timeline = new JPanel();
    private final JPanel commandBar = new JPanel(new GridLayout(0, 2, 6, 6));
    private final JLabel feedback = new JLabel(" ");
    private final List<ComicButton> commandButtons = new ArrayList<ComicButton>();

    private ResearchStateSnapshot snapshot;
    private CommandListener commandListener;

    public ResearchStateView() {
        super(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        timeline.setLayout(new BoxLayout(timeline, BoxLayout.Y_AXIS));
        timeline.setOpaque(false);
        JPanel timelineNorth = new JPanel(new BorderLayout());
        timelineNorth.setOpaque(false);
        timelineNorth.add(timeline, BorderLayout.NORTH); // plates keep their natural height
        JScrollPane scroll = new ComicScrollPane(timelineNorth,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        commandBar.setOpaque(false);
        feedback.setForeground(palette.getAccentRed());
        feedback.setFont(feedback.getFont().deriveFont(Font.PLAIN, 11f));
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.setOpaque(false);
        south.add(commandBar, BorderLayout.CENTER);
        south.add(feedback, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        rebuild();
    }

    /** Wire the session's command port; without a listener the tab stays read-only (no buttons). */
    public void setCommandListener(CommandListener listener) {
        this.commandListener = listener;
        rebuildCommandBar();
    }

    /** Render a snapshot. Must be called on the EDT. */
    public void setSnapshot(ResearchStateSnapshot snapshot) {
        this.snapshot = snapshot;
        feedback.setText(" "); // a new state invalidates old rejection feedback
        rebuild();
    }

    // ------------------------------------------------------------------ timeline

    private void rebuild() {
        timeline.removeAll();
        if (snapshot == null) {
            JLabel none = new JLabel("No active research session.");
            none.setEnabled(false);
            none.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            timeline.add(none);
        } else {
            List<String> completed = snapshot.getCompletedPhaseIds();
            for (String phaseId : snapshot.getPhaseOrder()) {
                timeline.add(buildPhaseRow(phaseId, completed));
                timeline.add(javax.swing.Box.createVerticalStrut(6));
            }
            JLabel revision = new JLabel("revision " + snapshot.getRevision());
            revision.setEnabled(false);
            revision.setFont(revision.getFont().deriveFont(Font.PLAIN, 11f));
            timeline.add(revision);
        }
        rebuildCommandBar();
        timeline.revalidate();
        timeline.repaint();
    }

    /** One phase plate; the active one nests the inner state's own (filled) plate. */
    private JComponent buildPhaseRow(String phaseId, List<String> completed) {
        ComicSectionPanel row = new ComicSectionPanel(palette);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));

        boolean active = phaseId.equals(snapshot.getCurrentPhaseId());
        JLabel title = new JLabel(upper(phaseId));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        title.setForeground(active ? palette.getInk() : palette.getInk().brighter());
        row.add(title);

        if (active) {
            row.setAccentStripe(problemAccent() != null ? problemAccent() : palette.getAccentYellow());
            row.add(javax.swing.Box.createVerticalStrut(4));
            row.add(buildInnerStatePlate());
        } else if (completed.contains(phaseId)) {
            row.setAccentStripe(palette.getAgentPetrol());
            row.add(quietLine("completed"));
        } else {
            row.setAccentStripe(new Color(0xD8D8D8));
            row.add(quietLine("pending"));
        }
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** The nested plate for the INNER state object — this one is allowed to pop. */
    private JComponent buildInnerStatePlate() {
        ComicSectionPanel inner = new ComicSectionPanel(palette);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setPlateFill(innerStateFill());

        JLabel state = new JLabel(upper(snapshot.getCurrentStateId()));
        state.setFont(state.getFont().deriveFont(Font.BOLD, 12f));
        state.setForeground(innerStateForeground());
        inner.add(state);
        if (snapshot.getContinuationStateId() != null) {
            inner.add(detailLine("continuation: " + upper(snapshot.getContinuationStateId())));
        }
        if (snapshot.getPendingApprovalId() != null) {
            inner.add(detailLine("approval: " + snapshot.getPendingApprovalId()));
        }
        if (!snapshot.getProblem().isEmpty()) {
            inner.add(detailLine("reason: " + snapshot.getProblem()));
        }

        JPanel indent = new JPanel(new BorderLayout());
        indent.setOpaque(false);
        indent.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0)); // the nesting is visible
        indent.add(inner, BorderLayout.CENTER);
        indent.setAlignmentX(LEFT_ALIGNMENT);
        return indent;
    }

    private Color innerStateFill() {
        String stateId = snapshot.getCurrentStateId();
        if (ResearchStateIds.COMPLETED.equals(stateId)) {
            return palette.getAgentPetrol();
        }
        if (problemAccent() != null) {
            return problemAccent();
        }
        return palette.getAccentYellow(); // running / waiting / approval: the action accent
    }

    private Color innerStateForeground() {
        // Petrol and red fills need light text; the yellow plate keeps ink.
        return innerStateFill() == palette.getAccentYellow() ? palette.getInk() : Color.WHITE;
    }

    /** Red for the states that mean trouble/stop, else {@code null}. */
    private Color problemAccent() {
        String stateId = snapshot.getCurrentStateId();
        boolean trouble = ResearchStateIds.FAILED.equals(stateId)
                || ResearchStateIds.BLOCKED.equals(stateId)
                || ResearchStateIds.CANCELLED.equals(stateId);
        return trouble ? palette.getAccentRed() : null;
    }

    // ------------------------------------------------------------------ command bar

    private void rebuildCommandBar() {
        commandBar.removeAll();
        commandButtons.clear();
        if (snapshot != null && commandListener != null) {
            List<ResearchCommandType> ordered =
                    new ArrayList<ResearchCommandType>(snapshot.getAllowedCommands());
            ordered.sort(java.util.Comparator.comparingInt(Enum::ordinal)); // stable button order
            for (final ResearchCommandType type : ordered) {
                ComicButton button = new ComicButton(label(type),
                        CRITICAL_COMMANDS.contains(type)
                                ? ComicButton.Accent.CRITICAL : ComicButton.Accent.ACTION);
                button.setToolTipText(label(type)); // narrow drawers may truncate the grid label
                button.setFocusable(false);
                button.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent event) {
                        dispatch(type);
                    }
                });
                commandButtons.add(button);
                commandBar.add(button);
            }
        }
        commandBar.setVisible(commandBar.getComponentCount() > 0);
        commandBar.revalidate();
        commandBar.repaint();
    }

    private void dispatch(ResearchCommandType type) {
        ResearchCommandDispatchResult result = commandListener.commandClicked(type);
        if (result != null && !result.isAccepted()) {
            String detail = result.getDetail();
            feedback.setText(detail == null || detail.isEmpty()
                    ? result.getStatus().toString() : detail);
        }
        // Accepted commands need no handling here: the session fires its state change and the
        // ordinary snapshot listener re-renders timeline + buttons from the new domain state.
    }

    /** "REQUEST_EVIDENCE_REVIEW" → "Request evidence review". */
    static String label(ResearchCommandType type) {
        String words = type.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    // ------------------------------------------------------------------ text projection

    /**
     * The plain-text projection of the current snapshot — the tab's accessible summary and the
     * stable seam for tests (the Swing composition may evolve freely underneath it).
     */
    public String renderedText() {
        return render(snapshot);
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

    private JComponent quietLine(String text) {
        JLabel label = new JLabel(text);
        label.setEnabled(false);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        return label;
    }

    private JComponent detailLine(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(innerStateForeground());
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        return label;
    }

    private static String upper(String id) {
        return id == null ? "" : id.toUpperCase();
    }

    // ------------------------------------------------------------------ test accessors

    List<ComicButton> commandButtonsForTest() {
        return new ArrayList<ComicButton>(commandButtons);
    }

    String feedbackTextForTest() {
        return feedback.getText();
    }
}
