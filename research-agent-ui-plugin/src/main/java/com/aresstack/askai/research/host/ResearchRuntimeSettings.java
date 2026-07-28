package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.acp.ResearchBackendMode;

import java.util.List;

/**
 * The TYPED, persisted configuration of the research backend — the single mapper between the UI, the
 * {@link WorkspaceStateStore} and {@link ResearchRuntimeConfig}. No free-form maps, no system properties.
 * {@code FAKE} is the deterministic clickdummy/development mode; {@code ACP} is the productive mode. There is
 * no silent fallback in either direction: an unusable productive configuration fails session creation
 * visibly ({@link ResearchAgentSessionFactory}).
 */
public final class ResearchRuntimeSettings {

    static final String KEY_MODE = "research.runtime.mode";
    static final String KEY_AGENT_JAVA = "research.runtime.agentJava";
    static final String KEY_AGENT_JAR = "research.runtime.agentJar";
    static final String KEY_SIDECAR_JAVA = "research.runtime.sidecarJava";
    static final String KEY_SIDECAR_JAR = "research.runtime.sidecarJar";
    static final String KEY_BROWSER_CHANNEL = "research.runtime.browserChannel";
    static final String KEY_HEADLESS = "research.runtime.headless";
    static final String KEY_SEARCH_URL = "research.runtime.searchUrl";

    private final ResearchBackendMode mode;
    private final String agentJavaExecutable;
    private final String agentJar;
    private final String sidecarJavaExecutable;
    private final String sidecarJar;
    private final String browserChannel;
    private final boolean headless;
    private final String searchUrlTemplate;

    public ResearchRuntimeSettings(ResearchBackendMode mode, String agentJavaExecutable, String agentJar,
                                   String sidecarJavaExecutable, String sidecarJar, String browserChannel,
                                   boolean headless, String searchUrlTemplate) {
        this.mode = mode == null ? ResearchBackendMode.FAKE : mode;
        this.agentJavaExecutable = nullToEmpty(agentJavaExecutable);
        this.agentJar = nullToEmpty(agentJar);
        this.sidecarJavaExecutable = nullToEmpty(sidecarJavaExecutable);
        this.sidecarJar = nullToEmpty(sidecarJar);
        this.browserChannel = browserChannel == null || browserChannel.isEmpty() ? "chrome" : browserChannel;
        this.headless = headless;
        this.searchUrlTemplate = nullToEmpty(searchUrlTemplate);
    }

    public static ResearchRuntimeSettings defaults() {
        return new ResearchRuntimeSettings(ResearchBackendMode.FAKE, "", "", "", "", "chrome", true, "");
    }

    public static ResearchRuntimeSettings load(WorkspaceStateStore store) {
        if (store == null) {
            return defaults(); // a host without persisted state runs the visible clickdummy mode
        }
        ResearchBackendMode mode;
        try {
            mode = ResearchBackendMode.valueOf(store.get(KEY_MODE, ResearchBackendMode.FAKE.name()));
        } catch (IllegalArgumentException unknownValue) {
            mode = ResearchBackendMode.FAKE; // unknown persisted value → the safe, visible clickdummy mode
        }
        return new ResearchRuntimeSettings(mode,
                store.get(KEY_AGENT_JAVA, ""),
                store.get(KEY_AGENT_JAR, ""),
                store.get(KEY_SIDECAR_JAVA, ""),
                store.get(KEY_SIDECAR_JAR, ""),
                store.get(KEY_BROWSER_CHANNEL, "chrome"),
                store.getBoolean(KEY_HEADLESS, true),
                store.get(KEY_SEARCH_URL, ""));
    }

    public void save(WorkspaceStateStore store) {
        store.put(KEY_MODE, mode.name());
        store.put(KEY_AGENT_JAVA, agentJavaExecutable);
        store.put(KEY_AGENT_JAR, agentJar);
        store.put(KEY_SIDECAR_JAVA, sidecarJavaExecutable);
        store.put(KEY_SIDECAR_JAR, sidecarJar);
        store.put(KEY_BROWSER_CHANNEL, browserChannel);
        store.putBoolean(KEY_HEADLESS, headless);
        store.put(KEY_SEARCH_URL, searchUrlTemplate);
    }

    public ResearchBackendMode getMode() { return mode; }
    public String getAgentJavaExecutable() { return agentJavaExecutable; }
    public String getAgentJar() { return agentJar; }
    public String getSidecarJavaExecutable() { return sidecarJavaExecutable; }
    public String getSidecarJar() { return sidecarJar; }
    public String getBrowserChannel() { return browserChannel; }
    public boolean isHeadless() { return headless; }
    public String getSearchUrlTemplate() { return searchUrlTemplate; }

    /** The productive runtime config (strict URL policy — private networks are never allowed from the UI). */
    public ResearchRuntimeConfig toRuntimeConfig() {
        return new ResearchRuntimeConfig(agentJavaExecutable, agentJar, sidecarJavaExecutable, sidecarJar,
                browserChannel, headless, false, searchUrlTemplate.isEmpty() ? null : searchUrlTemplate);
    }

    /** Same rules as the runtime itself ({@link ResearchRuntimeConfig#validate()}). Empty = usable. */
    public List<String> validateProductive() {
        return toRuntimeConfig().validate();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
