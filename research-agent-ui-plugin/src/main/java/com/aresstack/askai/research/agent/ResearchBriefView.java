package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.store.BriefApprovalStatus;
import com.aresstack.askai.research.store.FileResearchBriefStore;
import com.aresstack.askai.research.store.ResearchBriefArtifact;
import com.aresstack.askai.research.store.ResearchBriefRevision;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * The Fragestellung (research brief) tab: the user-owned WORKING COPY as an editable Markdown draft
 * plus the coarse, user-approved revision history (RA-P6 §1/§11 — a per-turn edit never becomes a
 * revision, only an explicit approval does). "Save draft" folds the editor into the working copy
 * (no-op when nothing changed), "Approve" turns the current draft into the next immutable revision.
 * External updates (the agent's turns) arrive via {@link #reloadFromStore()} — but never while the
 * user has unsaved edits, so the editor cannot jump under their hands. All methods run on the EDT.
 */
public final class ResearchBriefView extends JPanel {

    private final FileResearchBriefStore store;
    private final JTextArea editor = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final JButton save = new JButton("Save draft");
    private final JButton approve = new JButton("Approve");

    private boolean programmaticEdit;
    private boolean dirty;

    public ResearchBriefView(FileResearchBriefStore store) {
        super(new BorderLayout(0, 4));
        this.store = store;
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                onUserEdit();
            }

            public void removeUpdate(DocumentEvent e) {
                onUserEdit();
            }

            public void changedUpdate(DocumentEvent e) {
                onUserEdit();
            }
        });
        add(new JScrollPane(editor), BorderLayout.CENTER);

        save.setToolTipText("Save the draft as the working copy (no revision yet)");
        save.addActionListener(e -> saveDraft());
        approve.setToolTipText("Approve the current draft as the next immutable revision");
        approve.addActionListener(e -> approveDraft());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(save);
        buttons.add(approve);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(buttons, BorderLayout.WEST);
        south.add(status, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        reloadFromStore();
    }

    /** Re-read the store (e.g. after an agent turn) — SKIPPED while the user has unsaved edits. */
    public void reloadFromStore() {
        if (dirty) {
            return; // the user's draft in the editor always wins over an external refresh
        }
        ResearchBriefArtifact artifact = store.load();
        programmaticEdit = true;
        try {
            editor.setText(artifact.effectiveContent());
        } finally {
            programmaticEdit = false;
        }
        dirty = false;
        showState(artifact, null);
    }

    private void saveDraft() {
        boolean changed = store.updateWorkingCopy(editor.getText(), System.currentTimeMillis());
        dirty = false;
        showState(store.load(), changed ? "Draft saved." : "No changes.");
    }

    private void approveDraft() {
        store.updateWorkingCopy(editor.getText(), System.currentTimeMillis());
        ResearchBriefArtifact.Approval approval = store.approveCurrent(System.currentTimeMillis());
        dirty = false;
        String note = approval.getStatus() == BriefApprovalStatus.APPROVED
                ? "Approved as revision " + approval.getRevision().getRevisionNumber() + "."
                : "Already current — no new revision.";
        showState(store.load(), note);
    }

    private void showState(ResearchBriefArtifact artifact, String note) {
        StringBuilder text = new StringBuilder("<html>");
        if (note != null) {
            text.append("<b>").append(escapeHtml(note)).append("</b> &nbsp;");
        }
        ResearchBriefRevision latest = artifact.latestApprovedRevision();
        if (latest != null) {
            text.append("Rev ").append(latest.getRevisionNumber()).append(" approved ")
                    .append(new java.text.SimpleDateFormat("dd/MM/yy HH:mm")
                            .format(new java.util.Date(latest.getApprovedAtMillis())));
        } else {
            text.append("No approved revision yet");
        }
        if (artifact.hasWorkingCopy()) {
            text.append(" · unapproved draft");
        }
        text.append("</html>");
        status.setText(text.toString());
    }

    private void onUserEdit() {
        if (!programmaticEdit) {
            dirty = true;
        }
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
