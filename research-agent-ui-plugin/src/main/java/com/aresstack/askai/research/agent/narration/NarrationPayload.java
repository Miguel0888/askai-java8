package com.aresstack.askai.research.agent.narration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The FACTS a narration must carry — data-to-text, never "paraphrase this string": the narrator receives
 * structure and renders language; the validator checks the result against exactly this object. Everything
 * here is deterministic input assembled from the playbook and the live state.
 */
public final class NarrationPayload {

    private final String situation;
    private final List<String> mustConvey;
    private final Map<String, String> data;
    private final String expectedDecision; // null → no decision pending
    private final int maxSentences;
    private final List<String> recentUtterances;

    public NarrationPayload(String situation, List<String> mustConvey, Map<String, String> data,
                            String expectedDecision, int maxSentences, List<String> recentUtterances) {
        this.situation = situation == null ? "" : situation;
        this.mustConvey = mustConvey == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(mustConvey);
        this.data = data == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(data));
        this.expectedDecision = expectedDecision;
        this.maxSentences = maxSentences <= 0 ? 4 : maxSentences;
        this.recentUtterances = recentUtterances == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(recentUtterances);
    }

    public String getSituation() {
        return situation;
    }

    public List<String> getMustConvey() {
        return mustConvey;
    }

    /** Values that must appear VERBATIM in the narration (numbers, the user's quoted question, …). */
    public Map<String, String> getData() {
        return data;
    }

    public String getExpectedDecision() {
        return expectedDecision;
    }

    public int getMaxSentences() {
        return maxSentences;
    }

    /** The narrator's last few utterances — for variation ("do not repeat these openings"). */
    public List<String> getRecentUtterances() {
        return recentUtterances;
    }
}
