package com.aresstack.askai.research.runtime.search.provider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A provider's own configuration port value: an opaque, string-keyed bag of settings (endpoint, credentials,
 * locale, device, depth …). It deliberately carries no Swing types and no global static UI settings — the
 * host resolves credentials from its global secret/settings mechanism and hands the provider only this bag.
 * Missing required keys are reported as a {@link SearchProviderConfigurationException} by the provider.
 */
public final class SearchProviderConfiguration {

    private final SearchProviderId providerId;
    private final Map<String, String> settings;

    public SearchProviderConfiguration(SearchProviderId providerId, Map<String, String> settings) {
        if (providerId == null) {
            throw new IllegalArgumentException("Provider id must not be null");
        }
        this.providerId = providerId;
        this.settings = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(settings == null
                        ? Collections.<String, String>emptyMap() : settings));
    }

    public SearchProviderId getProviderId() {
        return providerId;
    }

    /** @return the trimmed value for {@code key}, or {@code null} when absent/blank. */
    public String get(String key) {
        String value = settings.get(key);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public String getOrDefault(String key, String fallback) {
        String value = get(key);
        return value == null ? fallback : value;
    }

    /** @return the value for {@code key} or a {@link SearchProviderConfigurationException} when missing. */
    public String require(String key) {
        String value = get(key);
        if (value == null) {
            throw new SearchProviderConfigurationException(providerId,
                    "Missing required configuration '" + key + "' for provider " + providerId);
        }
        return value;
    }

    public int getInt(String key, int fallback) {
        String value = get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new SearchProviderConfigurationException(providerId,
                    "Configuration '" + key + "' must be an integer for provider " + providerId
                            + " (was '" + value + "')");
        }
    }
}
