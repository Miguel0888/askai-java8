package com.aresstack.askai.browser.sidecar;

/**
 * Decides, from ONE non-blocking {@link ReadinessProbe} snapshot, whether a page has settled. Stateless: any
 * memory needed across probes lives in the caller-owned {@link ReadinessState}. Two implementations exist —
 * {@link GenericContentReadinessStrategy} (body text appears and stops growing) as the universal fallback, and
 * {@link SearchResultsReadinessStrategy} (result / no-result / challenge selectors) for engine result pages
 * where generic stability fires too early (the search header stabilises before the JS-injected results land).
 */
interface PageReadinessStrategy {

    /**
     * Inspect once, WITHOUT sleeping. Returns {@link ReadinessLabel#PENDING} to keep waiting or a settled
     * label to stop. {@code state} may be read and mutated to remember progress between calls.
     */
    ReadinessLabel inspect(ReadinessProbe probe, ReadinessState state, PageReadinessPolicy policy);
}
