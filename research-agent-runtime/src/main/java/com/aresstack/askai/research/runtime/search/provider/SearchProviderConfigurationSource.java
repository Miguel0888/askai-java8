package com.aresstack.askai.research.runtime.search.provider;

/**
 * The provider's only configuration port. Implementations resolve credentials and parameters from the host's
 * global secret/settings mechanism (never from the research project directory and never from Swing). The
 * registry uses it to build productive providers lazily.
 */
public interface SearchProviderConfigurationSource {

    SearchProviderConfiguration load(SearchProviderId providerId);
}
