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

    static final String KEY_LLM_NARRATION = "research.runtime.llmNarration";

    /** Agent language code ("en" default, "de" German) — read directly, independent of the path model. */
    public static String loadLanguage(WorkspaceStateStore store) {
        return store == null ? "en" : store.get(KEY_LANGUAGE, "en");
    }

    public static void saveLanguage(WorkspaceStateStore store, String code) {
        if (store != null) {
            store.put(KEY_LANGUAGE, "de".equalsIgnoreCase(code) ? "de" : "en");
        }
    }

    /**
     * LLM narration toggle (default OFF until burned in): when on AND the host provides an inference
     * port, milestone texts are phrased by the main model — always validated, always with the static
     * fallback. Read directly like the language, independent of the path model.
     */
    public static boolean loadLlmNarration(WorkspaceStateStore store) {
        return store != null && store.getBoolean(KEY_LLM_NARRATION, false);
    }

    public static void saveLlmNarration(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_LLM_NARRATION, enabled);
        }
    }

    static final String KEY_ALWAYS_SUGGEST = "research.runtime.alwaysOfferSearchSuggestions";

    /**
     * "Immer Suchvorschläge anbieten" (DEFAULT OFF = the long-standing behaviour): when on, the scoping
     * assistant treats search suggestions as the user's orientation map — a broad/unclear scope always
     * comes with 3-5 direction-opening suggestions accompanying the clarifying question. Applies to NEW
     * sessions (the flag travels to the agent process at launch).
     */
    public static boolean loadAlwaysOfferSearchSuggestions(WorkspaceStateStore store) {
        return store != null && store.getBoolean(KEY_ALWAYS_SUGGEST, false);
    }

    public static void saveAlwaysOfferSearchSuggestions(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_ALWAYS_SUGGEST, enabled);
        }
    }

    static final String KEY_AGENT_MAX_OUTPUT_TOKENS = "research.runtime.agentMaxOutputTokens";
    /** The default answer budget per agent model turn — generous enough for the longest contract (review). */
    public static final int DEFAULT_AGENT_MAX_OUTPUT_TOKENS = 4096;

    /**
     * The output-token budget the agent grants the model PER TURN (the longest contracted answer is the
     * post-search source review). A budget, not a target: short turns stay short. Configurable so a too
     * small value never has to be fixed in code again; an unparsable or non-positive persisted value falls
     * back to the default. Applies to NEW sessions.
     */
    public static int loadAgentMaxOutputTokens(WorkspaceStateStore store) {
        if (store == null) {
            return DEFAULT_AGENT_MAX_OUTPUT_TOKENS;
        }
        try {
            int value = Integer.parseInt(store.get(KEY_AGENT_MAX_OUTPUT_TOKENS,
                    String.valueOf(DEFAULT_AGENT_MAX_OUTPUT_TOKENS)).trim());
            return value > 0 ? value : DEFAULT_AGENT_MAX_OUTPUT_TOKENS;
        } catch (NumberFormatException invalid) {
            return DEFAULT_AGENT_MAX_OUTPUT_TOKENS;
        }
    }

    public static void saveAgentMaxOutputTokens(WorkspaceStateStore store, int tokens) {
        if (store != null && tokens > 0) {
            store.put(KEY_AGENT_MAX_OUTPUT_TOKENS, String.valueOf(tokens));
        }
    }

    static final String KEY_BOT_CONTROL = "research.runtime.botControlMcp";

    /**
     * Bot-control MCP toggle (DEFAULT ON): whether a new session opens the bot-control endpoint
     * (run_command/session_state/chat_history) and writes service-endpoint.json. OFF = the session is
     * GUI-only; no endpoint is registered and no connection file is written. Applies to NEW sessions.
     */
    public static boolean loadBotControlMcp(WorkspaceStateStore store) {
        return store == null || store.getBoolean(KEY_BOT_CONTROL, true);
    }

    public static void saveBotControlMcp(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_BOT_CONTROL, enabled);
        }
    }

    // The connector is APP-WIDE (one listener) — its keys carry the GLOBAL prefix so a session-scoping
    // store never freezes per-chat copies (the panel and the server must always see the SAME values).
    static final String KEY_CONNECTOR = WorkspaceStateStore.GLOBAL_KEY_PREFIX
            + "research.runtime.chatgptConnector";
    static final String KEY_CONNECTOR_PORT = KEY_CONNECTOR + ".port";
    static final String KEY_CONNECTOR_ORIGIN = KEY_CONNECTOR + ".publicOrigin";
    static final String KEY_CONNECTOR_CLIENT_ID = KEY_CONNECTOR + ".clientId";
    static final String KEY_CONNECTOR_CLIENT_SECRET = KEY_CONNECTOR + ".clientSecret";
    /** Pre-global legacy keys (may exist frozen in session scopes) — read-only migration fallback. */
    private static final String LEGACY_CONNECTOR = "research.runtime.chatgptConnector";

    /**
     * ChatGPT-connector toggle (DEFAULT OFF — this listener is reachable from other machines; enabling
     * a public face must be an explicit decision). When on, AskAI serves the OAuth endpoints + the MCP
     * endpoint /askai on the configured port; TLS terminates at the external Apache reverse proxy.
     */
    public static boolean loadChatGptConnector(WorkspaceStateStore store) {
        return store != null && store.getBoolean(KEY_CONNECTOR, false);
    }

    public static void saveChatGptConnector(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_CONNECTOR, enabled);
        }
    }

    public static ChatGptConnectorSettings loadChatGptConnectorSettings(WorkspaceStateStore store) {
        if (store == null) {
            return new ChatGptConnectorSettings(false, 8082, "", "", "");
        }
        int port;
        try {
            port = Integer.parseInt(store.get(KEY_CONNECTOR_PORT,
                    store.get(LEGACY_CONNECTOR + ".port", "8082")).trim());
        } catch (NumberFormatException invalid) {
            port = 8082;
        }
        return new ChatGptConnectorSettings(
                store.getBoolean(KEY_CONNECTOR,
                        store.getBoolean(LEGACY_CONNECTOR, false)),
                port,
                store.get(KEY_CONNECTOR_ORIGIN,
                        store.get(LEGACY_CONNECTOR + ".publicOrigin", "")),
                store.get(KEY_CONNECTOR_CLIENT_ID,
                        store.get(LEGACY_CONNECTOR + ".clientId", "")),
                store.get(KEY_CONNECTOR_CLIENT_SECRET,
                        store.get(LEGACY_CONNECTOR + ".clientSecret", "")));
    }

    public static void saveChatGptConnectorSettings(WorkspaceStateStore store,
                                                    ChatGptConnectorSettings settings) {
        if (store == null || settings == null) {
            return;
        }
        store.putBoolean(KEY_CONNECTOR, settings.isEnabled());
        store.put(KEY_CONNECTOR_PORT, String.valueOf(settings.getPort()));
        store.put(KEY_CONNECTOR_ORIGIN, settings.getPublicOrigin());
        store.put(KEY_CONNECTOR_CLIENT_ID, settings.getClientId());
        store.put(KEY_CONNECTOR_CLIENT_SECRET, settings.getClientSecret());
    }

    /** The typed ChatGPT-connector configuration (persisted; applies to NEW sessions). */
    public static final class ChatGptConnectorSettings {
        private final boolean enabled;
        private final int port;
        private final String publicOrigin;
        private final String clientId;
        private final String clientSecret;

        public ChatGptConnectorSettings(boolean enabled, int port, String publicOrigin,
                                        String clientId, String clientSecret) {
            this.enabled = enabled;
            this.port = port;
            this.publicOrigin = nullToEmpty(publicOrigin);
            this.clientId = nullToEmpty(clientId);
            this.clientSecret = nullToEmpty(clientSecret);
        }

        public boolean isEnabled() { return enabled; }
        public int getPort() { return port; }
        public String getPublicOrigin() { return publicOrigin; }
        public String getClientId() { return clientId; }
        public String getClientSecret() { return clientSecret; }
    }

    static final String KEY_SEARCH_STRATEGY = "research.search.strategy";
    static final String KEY_SEARCH_PROVIDER = "research.search.provider";
    static final String KEY_SEARCH_ENGINE = "research.search.engine";
    static final String KEY_SEARCH_LANGUAGE = "research.search.language";
    static final String KEY_SEARCH_COUNTRY = "research.search.country";

    /**
     * The persisted initial-search strategy selection (default: legacy browser SERP — nothing changes for
     * existing installations). Read directly like the language; applies to NEW sessions.
     */
    public static SearchStrategySelection loadSearchStrategy(WorkspaceStateStore store) {
        if (store == null) {
            return SearchStrategySelection.legacyBrowser();
        }
        return new SearchStrategySelection(
                store.get(KEY_SEARCH_STRATEGY, SearchStrategySelection.STRATEGY_LEGACY_BROWSER),
                store.get(KEY_SEARCH_PROVIDER, ""),
                store.get(KEY_SEARCH_ENGINE, ""),
                store.get(KEY_SEARCH_LANGUAGE, ""),
                store.get(KEY_SEARCH_COUNTRY, ""));
    }

    public static void saveSearchStrategy(WorkspaceStateStore store, SearchStrategySelection selection) {
        if (store == null || selection == null) {
            return;
        }
        store.put(KEY_SEARCH_STRATEGY, selection.getStrategy());
        store.put(KEY_SEARCH_PROVIDER, selection.getProvider());
        store.put(KEY_SEARCH_ENGINE, selection.getEngine());
        store.put(KEY_SEARCH_LANGUAGE, selection.getLanguage());
        store.put(KEY_SEARCH_COUNTRY, selection.getCountry());
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
    /**
     * A LEGACY reranker selection carried transparently ("" = none). The reranker is now chosen centrally
     * in AskAI (Configuration → AI models); this value is no longer edited here — it only carries a
     * previously persisted selection through to the host, which migrates it into the central store on first
     * use. See {@code LocalRerankerConfigurationSnapshotProvider}.
     */
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
     * The runtime rules ({@link ResearchRuntimeConfig#validate()}) PLUS the product-level requirement
     * that the thin sidecar jar needs its sibling {@code lib/} directory. Empty = usable.
     * <p>
     * A search provider URL is NO LONGER required: the product ships with search engines and the user
     * orders them in the search settings. The URL here is only the documented dev/test override, and
     * demanding one meant every installation had to name an engine the product already knew.
     */
    public List<String> validateProductive() {
        List<String> problems = new java.util.ArrayList<String>(toRuntimeConfig().validate());
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
