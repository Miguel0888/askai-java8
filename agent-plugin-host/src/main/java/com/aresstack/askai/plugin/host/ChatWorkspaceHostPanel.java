package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspaceCreationRequest;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceHostContext;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.plugin.api.ui.WorkspaceLayoutContribution;
import com.aresstack.askai.plugin.api.ui.WorkspaceLayoutHints;
import com.aresstack.askai.plugin.pf4j.api.WorkspacePluginExtension;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Chat view content host. It renders <b>no visible selector of its own</b> (the composer owns the
 * single, two-level selector: interaction mode Yapping/Questing, and — under Questing — the agent). It only
 * swaps the central content and manages workspace lifecycle, exposing a controller surface the app binds to
 * the composer selectors.
 *
 * <p>Two persisted dimensions: the interaction mode ({@link WorkspaceModeEntry#YAPPING_ID}/
 * {@link WorkspaceModeEntry#QUESTING_ID}) and, for Questing, the active agent id. Yapping shows the normal
 * chat; Questing activates the selected agent's plugin workspace. Discovery updates only the agent list.
 * If the active agent vanishes, Questing keeps another available agent, or falls back to Yapping when none
 * remain.</p>
 */
public final class ChatWorkspaceHostPanel extends JPanel implements WorkspaceModeController {

    private static final String STATE_INTERACTION_MODE = "chat.interactionMode";
    private static final String STATE_QUESTING_AGENT = "chat.questingAgentId";
    private static final String NORMAL_CARD = "normal";
    private static final String PLUGIN_CARD = "plugin";

    private final JComponent normalChatComponent;
    private final WorkspacePluginService pluginService;
    private final WorkspaceHostContextFactory hostContextFactory;
    private final UiExecutor uiExecutor;
    private final WorkspaceStateStore hostState;
    private final NotificationService notificationService;

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JPanel pluginContainer = new JPanel(new BorderLayout());

    private final Map<String, WorkspaceLifecycleController> openWorkspaces =
            new LinkedHashMap<String, WorkspaceLifecycleController>();
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<Runnable>();

    private List<WorkspaceModeEntry> agents = new ArrayList<WorkspaceModeEntry>();
    private String interactionMode;
    private String activeAgentId;
    private String activeWorkspaceAgentId;
    private boolean userSwitched;
    // New agent model (Commit 11): when set, Questing keeps the SHARED chat and routes to an agent session
    // instead of swapping to a standalone plugin workspace. The legacy workspace path stays as a fallback
    // for plugins that expose only a WorkspacePluginExtension.
    private AgentSessionCoordinator agentCoordinator;

    public ChatWorkspaceHostPanel(JComponent normalChatComponent, WorkspacePluginService pluginService,
                                  WorkspaceHostContextFactory hostContextFactory, UiExecutor uiExecutor,
                                  WorkspaceStateStore hostState, NotificationService notificationService) {
        super(new BorderLayout());
        this.normalChatComponent = normalChatComponent;
        this.pluginService = pluginService;
        this.hostContextFactory = hostContextFactory;
        this.uiExecutor = uiExecutor;
        this.hostState = hostState;
        this.notificationService = notificationService;
        this.interactionMode = hostState == null ? WorkspaceModeEntry.YAPPING_ID
                : hostState.get(STATE_INTERACTION_MODE, WorkspaceModeEntry.YAPPING_ID);
        this.activeAgentId = hostState == null ? null : hostState.get(STATE_QUESTING_AGENT, null);

        cardPanel.add(wrap(normalChatComponent), NORMAL_CARD);
        cardPanel.add(pluginContainer, PLUGIN_CARD);
        add(cardPanel, BorderLayout.CENTER);
        showNormalChat(); // always start on the chat; Questing is restored once agents are known
        startDiscovery();
    }

    /**
     * Wire the new agent model. When set, Questing routes to an agent session over the shared chat instead of
     * swapping to a standalone workspace. Must be called before the first Questing activation.
     */
    public void setAgentSessionCoordinator(AgentSessionCoordinator coordinator) {
        this.agentCoordinator = coordinator;
    }

    // ------------------------------------------------------------------ controller surface (bound by app)

    public String getInteractionMode() {
        return interactionMode;
    }

    public String getActiveAgentId() {
        return activeAgentId;
    }

    public boolean hasAgents() {
        return !agents.isEmpty();
    }

    /** @return a snapshot of the currently available agents (Questing sub-selector source). */
    public List<WorkspaceModeEntry> getAvailableAgents() {
        return new ArrayList<WorkspaceModeEntry>(agents);
    }

    @Override
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void fireChange() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    /** Yapping shows the normal chat; Questing activates the current/last agent (or reports none). */
    @Override
    public void setInteractionMode(String modeId) {
        userSwitched = true;
        if (WorkspaceModeEntry.QUESTING_ID.equals(modeId)) {
            interactionMode = WorkspaceModeEntry.QUESTING_ID;
            persist(STATE_INTERACTION_MODE, interactionMode);
            activateQuesting();
        } else {
            interactionMode = WorkspaceModeEntry.YAPPING_ID;
            persist(STATE_INTERACTION_MODE, interactionMode);
            if (agentCoordinator != null) {
                agentCoordinator.deactivateActive();
            }
            deactivateActiveWorkspace();
            showNormalChat();
        }
        fireChange();
    }

    /** Selects the Questing agent; activates it immediately when Questing is the current mode. */
    @Override
    public void selectAgent(String agentId) {
        userSwitched = true;
        activeAgentId = agentId;
        persist(STATE_QUESTING_AGENT, agentId);
        if (WorkspaceModeEntry.QUESTING_ID.equals(interactionMode)) {
            activateQuesting();
        }
        fireChange();
    }

    // ------------------------------------------------------------------ discovery

    private void startDiscovery() {
        pluginService.addCatalogListener(new WorkspaceCatalogListener() {
            public void onCatalogReady(List<PluginCatalogEntry> catalog, List<PluginLoadFailure> failures) {
                applyCatalog(catalog);
            }
        });
        pluginService.refreshAsync();
    }

    private void applyCatalog(List<PluginCatalogEntry> catalog) {
        agents = buildAgents(catalog);
        // Close agent sessions whose plugin is no longer selectable (disabled/removed); survivors stay open.
        if (agentCoordinator != null) {
            List<String> ids = new ArrayList<String>();
            for (WorkspaceModeEntry agent : agents) {
                ids.add(agent.getId());
            }
            agentCoordinator.retainOnly(ids);
        }
        fireChange();
        // Restore a persisted Questing selection once agents are known, unless the user already switched.
        if (WorkspaceModeEntry.QUESTING_ID.equals(interactionMode) && !userSwitched) {
            activateQuesting();
        } else if (WorkspaceModeEntry.QUESTING_ID.equals(interactionMode)
                && ((activeWorkspaceAgentId != null && !containsAgent(agents, activeWorkspaceAgentId))
                    || (activeAgentId != null && !containsAgent(agents, activeAgentId)))) {
            // The active agent (workspace or agent-model) disappeared: re-resolve or fall back to Yapping.
            activateQuesting();
        }
    }

    /** Builds the agent list from selectable plugin catalog entries. Visible for tests. */
    static List<WorkspaceModeEntry> buildAgents(List<PluginCatalogEntry> catalog) {
        List<WorkspaceModeEntry> result = new ArrayList<WorkspaceModeEntry>();
        if (catalog == null) {
            return result;
        }
        for (PluginCatalogEntry entry : catalog) {
            if (entry.isSelectable() && entry.getDescriptor() != null
                    && !containsAgent(result, entry.getDescriptor().getId())) {
                result.add(new WorkspaceModeEntry(entry.getDescriptor().getId(),
                        entry.getDescriptor().getDisplayName(), WorkspaceModeEntry.Kind.PLUGIN,
                        true, true, entry.getDescriptor().getDisplayOrder()));
            }
        }
        return result;
    }

    /**
     * Chooses the agent to run under Questing: the desired one if still available, else the first available,
     * else {@code null} (no agents → caller falls back to Yapping). Visible for tests.
     */
    static String resolveQuestingAgent(String desiredId, List<WorkspaceModeEntry> agents) {
        if (agents == null || agents.isEmpty()) {
            return null;
        }
        if (desiredId != null && containsAgent(agents, desiredId)) {
            return desiredId;
        }
        return agents.get(0).getId();
    }

    // ------------------------------------------------------------------ activation

    private void activateQuesting() {
        String agentId = resolveQuestingAgent(activeAgentId, agents);
        if (agentId == null) {
            notify(NotificationService.Severity.INFO,
                    "No agents installed. Install an agent plugin to use Questing.");
            if (agentCoordinator != null) {
                agentCoordinator.deactivateActive();
            }
            deactivateActiveWorkspace();
            showNormalChat();
            return;
        }
        if (!agentId.equals(activeAgentId)) {
            activeAgentId = agentId;
            persist(STATE_QUESTING_AGENT, agentId);
        }
        // New model: keep the shared chat and route to the agent session. Only fall back to the standalone
        // workspace path for legacy plugins that have no agent extension.
        if (agentCoordinator != null && agentCoordinator.canHandle(agentId)) {
            deactivateActiveWorkspace();
            showNormalChat();
            agentCoordinator.setActiveAgent(agentId);
            return;
        }
        openOrReactivate(agentId);
    }

    private void openOrReactivate(String agentId) {
        if (!agentId.equals(activeWorkspaceAgentId)) {
            deactivateActiveWorkspace();
        }
        WorkspaceLifecycleController controller = openWorkspaces.get(agentId);
        if (controller == null) {
            controller = createWorkspace(agentId);
            if (controller == null) {
                notify(NotificationService.Severity.ERROR, "The agent workspace could not be opened.");
                fallbackToYapping();
                return;
            }
            openWorkspaces.put(agentId, controller);
        }
        controller.activate();
        if (controller.getState() == WorkspaceInstanceState.FAILED) {
            notify(NotificationService.Severity.ERROR, "The agent workspace failed to activate.");
            fallbackToYapping();
            return;
        }
        showPluginWorkspace(controller);
        activeWorkspaceAgentId = agentId;
    }

    private WorkspaceLifecycleController createWorkspace(String agentId) {
        WorkspacePluginExtension extension = pluginService.getSelectableExtension(agentId);
        if (extension == null) {
            return null;
        }
        String workspaceInstanceId = agentId + "#" + Integer.toHexString(System.identityHashCode(this));
        WorkspaceHostContext context = hostContextFactory.create(agentId, workspaceInstanceId);
        WorkspaceCreationRequest request = new WorkspaceCreationRequest(workspaceInstanceId, "", null);
        WorkspaceLifecycleController.Result result =
                WorkspaceLifecycleController.open(agentId, extension.getWorkspaceFactory(), request, context);
        return result.isSuccess() ? result.getController() : null;
    }

    private void fallbackToYapping() {
        interactionMode = WorkspaceModeEntry.YAPPING_ID;
        persist(STATE_INTERACTION_MODE, interactionMode);
        showNormalChat();
        fireChange();
    }

    private void showPluginWorkspace(WorkspaceLifecycleController controller) {
        pluginContainer.removeAll();
        pluginContainer.add(assembleLayout(controller.getInstance().getLayout()), BorderLayout.CENTER);
        pluginContainer.revalidate();
        pluginContainer.repaint();
        cards.show(cardPanel, PLUGIN_CARD);
    }

    private void showNormalChat() {
        cards.show(cardPanel, NORMAL_CARD);
    }

    private void deactivateActiveWorkspace() {
        if (activeWorkspaceAgentId != null) {
            WorkspaceLifecycleController controller = openWorkspaces.get(activeWorkspaceAgentId);
            if (controller != null) {
                controller.deactivate();
            }
            activeWorkspaceAgentId = null;
        }
    }

    // ------------------------------------------------------------------ layout assembly

    private JComponent assembleLayout(WorkspaceLayoutContribution layout) {
        if (layout == null) {
            return new JPanel();
        }
        JComponent main = layout.getMainContent();
        JComponent center = main == null ? new JPanel() : main;
        if (layout.getActivity().isPresent()) {
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, layout.getActivity().get());
            split.setResizeWeight(1.0d);
            split.setDividerLocation(activityDivider(layout.getLayoutHints()));
            center = split;
        }
        if (layout.getNavigation().isPresent()) {
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, layout.getNavigation().get(), center);
            split.setDividerLocation(navigationDivider(layout.getLayoutHints()));
            center = split;
        }
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        if (layout.getToolbar().isPresent()) {
            panel.add(layout.getToolbar().get(), BorderLayout.NORTH);
        }
        panel.add(center, BorderLayout.CENTER);
        if (layout.getComposer().isPresent()) {
            panel.add(layout.getComposer().get(), BorderLayout.SOUTH);
        }
        return panel;
    }

    private static int navigationDivider(WorkspaceLayoutHints hints) {
        return hints == null ? 240 : Math.max(160, hints.getPreferredNavigationWidth());
    }

    private static int activityDivider(WorkspaceLayoutHints hints) {
        return hints == null ? 640 : Math.max(320, 900 - hints.getPreferredActivityWidth());
    }

    // ------------------------------------------------------------------ shutdown

    /**
     * Final, bounded shutdown for process exit: deactivate the active workspace and force-dispose every open
     * one (no close veto is honoured here), then shut down the plugin service. Ordinary mode switches keep
     * instances alive; only this tears them down.
     */
    public void shutdown() {
        if (agentCoordinator != null) {
            agentCoordinator.shutdown();
        }
        deactivateActiveWorkspace();
        for (WorkspaceLifecycleController controller : openWorkspaces.values()) {
            try {
                controller.dispose();
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        openWorkspaces.clear();
        pluginService.shutdown();
    }

    // ------------------------------------------------------------------ helpers

    private void persist(String key, String value) {
        if (hostState != null) {
            hostState.put(key, value);
        }
    }

    private void notify(NotificationService.Severity severity, String message) {
        if (notificationService != null) {
            notificationService.notify(severity, message);
        }
    }

    private static boolean containsAgent(List<WorkspaceModeEntry> agents, String id) {
        for (WorkspaceModeEntry agent : agents) {
            if (agent.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static JComponent wrap(JComponent component) {
        if (component != null) {
            return component;
        }
        JPanel empty = new JPanel(new BorderLayout());
        return empty;
    }
}
