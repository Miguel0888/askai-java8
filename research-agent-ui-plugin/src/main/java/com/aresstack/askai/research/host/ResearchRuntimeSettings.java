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
    static final String KEY_ALLOW_PRIVATE = "research.runtime.allowPrivateNetworks";
    static final String KEY_LANGUAGE = "research.runtime.language";
    static final String KEY_RERANKER_MODEL = "research.runtime.selectedRerankerModel";

    /** Agent language code ("en" default, "de" German) — read directly, independent of the path model. */
    public static String loadLanguage(WorkspaceStateStore store) {
        return store == null ? "en" : store.get(KEY_LANGUAGE, "en");
    }

    public static void saveLanguage(WorkspaceStateStore store, String code) {
        if (store != null) {
            store.put(KEY_LANGUAGE, "de".equalsIgnoreCase(code) ? "de" : "en");
        }
    }

    private final ResearchBackendMode mode;
    private final String agentJavaExecutable;
    private final String agentJar;
    private final String sidecarJavaExecutable;
    private final String sidecarJar;
    private final String browserChannel;
    private final boolean headless;
    private final String searchUrlTemplate;
    private final boolean allowPrivateNetworks;
    /** The EXPLICITLY selected virtual reranker model id ("" = nothing selected yet). */
    private final String selectedRerankerModel;

    public ResearchRuntimeSettings(ResearchBackendMode mode, String agentJavaExecutable, String agentJar,
                                   String sidecarJavaExecutable, String sidecarJar, String browserChannel,
                                   boolean headless, String searchUrlTemplate) {
        this(mode, agentJavaExecutable, agentJar, sidecarJavaExecutable, sidecarJar, browserChannel,
                headless, searchUrlTemplate, false);
    }

    /** @param allowPrivateNetworks development-only override of the strict URL policy (default false). */
    public ResearchRuntimeSettings(ResearchBackendMode mode, String agentJavaExecutable, String agentJar,
                                   String sidecarJavaExecutable, String sidecarJar, String browserChannel,
                                   boolean headless, String searchUrlTemplate,
                                   boolean allowPrivateNetworks) {
        this(mode, agentJavaExecutable, agentJar, sidecarJavaExecutable, sidecarJar, browserChannel,
                headless, searchUrlTemplate, allowPrivateNetworks, "");
    }

    /** @param selectedRerankerModel the explicitly selected virtual reranker model id ("" = none). */
    public ResearchRuntimeSettings(ResearchBackendMode mode, String agentJavaExecutable, String agentJar,
                                   String sidecarJavaExecutable, String sidecarJar, String browserChannel,
                                   boolean headless, String searchUrlTemplate,
                                   boolean allowPrivateNetworks, String selectedRerankerModel) {
        this.mode = mode == null ? ResearchBackendMode.FAKE : mode;
        this.agentJavaExecutable = nullToEmpty(agentJavaExecutable);
        this.agentJar = nullToEmpty(agentJar);
        this.sidecarJavaExecutable = nullToEmpty(sidecarJavaExecutable);
        this.sidecarJar = nullToEmpty(sidecarJar);
        this.browserChannel = browserChannel == null || browserChannel.isEmpty() ? "chrome" : browserChannel;
        this.headless = headless;
        this.searchUrlTemplate = nullToEmpty(searchUrlTemplate);
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.selectedRerankerModel = nullToEmpty(selectedRerankerModel);
    }

    public static ResearchRuntimeSettings defaults() {
        return new ResearchRuntimeSettings(ResearchBackendMode.FAKE, "", "", "", "", "chrome", true, "");
    }

    /** True when a mode value was ever persisted (the FAKE developer override requires an explicit one). */
    public static boolean hasPersistedMode(WorkspaceStateStore store) {
        return store != null && store.get(KEY_MODE, null) != null;
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
                store.get(KEY_SEARCH_URL, ""),
                store.getBoolean(KEY_ALLOW_PRIVATE, false),
                store.get(KEY_RERANKER_MODEL, ""));
    }

    /**
     * ONE-TIME initial selection/migration: when NO reranker selection was ever persisted and exactly
     * ONE installed rerank model exists, that model becomes the persisted selection. Afterwards the
     * persisted selection is the single truth — it is never silently replaced, even when models are
     * installed or removed later (an unusable selection fails the productive session start visibly).
     */
    public static void migrateInitialRerankerSelection(WorkspaceStateStore store,
                                                       List<String> installedRerankModels) {
        if (store == null || installedRerankModels == null || installedRerankModels.size() != 1) {
            return;
        }
        String persisted = store.get(KEY_RERANKER_MODEL, "");
        if (persisted == null || persisted.trim().isEmpty()) {
            store.put(KEY_RERANKER_MODEL, installedRerankModels.get(0));
        }
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
        store.putBoolean(KEY_ALLOW_PRIVATE, allowPrivateNetworks);
        store.put(KEY_RERANKER_MODEL, selectedRerankerModel);
    }

    public ResearchBackendMode getMode() { return mode; }
    public String getAgentJavaExecutable() { return agentJavaExecutable; }
    public String getAgentJar() { return agentJar; }
    public String getSidecarJavaExecutable() { return sidecarJavaExecutable; }
    public String getSidecarJar() { return sidecarJar; }
    public String getBrowserChannel() { return browserChannel; }
    public boolean isHeadless() { return headless; }
    public String getSearchUrlTemplate() { return searchUrlTemplate; }
    public boolean isAllowPrivateNetworks() { return allowPrivateNetworks; }
    public String getSelectedRerankerModel() { return selectedRerankerModel; }

    /** The productive runtime config. The URL policy is strict unless the explicit, persisted
     * development-only override is set — never an implicit relaxation. */
    public ResearchRuntimeConfig toRuntimeConfig() {
        return new ResearchRuntimeConfig(agentJavaExecutable, agentJar, sidecarJavaExecutable, sidecarJar,
                browserChannel, headless, allowPrivateNetworks,
                searchUrlTemplate.isEmpty() ? null : searchUrlTemplate, selectedRerankerModel);
    }

    /**
     * The runtime rules ({@link ResearchRuntimeConfig#validate()}) PLUS the product-level requirements:
     * the autonomous loop always seeds with {@code web_search}, so a search provider URL is mandatory for
     * the productive mode (it is not an optional nicety), and the thin sidecar jar needs its sibling
     * {@code lib/} directory. Empty = usable.
     */
    public List<String> validateProductive() {
        List<String> problems = new java.util.ArrayList<String>(toRuntimeConfig().validate());
        if (searchUrlTemplate.isEmpty()) {
            problems.add("search provider URL is required (autonomous research starts with web_search), "
                    + "e.g. https://www.bing.com/search?q={query}");
        }
        if (!sidecarJar.isEmpty() && new java.io.File(sidecarJar).isFile()) {
            java.io.File lib = new java.io.File(new java.io.File(sidecarJar).getParentFile(), "lib");
            if (!lib.isDirectory()) {
                problems.add("sidecar lib directory missing next to the jar: " + lib);
            }
        }
        return problems;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
