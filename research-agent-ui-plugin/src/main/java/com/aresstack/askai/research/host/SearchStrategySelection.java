package com.aresstack.askai.research.host;

/**
 * The persisted initial-search strategy SELECTION (no credentials, ever): which strategy the research agent
 * uses for the initial search, and — for {@code API_PROVIDER} — which provider/engine/locale. Values are the
 * agent-side enum names verbatim ({@code StrategySelection}, {@code SearchProviderId}, {@code SearchEngine});
 * this plugin deliberately does not depend on the runtime module, the contract is the documented snapshot
 * JSON of {@code SearchStrategyConfigurationLoader}:
 * <pre>
 * { "strategy": "API_PROVIDER", "provider": "DATA_FOR_SEO", "engine": "GOOGLE",
 *   "language": "de", "country": "de" }
 * </pre>
 * Provider secrets live exclusively in {@code ${user.home}/agents/research/providers/} and never appear in
 * this object or its snapshot.
 */
public final class SearchStrategySelection {

    public static final String STRATEGY_LEGACY_BROWSER = "LEGACY_BROWSER";
    public static final String STRATEGY_API_PROVIDER = "API_PROVIDER";
    public static final String ENGINE_PROVIDER_DEFAULT = "PROVIDER_DEFAULT";

    private final String strategy;
    private final String provider;
    private final String engine;
    private final String language;
    private final String country;

    public SearchStrategySelection(String strategy, String provider, String engine,
                                   String language, String country) {
        this.strategy = STRATEGY_API_PROVIDER.equals(safe(strategy))
                ? STRATEGY_API_PROVIDER : STRATEGY_LEGACY_BROWSER;
        this.provider = safe(provider);
        this.engine = safe(engine);
        this.language = safe(language);
        this.country = safe(country);
    }

    public static SearchStrategySelection legacyBrowser() {
        return new SearchStrategySelection(STRATEGY_LEGACY_BROWSER, "", "", "", "");
    }

    /** Identifier/locale values only: everything outside [A-Za-z0-9_-] is dropped (never escaped JSON). */
    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public boolean isApiProvider() {
        return STRATEGY_API_PROVIDER.equals(strategy) && !provider.isEmpty();
    }

    public String getStrategy() {
        return strategy;
    }

    public String getProvider() {
        return provider;
    }

    public String getEngine() {
        return engine;
    }

    public String getLanguage() {
        return language;
    }

    public String getCountry() {
        return country;
    }

    /**
     * The snapshot document the agent's {@code SearchStrategyConfigurationLoader} parses. Only meaningful
     * for {@link #isApiProvider()} — a legacy selection publishes NO snapshot at all (absent env var is
     * the documented legacy path), so existing sessions keep today's behavior bit for bit.
     */
    public String toSnapshotJson() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{ \"strategy\": \"").append(STRATEGY_API_PROVIDER).append('"');
        sb.append(", \"provider\": \"").append(provider).append('"');
        sb.append(", \"engine\": \"")
                .append(engine.isEmpty() ? ENGINE_PROVIDER_DEFAULT : engine).append('"');
        if (!language.isEmpty()) {
            sb.append(", \"language\": \"").append(language).append('"');
        }
        if (!country.isEmpty()) {
            sb.append(", \"country\": \"").append(country).append('"');
        }
        sb.append(" }");
        return sb.toString();
    }
}
