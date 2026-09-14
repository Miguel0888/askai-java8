package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.List;

/**
 * SC1 Search-Control: the runtime-side port to the HOST's semantic acquisition filter — the
 * second, ORTHOGONAL decision in the funnel. Query relevance ("does this answer my query?")
 * stays with the reranker/relevance model; scope control answers "may this enter the
 * negotiated research space at all?". NEVER a model tool: implementations ride the internal
 * research-service endpoint, structurally absent from the agent tool catalog.
 *
 * <p>Currency rule (deliberately different from scope_probe): a RUNNING search never goes
 * stale — {@link #begin()} pins one immutable snapshot, every {@link #evaluate} of the run
 * judges against it, the NEXT run sees the new fence. Page 1 and page 8 are judged against the
 * same research space.</p>
 */
public interface SearchScopeControlPort {

    /** The pinned run snapshot. {@code active=false} = no OUT anchors → true no-op fast path. */
    final class Session {
        public final boolean active;
        public final String handle;
        public final String summary;

        public Session(boolean active, String handle, String summary) {
            this.active = active;
            this.handle = handle;
            this.summary = summary == null ? "" : summary;
        }
    }

    /** One candidate: id (usually the URL) + its best available semantic text. */
    final class Item {
        public final String id;
        public final String text;

        public Item(String id, String text) {
            this.id = id == null ? "" : id;
            this.text = text == null ? "" : text;
        }
    }

    /** One verdict. OUT = clear canonical out (hard reject); UNCLASSIFIED = no usable text. */
    final class Decision {
        public final String id;
        public final boolean out;
        public final boolean unclassified;
        public final String nearestOutLabel;

        public Decision(String id, boolean out, boolean unclassified, String nearestOutLabel) {
            this.id = id;
            this.out = out;
            this.unclassified = unclassified;
            this.nearestOutLabel = nearestOutLabel == null ? "" : nearestOutLabel;
        }
    }

    /** Pin the run's immutable scope snapshot. Throws when scope control is needed but broken. */
    Session begin() throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;

    /** Judge ONE batch against the pinned snapshot ({@code lane}: serp/links/page — log only). */
    List<Decision> evaluate(String handle, String lane, List<Item> items)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;

    /** Release the snapshot. Best effort — never throws. */
    void end(String handle);
}
