package com.aresstack.askai.research.search.brightdata;

import com.aresstack.askai.research.search.config.ConfigurationValidation;

public final class BrightDataSearchConfigurationValidator {

    public void validate(
            BrightDataSearchConfiguration configuration) {

        if (configuration == null) {
            throw new IllegalArgumentException(
                    "configuration must not be null");
        }

        ConfigurationValidation.requireRange(
                configuration.getFormatVersion(),
                1,
                Integer.MAX_VALUE,
                "formatVersion");
        ConfigurationValidation.requireText(
                configuration.getSynchronousEndpoint(),
                "synchronousEndpoint");
        ConfigurationValidation.requireText(
                configuration.getAsynchronousRequestEndpoint(),
                "asynchronousRequestEndpoint");
        ConfigurationValidation.requireText(
                configuration.getAsynchronousResultEndpoint(),
                "asynchronousResultEndpoint");
        ConfigurationValidation.requireText(
                configuration.getZone(),
                "zone");
        ConfigurationValidation.requireNonNull(
                configuration.getExecutionMode(),
                "executionMode");
        ConfigurationValidation.requireNonNull(
                configuration.getSearchEngine(),
                "searchEngine");
        ConfigurationValidation.requireNonNull(
                configuration.getResponseFormat(),
                "responseFormat");
        ConfigurationValidation.requireNonNull(
                configuration.getRequestMethod(),
                "requestMethod");
        ConfigurationValidation.requireNonNull(
                configuration.getDataFormat(),
                "dataFormat");
        ConfigurationValidation.requireRange(
                configuration.getResultsPerPage(),
                1,
                100,
                "resultsPerPage");
        ConfigurationValidation.requireRange(
                configuration.getStartOffset(),
                0,
                Integer.MAX_VALUE,
                "startOffset");
        ConfigurationValidation.requireRange(
                configuration.getPollingIntervalMillis(),
                100,
                Integer.MAX_VALUE,
                "pollingIntervalMillis");
        ConfigurationValidation.requireRange(
                configuration.getMaximumPollAttempts(),
                1,
                10_000,
                "maximumPollAttempts");
        ConfigurationValidation.validateTransport(
                configuration.getTransport());

        if (configuration.getExecutionMode()
                == BrightDataExecutionMode.ASYNCHRONOUS
                && configuration.getSearchEngine()
                != BrightDataSearchEngine.GOOGLE) {

            throw new IllegalArgumentException(
                    "Bright Data asynchronous request mapping currently supports GOOGLE only");
        }

        if (configuration.isEnabled()
                && (configuration.getApiKey() == null
                || !configuration.getApiKey().isPresent())) {

            throw new IllegalArgumentException(
                    "apiKey must be configured when Bright Data is enabled");
        }
    }
}
