package com.aresstack.askai.research.runtime.search.provider;

/**
 * Provider-specific contract for the Brave Search API ({@link SearchProviderId#BRAVE_SEARCH_API}). A
 * first-stage target: the interface exists now; the concrete implementation is bound in its own follow-up
 * slice. Until then the registry does not resolve this id.
 */
public interface BraveSearchProvider extends SearchProvider {
}
