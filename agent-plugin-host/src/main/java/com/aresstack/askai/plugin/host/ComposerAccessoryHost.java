package com.aresstack.askai.plugin.host;

import javax.swing.JComponent;

/**
 * The seam the frame implements to place a composer accessory into the ACTIVE chat tab (above its composer).
 * Keeps {@link AgentComposerAccessoryArea} independent of the concrete chat-tab class: it only says "show this"
 * / "clear it", the frame routes to whichever tab is active and clears the previously-targeted one.
 */
public interface ComposerAccessoryHost {

    void setAccessory(JComponent component);

    void clearAccessory();

    /** Route an accessory-provided composer placeholder to the targeted tab (null = default). */
    void setComposerPlaceholder(String placeholderOrNull);
}
