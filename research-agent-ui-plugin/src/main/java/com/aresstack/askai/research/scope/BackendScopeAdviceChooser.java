package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser;
import com.aresstack.askai.research.search.ResearchServiceCommandWire;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The host-side {@link ScopeAdviceChooser}: a thin WIRE CLIENT over the existing service-command
 * channel — {@code #RSC1# choose_advice} out via {@code submitServiceCommand}, the correlated
 * {@code #RSX1# advice} event back via {@link #deliver} (on the backend callback thread; the
 * backend deliberately ignores control-prompt terminals, so the event is the only answer). Same
 * MVP transport discipline as the probe generator: ONE choice in flight, an EXPLICIT timeout, and
 * every transport misfortune a typed {@link ScopeAdviceChooser.ChoiceResult} — a broken chooser
 * is never a silent NONE.
 */
public final class BackendScopeAdviceChooser implements ScopeAdviceChooser {

    /** All transport knobs explicit — wired from settings by the host, never constants. */
    public static final class WireSettings {
        public final double temperature;
        public final int maxOutputTokens;
        public final int timeoutSeconds;

        public WireSettings(double temperature, int maxOutputTokens, int timeoutSeconds) {
            this.temperature = temperature;
            this.maxOutputTokens = Math.max(1, maxOutputTokens);
            this.timeoutSeconds = Math.max(1, timeoutSeconds);
        }
    }

    /** Queue sentinel for an aborted wait (a BlockingQueue cannot carry null). */
    private static final String ABORTED_PREFIX = " aborted:";

    private final ResearchSessionBackend backend;
    private final ResearchSessionHandle handle;
    private final WireSettings settings;
    private final Map<String, BlockingQueue<String>> pending =
            new ConcurrentHashMap<String, BlockingQueue<String>>();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    public BackendScopeAdviceChooser(ResearchSessionBackend backend, ResearchSessionHandle handle,
                                     WireSettings settings) {
        if (backend == null || handle == null || settings == null) {
            throw new IllegalArgumentException("backend, handle and settings are required");
        }
        this.backend = backend;
        this.handle = handle;
        this.settings = settings;
    }

    @Override
    public ChoiceResult choose(ChoiceRequest request) {
        if (!inFlight.compareAndSet(false, true)) {
            return ChoiceResult.failure(ChoiceResult.Status.PROVIDER_FAILURE,
                    "an advice choice is already in flight");
        }
        String requestId = java.util.UUID.randomUUID().toString();
        BlockingQueue<String> answer = new ArrayBlockingQueue<String>(1);
        pending.put(requestId, answer);
        try {
            backend.submitServiceCommand(handle, ResearchServiceCommandWire.chooseAdvice(requestId,
                    ScopeAdviceWireCodec.encodeRequest(request, settings.temperature,
                            settings.maxOutputTokens)));
            System.err.println("[advice] host sent requestId=" + requestId
                    + " candidates=" + request.getCandidates().size()
                    + " timeoutS=" + settings.timeoutSeconds);
            String payload = answer.poll(settings.timeoutSeconds, TimeUnit.SECONDS);
            if (payload == null) {
                return ChoiceResult.failure(ChoiceResult.Status.TIMEOUT,
                        "no advice answer within " + settings.timeoutSeconds + "s");
            }
            if (payload.startsWith(ABORTED_PREFIX)) {
                return ChoiceResult.failure(ChoiceResult.Status.PROVIDER_FAILURE,
                        payload.substring(ABORTED_PREFIX.length()));
            }
            return ScopeAdviceWireCodec.decodeResult(payload);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ChoiceResult.failure(ChoiceResult.Status.PROVIDER_FAILURE,
                    "interrupted while waiting for the advice choice");
        } finally {
            pending.remove(requestId);
            inFlight.set(false);
        }
    }

    /** Route a correlated {@code #RSX1# advice} payload to its waiting request. */
    public boolean deliver(String requestId, String payloadJson) {
        BlockingQueue<String> answer = requestId == null ? null : pending.get(requestId);
        if (answer == null) {
            return false;
        }
        return answer.offer(payloadJson == null ? "" : payloadJson);
    }

    /** Session teardown / user cancel: waiting requests fail typed instead of timing out. */
    public void abortAll(String reason) {
        String sentinel = ABORTED_PREFIX + (reason == null ? "session closed" : reason);
        for (BlockingQueue<String> answer : pending.values()) {
            answer.offer(sentinel);
        }
    }
}
