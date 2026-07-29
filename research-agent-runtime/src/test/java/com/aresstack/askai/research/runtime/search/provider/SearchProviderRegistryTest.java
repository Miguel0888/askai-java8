package com.aresstack.askai.research.runtime.search.provider;

import com.aresstack.askai.research.runtime.search.provider.dataforseo.DataForSeoSearchProvider;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The registry binds ONLY implemented providers. DATA_FOR_SEO resolves to the productive adapter; every
 * other catalogued id has no provider object at all — the registry throws a typed
 * {@link SearchProviderNotImplementedException} instead of returning a stub, so the productive/not-implemented
 * decision lives in exactly one place and an unimplemented provider fails visibly.
 */
public class SearchProviderRegistryTest {

    private SearchProviderRegistry registry() {
        return new DefaultSearchProviderRegistry(new SearchProviderConfigurationSource() {
            public SearchProviderConfiguration load(SearchProviderId providerId) {
                Map<String, String> settings = new LinkedHashMap<String, String>();
                settings.put("login", "user");
                settings.put("password", "secret");
                settings.put("location_name", "Germany");
                return new SearchProviderConfiguration(providerId, settings);
            }
        });
    }

    @Test
    public void dataForSeoResolvesToTheProductiveProvider() {
        SearchProvider provider = registry().requireImplementedProvider(SearchProviderId.DATA_FOR_SEO);
        assertTrue(provider instanceof DataForSeoSearchProvider);
        assertEquals(SearchProviderAvailability.AVAILABLE, provider.getAvailability());
    }

    @Test
    public void everyOtherIdThrowsNotImplementedWithoutCreatingAnObject() {
        SearchProviderRegistry registry = registry();
        for (SearchProviderId id : SearchProviderId.values()) {
            if (id == SearchProviderId.DATA_FOR_SEO) {
                continue;
            }
            try {
                registry.requireImplementedProvider(id);
                fail("expected SearchProviderNotImplementedException for " + id);
            } catch (SearchProviderNotImplementedException ex) {
                assertEquals(id, ex.getProviderId());
                assertTrue("a not-implemented provider is not retryable", !ex.isRetryable());
            }
        }
    }

    @Test
    public void catalogueListsEveryIdWithDataForSeoTheOnlyImplementedOne() {
        int implemented = 0;
        for (SearchProviderDescriptor descriptor : registry().getDescriptors()) {
            if (descriptor.isImplemented()) {
                implemented++;
                assertEquals(SearchProviderId.DATA_FOR_SEO, descriptor.getProviderId());
            } else {
                assertEquals(SearchProviderImplementationStatus.NOT_IMPLEMENTED,
                        descriptor.getImplementationStatus());
            }
        }
        assertEquals("only DataForSEO is productive in this slice", 1, implemented);
        assertEquals(SearchProviderId.values().length, registry().getDescriptors().size());
    }
}
