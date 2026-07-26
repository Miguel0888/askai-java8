package com.aresstack.askai.plugin.api.ui;

import javax.swing.JComponent;
import java.util.Optional;

/**
 * The structured Swing contributions a workspace provides for the host's slots. Only {@code getMainContent()}
 * is required; navigation, activity, toolbar and composer are optional. The host arranges these into a
 * consistent layout rather than letting a plugin take over the whole frame.
 */
public interface WorkspaceLayoutContribution {

    Optional<JComponent> getToolbar();

    Optional<JComponent> getNavigation();

    JComponent getMainContent();

    Optional<JComponent> getActivity();

    Optional<JComponent> getComposer();

    WorkspaceLayoutHints getLayoutHints();
}
