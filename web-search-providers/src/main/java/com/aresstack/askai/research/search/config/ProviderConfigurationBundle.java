package com.aresstack.askai.research.search.config;

import com.aresstack.askai.research.search.brave.BraveSearchConfiguration;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;

public final class ProviderConfigurationBundle {

    private final BraveSearchConfiguration brave;
    private final BrightDataSearchConfiguration brightData;
    private final DataForSeoSearchConfiguration dataForSeo;

    public ProviderConfigurationBundle(
            BraveSearchConfiguration brave,
            BrightDataSearchConfiguration brightData,
            DataForSeoSearchConfiguration dataForSeo) {

        this.brave = requireNonNull(brave, "brave");
        this.brightData = requireNonNull(
                brightData,
                "brightData");
        this.dataForSeo = requireNonNull(
                dataForSeo,
                "dataForSeo");
    }

    public BraveSearchConfiguration getBrave() {
        return brave;
    }

    public BrightDataSearchConfiguration getBrightData() {
        return brightData;
    }

    public DataForSeoSearchConfiguration getDataForSeo() {
        return dataForSeo;
    }

    private static <T> T requireNonNull(
            T value,
            String propertyName) {

        if (value == null) {
            throw new IllegalArgumentException(
                    propertyName + " must not be null");
        }
        return value;
    }
}
