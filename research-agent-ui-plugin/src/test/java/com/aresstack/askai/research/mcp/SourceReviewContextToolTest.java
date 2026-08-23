package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.sources.SourceStatus;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A review that is asked what we LEARNED must be given the material, not a table of contents. The old
 * context was {@code source_list} — ids, titles and statuses — from which no honest summary can be
 * written, only an invented one.
 */
public class SourceReviewContextToolTest {

    private final InMemoryResearchSourceRepository sources = InMemoryResearchSourceRepository.empty();

    private final ResearchControlContext ctx = new ResearchControlContext() {
        public String currentPhaseId() {
            return ResearchStateIds.RESEARCH;
        }

        public String currentStateId() {
            return ResearchStateIds.RUNNING;
        }

        public String statusLine() {
            return currentPhaseId() + "/" + currentStateId();
        }

        public AgentArtifactStore artifactStore() {
            return null;
        }

        public ResearchSourceRepository sourceRepository() {
            return sources;
        }

        public String acceptCapture(String captureId) {
            return null;
        }
    };

    private void add(String id, String title, String text, long capturedAt, SourceStatus status) {
        sources.put(ResearchSourceRecord.builder(id)
                .title(title)
                .url("https://example.test/" + id)
                .searchQuery("thailand prostitution laws")
                .excerpt("excerpt of " + id)
                .fullText(text)
                .capturedAt(capturedAt)
                .status(status)
                .build());
    }

    private String invoke(String capturedThrough) {
        return invoke(capturedThrough, null);
    }

    private String invoke(String capturedThrough, String capturedSince) {
        for (McpToolContribution tool : ResearchToolPolicy.toolsFor(ResearchStateIds.RESEARCH,
                ResearchStateIds.RUNNING, ctx)) {
            if ("source_review_context".equals(tool.getName())) {
                Map<String, Object> args = new HashMap<String, Object>();
                if (capturedThrough != null) {
                    args.put("captured_through", capturedThrough);
                }
                if (capturedSince != null) {
                    args.put("captured_since", capturedSince);
                }
                McpToolResult result = tool.getHandler().invoke(new McpToolCall("source_review_context", args));
                return String.valueOf(result.getText());
            }
        }
        throw new IllegalStateException("source_review_context is not offered");
    }

    /**
     * "The NEW sources" is a WINDOW, not everything up to now: without the lower edge every review
     * re-read the whole cumulative corpus and produced the same summary and clusters, search after search.
     */
    @Test
    public void theSinceEdgeCutsOffEarlierSearchesSources() {
        add("s-old", "Old search material", "text from an earlier search", 1_000L, SourceStatus.ACCEPTED);
        add("s-new", "This search's find", "text this search just added", 5_000L, SourceStatus.ACCEPTED);

        String window = invoke("9000", "4000");
        assertTrue("the new source is in the window", window.contains("This search's find"));
        assertFalse("the earlier corpus is NOT re-reviewed", window.contains("Old search material"));

        String all = invoke("9000", "0");
        assertTrue("0 keeps the old behaviour: everything up to the pin", all.contains("Old search material"));
    }

    @Test
    public void theReviewSeesTheActualTextNotJustTheTitle() {
        add("s1", "Legal framework", "The 1996 act criminalises procurement, not sex work itself.",
                1_000L, SourceStatus.ACCEPTED);

        String context = invoke("0");

        assertTrue(context.contains("Legal framework"));
        assertTrue("the material itself is what a review works from",
                context.contains("criminalises procurement"));
        assertTrue("and where it came from", context.contains("thailand prostitution laws"));
    }

    /** The pin decides the set: a source captured after it belongs to the NEXT review, not this one. */
    @Test
    public void aSourceCapturedAfterThePinIsNotPartOfThisReview() {
        add("s1", "Before", "text before the pin", 1_000L, SourceStatus.ACCEPTED);
        add("s2", "After", "text after the pin", 2_000L, SourceStatus.ACCEPTED);

        String context = invoke("1500");

        assertTrue(context.contains("Before"));
        assertFalse("the ledger will not mark this one reviewed, so the review must not read it",
                context.contains("After"));
    }

    /** A parked candidate has no text yet — offering it as material would only invite guessing. */
    @Test
    public void parkedAndExcludedSourcesAreNotMaterial() {
        add("s1", "Parked candidate", "", 1_000L, SourceStatus.PARKED);
        add("s2", "Excluded", "text", 1_100L, SourceStatus.EXCLUDED);

        assertEquals("No sources to review.", invoke("0"));
    }

    /** A source without readable text says so, rather than looking like a page that said nothing. */
    @Test
    public void aSourceWithoutTextSaysSo() {
        sources.put(ResearchSourceRecord.builder("s1").title("Unreadable")
                .url("https://example.test/s1").capturedAt(1_000L)
                .status(SourceStatus.ACCEPTED).build());

        assertTrue(invoke("0").contains("no readable text was captured"));
    }
}
