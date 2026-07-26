package com.aresstack.askai.research.ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;

/**
 * The research composer: the host-provided interaction-mode controls (Yapping/Questing + agent) on top, the
 * active-section context, and a prompt field. Sending submits the prompt to the backend (tied to the active
 * section); the resulting user/assistant messages arrive back as backend events on the conversation surface.
 */
final class ResearchComposerView extends JPanel {

    private final ResearchWorkspaceController controller;
    private final JLabel contextLabel = new JLabel();
    private final JTextField input = new JTextField();

    ResearchComposerView(ResearchWorkspaceController controller, JComponent modeControls) {
        super(new BorderLayout(6, 4));
        this.controller = controller;

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(modeControls, BorderLayout.WEST);
        top.add(contextLabel, BorderLayout.EAST);

        JButton send = new JButton("Send");
        send.addActionListener(e -> send());
        input.addActionListener(e -> send());

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(input, BorderLayout.CENTER);
        row.add(send, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(row, BorderLayout.CENTER);
        refresh();
    }

    private void send() {
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        controller.submitPrompt(text);
        input.setText("");
    }

    void refresh() {
        String section = controller.getActiveSectionId().isEmpty()
                ? "(whole document)" : controller.getActiveSectionId();
        contextLabel.setText("Active section: " + section + "   ·   Phase: " + controller.phase());
    }
}
