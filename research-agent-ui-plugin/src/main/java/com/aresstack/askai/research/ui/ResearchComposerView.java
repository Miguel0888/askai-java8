package com.aresstack.askai.research.ui;

import com.aresstack.askai.plugin.api.service.ConversationSurface;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The research composer: the host-provided interaction-mode controls (Yapping/Questing + agent) on top, the
 * active-section context, and a prompt field. Sending posts a user message to the host conversation surface
 * (no backend in Commit 7; Commit 8 turns prompts into a simulated agent run).
 */
final class ResearchComposerView extends JPanel {

    private final ResearchWorkspaceController controller;
    private final ConversationSurface conversation;
    private final JLabel contextLabel = new JLabel();
    private final JTextField input = new JTextField();
    private final AtomicLong messageSequence = new AtomicLong();

    ResearchComposerView(ResearchWorkspaceController controller, JComponent modeControls,
                         ConversationSurface conversation) {
        super(new BorderLayout(6, 4));
        this.controller = controller;
        this.conversation = conversation;

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
        conversation.addUserMessage("m" + messageSequence.incrementAndGet(), text);
        input.setText("");
    }

    void refresh() {
        String section = controller.getActiveSectionId().isEmpty()
                ? "(whole document)" : controller.getActiveSectionId();
        contextLabel.setText("Active section: " + section + "   ·   Phase: " + controller.phase());
    }
}
