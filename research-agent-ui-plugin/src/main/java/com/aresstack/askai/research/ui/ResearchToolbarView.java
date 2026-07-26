package com.aresstack.askai.research.ui;

import com.aresstack.askai.research.state.ResearchCommandType;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.FlowLayout;

/** Toolbar / phase bar: shows the phase + run state and offers state-machine-gated actions. */
final class ResearchToolbarView extends JPanel {

    private final ResearchWorkspaceController controller;
    private final JLabel phaseLabel = new JLabel();
    private final JButton nextButton = new JButton("Next step");
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
        add(nextButton);
        add(pauseButton);
        add(resumeButton);
        add(approveButton);
        add(requestChangesButton);
        add(cancelButton);

        nextButton.addActionListener(e -> dispatch(controller.nextStepCommand()));
        pauseButton.addActionListener(e -> dispatch(ResearchCommandType.PAUSE));
        resumeButton.addActionListener(e -> dispatch(ResearchCommandType.RESUME));
        approveButton.addActionListener(e -> dispatch(controller.approveCommand()));
        requestChangesButton.addActionListener(e -> dispatch(controller.requestChangesCommand()));
        cancelButton.addActionListener(e -> dispatch(ResearchCommandType.CANCEL));

        refresh();
    }

    private void dispatch(ResearchCommandType type) {
        if (type != null) {
            controller.dispatch(type);
        }
    }

    void refresh() {
        phaseLabel.setText(controller.phase() + " / " + controller.runState());
        nextButton.setEnabled(canDispatch(controller.nextStepCommand()));
        pauseButton.setEnabled(controller.canDispatch(ResearchCommandType.PAUSE));
        resumeButton.setEnabled(controller.canDispatch(ResearchCommandType.RESUME));
        approveButton.setEnabled(canDispatch(controller.approveCommand()));
        requestChangesButton.setEnabled(canDispatch(controller.requestChangesCommand()));
        cancelButton.setEnabled(controller.canDispatch(ResearchCommandType.CANCEL));
    }

    private boolean canDispatch(ResearchCommandType type) {
        return type != null && controller.canDispatch(type);
    }
}
