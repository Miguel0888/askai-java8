package com.aresstack.askai.research.agent.narration;

import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.backend.ResearchScheduler;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the visible narration lifecycle: thought bubble now → exactly one final text later. The SAME
 * sequence for every path (LLM answer, timeout, failure, fake), so the user can never tell which produced
 * the message:
 *
 * <ol>
 *   <li>{@code startThinking(activityId, staticTitle)} in the same UI tick — the bubble IS the latency
 *       affordance; decision cards render independently and instantly (expert fast-track).</li>
 *   <li>The async narrator works off-thread; a scheduler timeout races it with the fallback text.</li>
 *   <li>Exactly one terminal wins (per-request CAS). On the UI thread the GENERATION captured at submit is
 *       checked: stale → the bubble closes silently and nothing is presented; fresh → {@code finishThinking}
 *       plus exactly one presented message. Already-shown text is never replaced.</li>
 * </ol>
 *
 * {@link #invalidate()} bumps the generation and cancels all in-flight narrations (freeing the local
 * model), e.g. when the user consumed a decision card or the state advanced. Without an async narrator
 * (toggle off) {@link #narrate} presents the fallback synchronously — no bubble, today's behavior.
 */
public final class NarrationCoordinator {

    /** Receives the one final text on the UI thread (typically {@code sayAsAgent}). */
    public interface Presenter {
        void present(String text);
    }

    private final AsyncNarrator asyncNarrator;
    private final AgentConversationSink sink;
    private final UiExecutor uiExecutor;
    private final ResearchScheduler scheduler;
    private final long timeoutMillis;
    private final AtomicInteger generation = new AtomicInteger();
    private final CopyOnWriteArrayList<Active> inFlight = new CopyOnWriteArrayList<Active>();

    public NarrationCoordinator(AsyncNarrator asyncNarrator, AgentConversationSink sink,
                                UiExecutor uiExecutor, ResearchScheduler scheduler, long timeoutMillis) {
        this.asyncNarrator = asyncNarrator;
        this.sink = sink;
        this.uiExecutor = uiExecutor;
        this.scheduler = scheduler;
        this.timeoutMillis = timeoutMillis;
    }

    /** Anything in flight no longer belongs to the current situation: cancel it, close bubbles silently. */
    public void invalidate() {
        generation.incrementAndGet();
        for (Active active : inFlight) {
            finish(active, null); // null text → close silently, present nothing
        }
    }

    public void narrate(final NarrationRequest request, final Presenter presenter) {
        if (asyncNarrator == null) {
            presenter.present(request.getFallbackText());
            return;
        }
        final Active active = new Active(request, presenter, generation.get());
        inFlight.add(active);
        onUi(new Runnable() {
            public void run() {
                if (sink != null && !active.done.get()) {
                    sink.startThinking(request.getActivityId(), request.getThinkingTitle());
                    active.bubbleShown = true;
                }
            }
        });
        active.timeout = scheduler.schedule(new Runnable() {
            public void run() {
                finish(active, request.getFallbackText());
            }
        }, timeoutMillis);
        NarrationHandle handle = asyncNarrator.narrate(request, new AsyncNarrator.Callback() {
            public void onNarration(String text) {
                boolean usable = text != null && !text.trim().isEmpty();
                finish(active, usable ? text : request.getFallbackText());
            }

            public void onFailure(String reason) {
                finish(active, request.getFallbackText());
            }
        });
        active.handle = handle == null ? NarrationHandle.NONE : handle;
        if (active.done.get()) {
            active.handle.cancel(); // terminal won the race before the handle existed
        }
    }

    /** Exactly-one-terminal: the first caller wins; {@code text == null} closes silently (stale/cancel). */
    private void finish(final Active active, final String text) {
        if (!active.done.compareAndSet(false, true)) {
            return;
        }
        inFlight.remove(active);
        if (active.timeout != null) {
            active.timeout.cancel();
        }
        if (active.handle != null) {
            active.handle.cancel();
        }
        onUi(new Runnable() {
            public void run() {
                boolean stale = text == null || generation.get() != active.generation;
                if (sink != null && active.bubbleShown) {
                    sink.finishThinking(active.request.getActivityId(), "");
                }
                if (!stale) {
                    active.presenter.present(text);
                }
            }
        });
    }

    private void onUi(Runnable task) {
        if (uiExecutor != null) {
            uiExecutor.execute(task);
        } else {
            task.run();
        }
    }

    private static final class Active {
        private final NarrationRequest request;
        private final Presenter presenter;
        private final int generation;
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile boolean bubbleShown;
        private volatile ResearchScheduler.Cancellable timeout;
        private volatile NarrationHandle handle;

        private Active(NarrationRequest request, Presenter presenter, int generation) {
            this.request = request;
            this.presenter = presenter;
            this.generation = generation;
        }
    }
}
