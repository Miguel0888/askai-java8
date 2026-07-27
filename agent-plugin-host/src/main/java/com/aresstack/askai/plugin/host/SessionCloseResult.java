package com.aresstack.askai.plugin.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of closing the outgoing generation's sessions during a transactional swap. The old plugin
 * generation may only be retired (classloaders unloaded) when this is {@link #isSuccessful() successful}: if any
 * session failed to close, a live object would otherwise be left pointing at an unloaded classloader, so the
 * swap is aborted, the candidate is discarded and the previous generation is kept.
 */
public final class SessionCloseResult {

    private final List<String> failures;

    private SessionCloseResult(List<String> failures) {
        this.failures = Collections.unmodifiableList(new ArrayList<String>(failures));
    }

    public static SessionCloseResult ok() {
        return new SessionCloseResult(Collections.<String>emptyList());
    }

    public static SessionCloseResult of(List<String> failures) {
        return new SessionCloseResult(failures == null ? Collections.<String>emptyList() : failures);
    }

    public boolean isSuccessful() {
        return failures.isEmpty();
    }

    public List<String> getFailures() {
        return failures;
    }
}
