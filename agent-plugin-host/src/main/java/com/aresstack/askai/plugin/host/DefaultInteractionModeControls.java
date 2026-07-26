package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.InteractionModeControls;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.util.List;

/**
 * A view of the shared {@link WorkspaceModeController}: a Yapping/Questing selector and, when Questing is
 * chosen, an agent selector. Purely a view — it holds no state, only reflects the controller and forwards
 * user actions. Several instances can exist (one per workspace composer); each registers its own change
 * listener and detaches it on {@link #dispose()}.
 */
final class DefaultInteractionModeControls implements InteractionModeControls {

    private final WorkspaceModeController controller;
    private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JComboBox<ModeItem> modeCombo = new JComboBox<ModeItem>();
    private final JComboBox<AgentItem> agentCombo = new JComboBox<AgentItem>();
    private final JLabel noAgentsLabel = new JLabel("No agents installed");
    private final Runnable changeListener;
    private boolean updating;

    DefaultInteractionModeControls(WorkspaceModeController controller) {
        this.controller = controller;
        panel.setOpaque(false);

        modeCombo.addItem(new ModeItem(WorkspaceModeEntry.YAPPING_ID, "Yapping"));
        modeCombo.addItem(new ModeItem(WorkspaceModeEntry.QUESTING_ID, "Questing"));
        modeCombo.addActionListener(event -> {
            if (!updating) {
                ModeItem item = (ModeItem) modeCombo.getSelectedItem();
                if (item != null) {
                    controller.setInteractionMode(item.id);
                }
            }
        });
        agentCombo.addActionListener(event -> {
            if (!updating) {
                AgentItem item = (AgentItem) agentCombo.getSelectedItem();
                if (item != null) {
                    controller.selectAgent(item.id);
                }
            }
        });

        panel.add(new JLabel("Mode"));
        panel.add(modeCombo);
        panel.add(agentCombo);
        panel.add(noAgentsLabel);

        this.changeListener = new Runnable() {
            public void run() {
                refresh();
            }
        };
        controller.addChangeListener(changeListener);
        refresh();
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Override
    public void dispose() {
        controller.removeChangeListener(changeListener);
    }

    private void refresh() {
        updating = true;
        try {
            selectMode(controller.getInteractionMode());

            boolean questing = WorkspaceModeEntry.QUESTING_ID.equals(controller.getInteractionMode());
            List<WorkspaceModeEntry> agents = controller.getAvailableAgents();
            boolean hasAgents = !agents.isEmpty();

            DefaultComboBoxModel<AgentItem> model = new DefaultComboBoxModel<AgentItem>();
            for (WorkspaceModeEntry agent : agents) {
                model.addElement(new AgentItem(agent.getId(), agent.getDisplayName()));
            }
            agentCombo.setModel(model);
            selectAgent(controller.getActiveAgentId());

            agentCombo.setVisible(questing && hasAgents);
            noAgentsLabel.setVisible(questing && !hasAgents);
            panel.revalidate();
            panel.repaint();
        } finally {
            updating = false;
        }
    }

    private void selectMode(String id) {
        for (int i = 0; i < modeCombo.getItemCount(); i++) {
            if (modeCombo.getItemAt(i).id.equals(id)) {
                modeCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    private void selectAgent(String id) {
        if (id == null) {
            return;
        }
        for (int i = 0; i < agentCombo.getItemCount(); i++) {
            if (agentCombo.getItemAt(i).id.equals(id)) {
                agentCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    private static final class ModeItem {
        final String id;
        final String label;

        ModeItem(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class AgentItem {
        final String id;
        final String label;

        AgentItem(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
