package com.aresstack.askai.java8.plugin.host;

import com.aresstack.askai.plugin.api.service.ThemeService;

import javax.swing.UIManager;
import java.awt.Color;
import java.util.concurrent.CopyOnWriteArrayList;

/** {@link ThemeService} backed by Swing's {@link UIManager}. */
public final class AskAiThemeService implements ThemeService {

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<Runnable>();

    @Override
    public Color color(String key, Color fallback) {
        Color value = key == null ? null : UIManager.getColor(key);
        return value == null ? fallback : value;
    }

    @Override
    public boolean isDark() {
        Color background = UIManager.getColor("Panel.background");
        if (background == null) {
            return false;
        }
        // Perceived luminance; below mid-grey counts as a dark theme.
        double luminance = (0.299 * background.getRed() + 0.587 * background.getGreen()
                + 0.114 * background.getBlue()) / 255.0;
        return luminance < 0.5;
    }

    @Override
    public void addThemeChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeThemeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Invoked by the app when the look and feel changes so plugins can repaint. */
    public void fireThemeChanged() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
