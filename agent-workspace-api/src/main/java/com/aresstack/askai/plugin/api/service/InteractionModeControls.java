package com.aresstack.askai.plugin.api.service;

import javax.swing.JComponent;

/**
 * A host-provided view of the shared interaction-mode selector (Yapping/Questing and, under Questing, the
 * agent). A workspace embeds one instance in its own composer; several instances may exist (one per
 * workspace composer) but only the active workspace's instance is visible, so the user never sees two
 * selectors at once. All instances reflect and drive the same central controller.
 *
 * <p>Ownership &amp; threading: the workspace owns the instance and must call {@link #dispose()} exactly once
 * (it detaches the controller listener so a hidden/closed workspace stops receiving updates). Use on the EDT.
 * Never reuse the same instance's component in two parents.</p>
 */
public interface InteractionModeControls {

    JComponent getComponent();

    void dispose();
}
