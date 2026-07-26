package com.aresstack.askai.plugin.api.service;

import java.awt.Color;

/**
 * Read-only access to the host theme so plugins avoid hard-coded light-theme colors and can react to theme
 * changes. Colors resolve from UIManager/FlatLaf; a listener fires when the theme switches.
 */
public interface ThemeService {

    /** @return a themed color for the given UIManager-style key, or {@code fallback} if unresolved. */
    Color color(String key, Color fallback);

    boolean isDark();

    void addThemeChangeListener(Runnable listener);

    void removeThemeChangeListener(Runnable listener);
}
