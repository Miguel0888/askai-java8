package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.AiLayoutResolverSettings;
import com.aresstack.askai.browser.search.AiRetryPolicy;
import com.aresstack.askai.browser.search.SearchDiagnosticsSettings;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.inference.StructuredInferencePort;
import com.aresstack.askai.browser.search.inference.StructuredInferenceRequest;
import com.aresstack.askai.browser.search.inference.StructuredInferenceResult;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisAttempt;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionDecision;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionRequest;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolver;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverOutcome;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The single production {@link SearchPageLayoutResolver}: it builds the prompt from the bounded
 * artifact, calls the neutral {@link StructuredInferencePort}, parses the answer and — bounded by the
 * {@link AiRetryPolicy} and the cancellation signal — repairs a rejected answer by handing the model
 * its previous response and the concrete problems. It knows NO concrete model library; the adapter is
 * injected. Absent an adapter (or when the port answers UNAVAILABLE) it returns a typed
 * {@code AI_UNAVAILABLE} — never a silent fake success. A4c deepens the in-loop gate from parse-only
 * to full structural validation.
 */
public final class AiSearchPageLayoutResolver implements SearchPageLayoutResolver {

    /** Absolute ceiling on attempts regardless of settings — no unbounded retry path exists. */
    static final int HARD_ATTEMPT_CEILING = 6;

    private final StructuredInferencePort port;
    private final SearchPageLayoutPromptFactory promptFactory = new SearchPageLayoutPromptFactory();
    private final SearchPageLayoutDecisionParser parser = new SearchPageLayoutDecisionParser();

    public AiSearchPageLayoutResolver(StructuredInferencePort port) {
        this.port = port;
    }

    public SearchPageLayoutResolverResult resolve(SearchPageLayoutResolutionRequest request) {
        SearchPageAnalysisArtifact artifact = request.artifact;
        AiLayoutResolverSettings ai = request.aiSettings;
        String snapshotId = artifact.snapshotId;

        if (ai == null || !ai.enabled) {
            return result(SearchPageLayoutResolverOutcome.AI_DISABLED, snapshotId, null,
                    Collections.<SearchPageAnalysisAttempt>emptyList(),
                    "AI layout resolver is disabled");
        }
        if (request.cancellationSignal.isCancelled()) {
            return result(SearchPageLayoutResolverOutcome.CANCELLED, snapshotId, null,
                    Collections.<SearchPageAnalysisAttempt>emptyList(),
                    "cancelled before first attempt");
        }

        AiRetryPolicy policy = ai.retryPolicy;
        int maxAttempts = clamp(policy.maximumAttempts, 1, HARD_ATTEMPT_CEILING);
        List<SearchPageAnalysisAttempt> attempts = new ArrayList<SearchPageAnalysisAttempt>();
        String previousResponse = "";
        List<String> previousViolations = Collections.emptyList();

        for (int attemptNumber = 1; attemptNumber <= maxAttempts; attemptNumber++) {
            boolean lastAttempt = attemptNumber == maxAttempts;
            if (request.cancellationSignal.isCancelled()) {
                return result(SearchPageLayoutResolverOutcome.CANCELLED, snapshotId, null, attempts,
                        "cancelled before attempt " + attemptNumber);
            }
            backoffBeforeRetry(policy, attemptNumber, request.cancellationSignal);
            if (request.cancellationSignal.isCancelled()) {
                return result(SearchPageLayoutResolverOutcome.CANCELLED, snapshotId, null, attempts,
                        "cancelled during backoff before attempt " + attemptNumber);
            }

            String userPrompt = promptFactory.userPrompt(artifact, ai);
            if (attemptNumber > 1) {
                userPrompt += promptFactory.repairSuffix(artifact, previousResponse,
                        previousViolations, policy.includePreviousResponse,
                        policy.includeValidationErrors);
            }
            StructuredInferenceResult inference = port.execute(new StructuredInferenceRequest(
                    ai.modelProfileId, promptFactory.systemPrompt(ai), userPrompt,
                    ai.maximumOutputTokens, ai.temperature, ai.reasoningEffort, attemptNumber,
                    request.cancellationSignal));

            String rawStored = storedRaw(request.diagnosticsSettings, inference.rawText);

            if (inference.status == StructuredInferenceStatus.CANCELLED) {
                attempts.add(attempt(attemptNumber, inference.status, false, false,
                        one("inference cancelled"), rawStored));
                return result(SearchPageLayoutResolverOutcome.CANCELLED, snapshotId, null, attempts,
                        "inference cancelled");
            }
            if (inference.status != StructuredInferenceStatus.SUCCESS) {
                attempts.add(attempt(attemptNumber, inference.status, false, false,
                        one("inference " + inference.status + " " + inference.detail), rawStored));
                if (retryOnStatus(policy, inference.status) && !lastAttempt) {
                    previousResponse = inference.rawText;
                    previousViolations = one("inference " + inference.status);
                    continue;
                }
                return result(SearchPageLayoutResolverOutcome.AI_UNAVAILABLE, snapshotId, null,
                        attempts, "inference " + inference.status);
            }

            if (inference.rawText.trim().isEmpty()) {
                attempts.add(attempt(attemptNumber, inference.status, false, false,
                        one("empty response"), rawStored));
                if (policy.retryOnEmptyResponse && !lastAttempt) {
                    previousResponse = "";
                    previousViolations = one("empty response");
                    continue;
                }
                return result(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, snapshotId, null,
                        attempts, "empty response");
            }

            SearchPageLayoutResolutionDecision decision;
            try {
                decision = parser.parse(inference.rawText);
            } catch (MiniJson.JsonParseException parseFailure) {
                attempts.add(attempt(attemptNumber, inference.status, false, false,
                        one(parseFailure.getMessage()), rawStored));
                if (policy.retryOnParsingFailure && !lastAttempt) {
                    previousResponse = inference.rawText;
                    previousViolations = one(parseFailure.getMessage());
                    continue;
                }
                return result(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, snapshotId, null,
                        attempts, "parse failure: " + parseFailure.getMessage());
            }

            // A4b accepts a parsed decision; A4c inserts structural validation here.
            attempts.add(attempt(attemptNumber, inference.status, true, true,
                    Collections.<String>emptyList(), rawStored));
            return result(SearchPageLayoutResolverOutcome.RESOLVED, snapshotId, decision, attempts,
                    "resolved on attempt " + attemptNumber);
        }

        return result(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, snapshotId, null, attempts,
                "retry budget exhausted");
    }

    private static boolean retryOnStatus(AiRetryPolicy policy, StructuredInferenceStatus status) {
        switch (status) {
            case TIMEOUT:
                return policy.retryOnModelTimeout;
            case INVALID_RESPONSE:
                return policy.retryOnParsingFailure;
            case UNAVAILABLE:
            case PROVIDER_FAILURE:
            default:
                return false;
        }
    }

    private void backoffBeforeRetry(AiRetryPolicy policy, int attemptNumber,
                                    CancellationSignal cancellation) {
        if (attemptNumber <= 1) {
            return;
        }
        long backoff = (long) (policy.initialBackoffMillis
                * Math.pow(policy.backoffMultiplier, attemptNumber - 2));
        backoff = Math.min(backoff, policy.maximumBackoffMillis);
        if (backoff <= 0) {
            return;
        }
        long deadline = backoff;
        long slept = 0;
        while (slept < deadline && !cancellation.isCancelled()) {
            try {
                Thread.sleep(Math.min(50, deadline - slept));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            slept += 50;
        }
    }

    private String storedRaw(SearchDiagnosticsSettings diagnostics, String rawText) {
        if (diagnostics == null || !diagnostics.storeRawModelResponses) {
            return "";
        }
        int cap = Math.max(0, diagnostics.maximumTextExcerptCharacters);
        return rawText.length() <= cap ? rawText : rawText.substring(0, cap);
    }

    private static SearchPageAnalysisAttempt attempt(int number, StructuredInferenceStatus status,
                                                     boolean parsed, boolean accepted,
                                                     List<String> violations, String rawResponse) {
        return new SearchPageAnalysisAttempt(number, status, parsed, accepted, violations,
                rawResponse);
    }

    private static SearchPageLayoutResolverResult result(SearchPageLayoutResolverOutcome outcome,
                                                         String snapshotId,
                                                         SearchPageLayoutResolutionDecision decision,
                                                         List<SearchPageAnalysisAttempt> attempts,
                                                         String diagnostic) {
        return new SearchPageLayoutResolverResult(outcome, snapshotId, decision, attempts, diagnostic);
    }

    private static List<String> one(String value) {
        List<String> list = new ArrayList<String>(1);
        list.add(value);
        return list;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
