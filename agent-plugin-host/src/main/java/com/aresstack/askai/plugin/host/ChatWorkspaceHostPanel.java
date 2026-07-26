package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspaceCreationRequest;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceHostContext;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.plugin.api.ui.WorkspaceLayoutContribution;
import com.aresstack.askai.plugin.api.ui.WorkspaceLayoutHints;
import com.aresstack.askai.plugin.pf4j.api.WorkspacePluginExtension;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generic host for the Chat view: a mode selector over "Normal Chat" (a built-in host mode, not a
 * plugin) plus the installed, compatible workspace plugins. Only the central chat area is swapped on a mode
 * change; the surrounding AskAI frame is untouched.
 *
 * <p>It never constructs application UI: the normal chat arrives as a generic {@link JComponent} and plugin
 * workspaces come from the {@link WorkspacePluginService}. Selection and persistence use stable ids. Normal
 * Chat is available immediately; the plugin catalog arrives later, on the EDT, without resetting the current
 * selection. A missing/incompatible remembered plugin falls back to {@link WorkspaceMode#NORMAL_CHAT_ID}.</p>
 */
public final class ChatWorkspaceHostPanel extends JPanel {

    private static final String STATE_KEY = "chat.workspaceModeId";
    private static final String NORMAL_CARD = "normal";
    private static final String PLUGIN_CARD = "plugin";

    private final JComponent normalChatComponent;
    private final WorkspacePluginService pluginService;
    private final WorkspaceHostContextFactory hostContextFactory;
    private final UiExecutor uiExecutor;
    private final WorkspaceStateStore hostState;

    private final JComboBox<WorkspaceMode> modeSelector = new JComboBox<WorkspaceMode>();
    private final DefaultComboBoxModel<WorkspaceMode> modeModel = new DefaultComboBoxModel<WorkspaceMode>();
    private final JLabel statusLabel = new JLabel(" ");
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JPanel pluginContainer = new JPanel(new BorderLayout());

    /** Open workspaces kept alive across mode switches, keyed by plugin/mode id (state is preserved). */
    private final Map<String, WorkspaceLifecycleController> openWorkspaces =
            new LinkedHashMap<String, WorkspaceLifecycleController>();

    private String activePluginModeId;
    private boolean updatingSelector;
    private boolean userSwitched;

    public ChatWorkspaceHostPanel(JComponent normalChatComponent, WorkspacePluginService pluginService,
                                  WorkspaceHostContextFactory hostContextFactory, UiExecutor uiExecutor,
                                  WorkspaceStateStore hostState) {
        super(new BorderLayout());
        this.normalChatComponent = normalChatComponent;
        this.pluginService = pluginService;
        this.hostContextFactory = hostContextFactory;
        this.uiExecutor = uiExecutor;
        this.hostState = hostState;
        buildUi();
        showNormalChat();
        startDiscovery();
    }

    private void buildUi() {
        modeModel.addElement(new WorkspaceMode(WorkspaceMode.NORMAL_CHAT_ID, "Normal Chat"));
        modeSelector.setModel(modeModel);
        modeSelector.addActionListener(event -> {
            if (!updatingSelector) {
                onUserSelectedMode((WorkspaceMode) modeSelector.getSelectedItem());
            }
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.add(new JLabel("Mode"));
        bar.add(modeSelector);
        bar.add(statusLabel);

        cardPanel.add(wrap(normalChatComponent), NORMAL_CARD);
        cardPanel.add(pluginContainer, PLUGIN_CARD);

        add(bar, BorderLayout.NORTH);
        add(cardPanel, BorderLayout.CENTER);
    }

    private static JComponent wrap(JComponent component) {
        if (component != null) {
            return component;
        }
        JPanel empty = new JPanel(new BorderLayout());
        empty.add(new JLabel("Chat unavailable", JLabel.CENTER), BorderLayout.CENTER);
        return empty;
    }

    // ------------------------------------------------------------------ discovery

    private void startDiscovery() {
        statusLabel.setText("Loading plugins…");
        pluginService.discoverAsync(new WorkspaceCatalogListener() {
            public void onCatalogReady(List<PluginCatalogEntry> catalog, List<PluginLoadFailure> failures) {
                applyCatalog(catalog);
            }
        });
    }

    private void applyCatalog(List<PluginCatalogEntry> catalog) {
        List<WorkspaceMode> modes = buildModes(catalog, activePluginModeId, currentSelectedId());
        rebuildSelector(modes);
        statusLabel.setText(" ");

        // Restore the remembered plugin only if the user has not already switched and it is now available.
        if (!userSwitched) {
            String desired = hostState == null ? WorkspaceMode.NORMAL_CHAT_ID
                    : hostState.get(STATE_KEY, WorkspaceMode.NORMAL_CHAT_ID);
            if (!WorkspaceMode.NORMAL_CHAT_ID.equals(desired) && containsId(modes, desired)) {
                selectModeById(desired);
                onUserSelectedMode(findMode(modes, desired));
            }
        }
    }

    /** Ordered mode list: Normal Chat first, then selectable catalog entries; a still-active but vanished
     *  plugin is retained so it is not abruptly removed. Visible for tests. */
    static List<WorkspaceMode> buildModes(List<PluginCatalogEntry> catalog, String activePluginModeId,
                                          String selectedId) {
        List<WorkspaceMode> modes = new ArrayList<WorkspaceMode>();
        modes.add(new WorkspaceMode(WorkspaceMode.NORMAL_CHAT_ID, "Normal Chat"));
        boolean activeRetained = activePluginModeId == null;
        if (catalog != null) {
            for (PluginCatalogEntry entry : catalog) {
                if (entry.isSelectable() && entry.getDescriptor() != null) {
                    String id = entry.getDescriptor().getId();
                    if (!containsId(modes, id)) {
                        modes.add(new WorkspaceMode(id, entry.getDescriptor().getDisplayName()));
                    }
                    if (id.equals(activePluginModeId)) {
                        activeRetained = true;
                    }
                }
            }
        }
        // Keep an active workspace visible even if it dropped out of the catalog.
        if (!activeRetained && activePluginModeId != null && !containsId(modes, activePluginModeId)) {
            modes.add(new WorkspaceMode(activePluginModeId, activePluginModeId));
        }
        return modes;
    }

    private void rebuildSelector(List<WorkspaceMode> modes) {
        String keep = currentSelectedId();
        updatingSelector = true;
        try {
            modeModel.removeAllElements();
            for (WorkspaceMode mode : modes) {
                modeModel.addElement(mode);
            }
            WorkspaceMode target = findMode(modes, keep != null && containsId(modes, keep)
                    ? keep : WorkspaceMode.NORMAL_CHAT_ID);
            modeModel.setSelectedItem(target);
        } finally {
            updatingSelector = false;
        }
    }

    // ------------------------------------------------------------------ selection

    private void onUserSelectedMode(WorkspaceMode mode) {
        if (mode == null) {
            return;
        }
        userSwitched = true;
        persistSelection(mode.getId());
        if (mode.isNormalChat()) {
            deactivateActivePlugin();
            showNormalChat();
            return;
        }
        openOrReactivate(mode);
    }

    private void openOrReactivate(WorkspaceMode mode) {
        deactivateActivePlugin();

        WorkspaceLifecycleController controller = openWorkspaces.get(mode.getId());
        if (controller == null) {
            controller = createWorkspace(mode);
            if (controller == null) {
                fallbackToNormalChat("The workspace could not be opened.");
                return;
            }
            openWorkspaces.put(mode.getId(), controller);
        }
        controller.activate();
        if (controller.getState() == WorkspaceInstanceState.FAILED) {
            fallbackToNormalChat("The workspace failed to activate.");
            return;
        }
        showPluginWorkspace(controller);
        activePluginModeId = mode.getId();
    }

    private WorkspaceLifecycleController createWorkspace(WorkspaceMode mode) {
        WorkspacePluginExtension extension = pluginService.getSelectableExtension(mode.getId());
        if (extension == null) {
            return null;
        }
        String workspaceInstanceId = mode.getId() + "#" + Integer.toHexString(System.identityHashCode(this))
                + "-" + openWorkspaces.size();
        WorkspaceHostContext context = hostContextFactory.create(mode.getId(), workspaceInstanceId);
        WorkspaceCreationRequest request = new WorkspaceCreationRequest(workspaceInstanceId, "", null);
        WorkspaceLifecycleController.Result result =
                WorkspaceLifecycleController.open(mode.getId(), extension.getWorkspaceFactory(), request, context);
        return result.isSuccess() ? result.getController() : null;
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
        activePluginModeId = null;
    }

    private void deactivateActivePlugin() {
        if (activePluginModeId != null) {
            WorkspaceLifecycleController controller = openWorkspaces.get(activePluginModeId);
            if (controller != null) {
                controller.deactivate();
            }
        }
    }

    private void fallbackToNormalChat(String message) {
        if (message != null) {
            statusLabel.setText(message);
        }
        persistSelection(WorkspaceMode.NORMAL_CHAT_ID);
        selectModeById(WorkspaceMode.NORMAL_CHAT_ID);
        showNormalChat();
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
            split.setDividerLocation(dividerFromHints(layout.getLayoutHints(), true));
            center = split;
        }
        if (layout.getNavigation().isPresent()) {
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, layout.getNavigation().get(), center);
            split.setDividerLocation(dividerFromHints(layout.getLayoutHints(), false));
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

    private static int dividerFromHints(WorkspaceLayoutHints hints, boolean activity) {
        if (hints == null) {
            return activity ? 640 : 240;
        }
        return activity ? Math.max(320, 900 - hints.getPreferredActivityWidth())
                : Math.max(160, hints.getPreferredNavigationWidth());
    }

    // ------------------------------------------------------------------ shutdown

    /**
     * Final, bounded shutdown for process exit: deactivate the active workspace, force-dispose every open
     * workspace (no close veto is honoured here — a plugin cannot trap process shutdown), then shut down the
     * plugin service. Ordinary user mode switches keep instances alive; only this tears them down.
     */
    public void shutdown() {
        deactivateActivePlugin();
        for (WorkspaceLifecycleController controller : openWorkspaces.values()) {
            try {
                controller.dispose();
            } catch (RuntimeException ignored) {
                // best-effort: one broken dispose must not block the others
            }
        }
        openWorkspaces.clear();
        pluginService.shutdown();
    }

    // ------------------------------------------------------------------ helpers

    private void persistSelection(String id) {
        if (hostState != null) {
            hostState.put(STATE_KEY, id);
        }
    }

    private String currentSelectedId() {
        Object selected = modeModel.getSelectedItem();
        return selected instanceof WorkspaceMode ? ((WorkspaceMode) selected).getId() : null;
    }

    private void selectModeById(String id) {
        updatingSelector = true;
        try {
            for (int i = 0; i < modeModel.getSize(); i++) {
                WorkspaceMode mode = modeModel.getElementAt(i);
                if (mode.getId().equals(id)) {
                    modeModel.setSelectedItem(mode);
                    return;
                }
            }
        } finally {
            updatingSelector = false;
        }
    }

    private static boolean containsId(List<WorkspaceMode> modes, String id) {
        for (WorkspaceMode mode : modes) {
            if (mode.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static WorkspaceMode findMode(List<WorkspaceMode> modes, String id) {
        for (WorkspaceMode mode : modes) {
            if (mode.getId().equals(id)) {
                return mode;
            }
        }
        return modes.get(0);
    }
}
