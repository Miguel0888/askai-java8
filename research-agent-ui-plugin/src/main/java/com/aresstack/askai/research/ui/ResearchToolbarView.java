package com.aresstack.askai.research.ui;

import com.aresstack.askai.research.state.ResearchCommandType;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.FlowLayout;

/**
 * Toolbar / phase bar: shows the phase + run state and offers the human-in-the-loop actions. The run itself
 * advances automatically inside the backend, so there is no manual "next step"; the buttons here are the
 * control surface (pause/resume/cancel) plus the approval gate (approve / request changes).
 */
final class ResearchToolbarView extends JPanel {

    private final ResearchWorkspaceController controller;
    private final JLabel phaseLabel = new JLabel();
    private final JButton pauseButton = new JButton("Pause");
    private final JButton resumeButton = new JButton("Resume");
    private final JButton approveButton = new JButton("Approve");
    private final JButton requestChangesButton = new JButton("Request changes");
    private final JButton cancelButton = new JButton("Cancel");

    ResearchToolbarView(ResearchWorkspaceController controller) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        this.controller = controller;
        setOpaque(false);

        add(new JLabel("Research Agent  ·  Demo project  ·  "));
        add(phaseLabel);
        add(pauseButton);
        add(resumeButton);
        add(approveButton);
        add(requestChangesButton);
        add(cancelButton);

        pauseButton.addActionListener(e -> controller.pause());
        resumeButton.addActionListener(e -> controller.resume());
        approveButton.addActionListener(e -> controller.approveCurrent());
        requestChangesButton.addActionListener(e -> controller.rejectCurrent("Please revise."));
        cancelButton.addActionListener(e -> controller.cancel());

        refresh();
    }

    void refresh() {
        phaseLabel.setText(controller.phase() + " / " + controller.runState());
        pauseButton.setEnabled(controller.canDispatch(ResearchCommandType.PAUSE));
        resumeButton.setEnabled(controller.canDispatch(ResearchCommandType.RESUME));
        approveButton.setEnabled(controller.hasPendingApproval());
        requestChangesButton.setEnabled(controller.hasPendingApproval());
        cancelButton.setEnabled(controller.canDispatch(ResearchCommandType.CANCEL));
    }
}
