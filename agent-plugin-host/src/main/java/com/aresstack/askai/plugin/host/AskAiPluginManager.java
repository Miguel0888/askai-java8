package com.aresstack.askai.plugin.host;

import org.pf4j.DefaultPluginManager;

import java.nio.file.Path;

/**
 * PF4J plugin manager for AskAI: a {@link DefaultPluginManager} pinned to a single controlled plugin root
 * with the host's system version set (so a plugin's {@code Plugin-Requires} can be checked). It only manages
 * PF4J plugin bundles; it creates no UI and knows no research-specific types.
 */
public final class AskAiPluginManager extends DefaultPluginManager {

    public AskAiPluginManager(Path pluginsRoot, String systemVersion) {
        super(pluginsRoot);
        if (systemVersion != null && systemVersion.trim().length() > 0) {
            setSystemVersion(systemVersion.trim());
        }
    }
}
