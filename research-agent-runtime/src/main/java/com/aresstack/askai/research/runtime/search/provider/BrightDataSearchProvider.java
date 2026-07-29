package com.aresstack.askai.research.runtime.search.provider;

/**
 * Provider-specific contract for Bright Data's SERP API ({@link SearchProviderId#BRIGHT_DATA}). A
 * first-stage target: the interface exists now; the concrete implementation (with engine selection over
 * Google/Bing/Yandex/Baidu) is bound in its own follow-up slice. Until then the registry does not resolve
 * this id.
 */
public interface BrightDataSearchProvider extends SearchProvider {
}
