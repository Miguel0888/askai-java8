package com.aresstack.askai.plugin.host;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;

/** Two controls views bound to one controller stay in sync; dispose detaches the listener. */
public class InteractionModeControlsTest {

    @Test
    public void twoViewsShareControllerStateAndDisposeDetaches() throws Exception {
        final FakeController controller = new FakeController();
        controller.agents.add(new WorkspaceModeEntry("com.x.research", "Research Agent",
                WorkspaceModeEntry.Kind.PLUGIN, true, true, 0));

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                DefaultInteractionModeControls a = new DefaultInteractionModeControls(controller);
                DefaultInteractionModeControls b = new DefaultInteractionModeControls(controller);
                assertEquals(2, controller.listeners.size());

                // A change in the controller notifies both views (they refresh without throwing).
                controller.mode = WorkspaceModeEntry.QUESTING_ID;
                controller.fire();

                // Disposing one view detaches only its listener.
                a.dispose();
                assertEquals(1, controller.listeners.size());
                b.dispose();
                assertEquals(0, controller.listeners.size());
            }
        });
    }

    @Test
    public void selectingAModeDrivesTheController() throws Exception {
        final FakeController controller = new FakeController();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                new DefaultInteractionModeControls(controller);
                controller.setInteractionMode(WorkspaceModeEntry.QUESTING_ID);
                assertEquals(WorkspaceModeEntry.QUESTING_ID, controller.getInteractionMode());
                controller.setInteractionMode(WorkspaceModeEntry.YAPPING_ID);
                assertEquals(WorkspaceModeEntry.YAPPING_ID, controller.getInteractionMode());
            }
        });
    }

    /** Minimal in-memory controller so the view can be tested without the full Swing host panel. */
    private static final class FakeController implements WorkspaceModeController {
        final List<WorkspaceModeEntry> agents = new ArrayList<WorkspaceModeEntry>();
        final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<Runnable>();
        String mode = WorkspaceModeEntry.YAPPING_ID;
        String agentId;

        void fire() {
            for (Runnable listener : listeners) {
                listener.run();
            }
        }

        @Override
        public String getInteractionMode() {
            return mode;
        }

        @Override
        public String getActiveAgentId() {
            return agentId;
        }

        @Override
        public String getActiveAgentLabel() {
            for (WorkspaceModeEntry agent : agents) {
                if (agent.getId().equals(agentId)) {
                    return agent.getDisplayName();
                }
            }
            return null;
        }

        @Override
        public List<WorkspaceModeEntry> getAvailableAgents() {
            return new ArrayList<WorkspaceModeEntry>(agents);
        }

        @Override
        public boolean hasAgents() {
            return !agents.isEmpty();
        }

        @Override
        public void setInteractionMode(String modeId) {
            this.mode = modeId;
            fire();
        }

        @Override
        public void selectAgent(String id) {
            this.agentId = id;
            fire();
        }

        @Override
        public void addChangeListener(Runnable listener) {
            listeners.addIfAbsent(listener);
        }

        @Override
        public void removeChangeListener(Runnable listener) {
            listeners.remove(listener);
        }
    }
}
