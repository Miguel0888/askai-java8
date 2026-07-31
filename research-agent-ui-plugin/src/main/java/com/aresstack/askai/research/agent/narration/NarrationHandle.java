package com.aresstack.askai.research.agent.narration;

/**
 * Handle on one in-flight narration. {@link #cancel()} is idempotent and best-effort: it frees the
 * underlying generator (a local model is a serial bottleneck resource) — correctness against late
 * callbacks is guaranteed separately by the coordinator's generation guard, never by cancel alone.
 */
public interface NarrationHandle {

    NarrationHandle NONE = new NarrationHandle() {
        public void cancel() {
        }
    };

    void cancel();
}
