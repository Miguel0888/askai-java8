package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * A {@link WorkspaceStateStore} backed by a single properties file, scoped to one (plugin, workspace) via
 * that file's location. Tolerant reads (a missing/corrupt file starts empty); writes never surface an error
 * to the UI. Keys from different plugins/workspaces cannot collide because each has its own file.
 */
public final class FileWorkspaceStateStore implements WorkspaceStateStore {

    private final File file;
    private final Properties values = new Properties();

    public FileWorkspaceStateStore(File file) {
        this.file = file;
        load();
    }

    @Override
    public synchronized String get(String key, String defaultValue) {
        String value = key == null ? null : values.getProperty(key);
        return value == null ? defaultValue : value;
    }

    @Override
    public synchronized boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, null);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    @Override
    public synchronized int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    @Override
    public synchronized void put(String key, String value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            values.remove(key);
        } else {
            values.setProperty(key, value);
        }
        save();
    }

    @Override
    public void putBoolean(String key, boolean value) {
        put(key, Boolean.toString(value));
    }

    @Override
    public void putInt(String key, int value) {
        put(key, Integer.toString(value));
    }

    private void load() {
        if (file == null || !file.isFile()) {
            return;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            values.load(in);
        } catch (RuntimeException | IOException ignored) {
            values.clear(); // missing or corrupt state starts empty
        } finally {
            closeQuietly(in);
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            OutputStream out = null;
            try {
                out = new FileOutputStream(file);
                values.store(out, "AskAI workspace state");
            } finally {
                closeQuietly(out);
            }
        } catch (RuntimeException | IOException ignored) {
            // never break the UI because a state file could not be written
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
