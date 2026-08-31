package com.aresstack.askai.research.runtime.team;

/**
 * The SCOPING phase contract: {@link ScopingAssistantOutputParser} producing a {@link ScopingAssistantOutput},
 * with the stricter USEFUL-FIRST-TURN rule enforced on top (RA-P6.5): a substantive scoping turn must HELP
 * before it asks — so besides the required research brief it must also carry an exploration map AND at least
 * one search suggestion. A reply that only asks the user to narrow the topic (brief-only, no map, no
 * suggestion) is rejected here, triggering one bounded repair and then an honest failure — it is no longer a
 * valid first scoping turn. The phase-agnostic GREETING is exempt: it uses the generic contract, not this one.
 *
 * <p>With the concept tools active the contract additionally publishes a GENERATION-TIME schema
 * (Ollama structured outputs): the K2c gate showed gemma emitting well-intentioned turns (good
 * conceptActions!) inside brace-broken JSON — a grammar stops a small model from losing count
 * where a repair prompt cannot. Without the concept tools the contract stays schema-free, so the
 * behaviour against an older host is unchanged.</p>
 */
public final class ScopingPhaseOutputContract implements PhaseOutputContract {

    private final boolean conceptTools;

    public ScopingPhaseOutputContract() {
        this(false);
    }

    public ScopingPhaseOutputContract(boolean conceptTools) {
        this.conceptTools = conceptTools;
    }

    public PhaseParseResult parse(String rawModelText) {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(rawModelText);
        return result.isOk()
                ? PhaseParseResult.ok(result.getOutput())
                : PhaseParseResult.fail(result.getError());
    }

    /**
     * The scoping answer's shape as a grammar. Deliberate choices: {@code assistantMessage} and
     * {@code conceptAction} are REQUIRED (the action decision is always explicit — type "none"
     * says "I change nothing"); the concept action types are an enum; list sizes carry maxItems
     * (the Weidezaun lesson: a model that loses count is stopped by the grammar, not by prose);
     * scope operations pin {@code kind} to the known enum AND require a {@code facetId} in the
     * TECHNICAL-ID shape (live-gate 2: missing ids starved the Weidezaun; live-gate 3: minLength
     * alone let a 100-char prompt placeholder become a canonical facet — the pattern blocks
     * commas, colons, braces and sentences at generation time, and the runtime re-checks it for
     * engines that ignore {@code pattern}). The grammar is FLAT on purpose (the K2c lesson: no
     * oneOf for small models), so {@code facetId} is demanded on EVERY operation — on non-facet
     * kinds the codec simply ignores the extra field, which is far cheaper than a branched
     * grammar. Advisory suggestions require NON-EMPTY label+query — the first live gate saw
     * {@code "label":""} slip past a presence-only schema. Per-kind REQUIRED fields (addExclusion
     * needs value, setMission needs mission, …) cannot be expressed flatly — that stays the
     * runtime validator's job, with the field reference travelling in the repair feedback.
     */
    @Override
    public String outputSchemaJson() {
        if (!conceptTools) {
            return null;
        }
        return "{\"type\":\"object\",\"properties\":{"
                + "\"assistantMessage\":{\"type\":\"string\"},"
                + "\"researchBriefMarkdown\":{\"type\":\"string\"},"
                + "\"searchSuggestions\":{\"type\":\"array\",\"maxItems\":5,\"items\":"
                + "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\","
                + "\"minLength\":1},"
                + "\"purpose\":{\"type\":\"string\"},\"priority\":{\"type\":\"integer\"}},"
                + "\"required\":[\"query\"]}},"
                + "\"advice\":{\"type\":\"object\",\"properties\":{"
                + "\"recommendation\":{\"type\":\"string\",\"enum\":[\"STAY\",\"CONTINUE\","
                + "\"NEUTRAL\"]},\"reason\":{\"type\":\"string\"}},"
                + "\"required\":[\"recommendation\"]},"
                + "\"scopePatch\":{\"type\":\"object\",\"properties\":{"
                + "\"operations\":{\"type\":\"array\",\"maxItems\":8,\"items\":"
                + "{\"type\":\"object\",\"properties\":{\"kind\":{\"type\":\"string\","
                + "\"enum\":[\"setMission\",\"addFacet\",\"confirmFacet\",\"excludeFacet\","
                + "\"setFacetEmphasis\",\"setCrossCuttingEmphasis\",\"setDeliverable\","
                + "\"addDomain\",\"addContext\",\"addPerspective\",\"addConstraint\","
                + "\"addExclusion\",\"addTerminology\",\"setGeographicScope\","
                + "\"setTemporalScope\",\"addUnresolvedIssue\",\"resolveIssue\"]},"
                + "\"facetId\":{\"type\":\"string\",\"minLength\":1,"
                + "\"pattern\":\"^[a-z0-9][a-z0-9_-]{0,63}$\"},"
                + "\"label\":{\"type\":\"string\",\"minLength\":1}},"
                + "\"required\":[\"kind\",\"facetId\"]}}}},"
                + "\"unresolvedIssues\":{\"type\":\"array\",\"maxItems\":6,\"items\":"
                + "{\"type\":\"object\"}},"
                + "\"orientationSuggestions\":{\"type\":\"array\",\"maxItems\":3,\"items\":"
                + "{\"type\":\"object\",\"properties\":{"
                + "\"label\":{\"type\":\"string\",\"minLength\":1},"
                + "\"query\":{\"type\":\"string\",\"minLength\":1},"
                + "\"rationale\":{\"type\":\"string\"}},"
                + "\"required\":[\"label\",\"query\"]}},"
                + "\"conceptAction\":{\"type\":\"object\",\"properties\":{"
                + "\"type\":{\"type\":\"string\",\"enum\":[\"none\",\"read\",\"add\","
                + "\"remove\"]},"
                + "\"path\":{\"type\":\"array\",\"maxItems\":6,\"items\":"
                + "{\"type\":\"string\"}},"
                + "\"parent\":{\"type\":\"array\",\"maxItems\":6,\"items\":"
                + "{\"type\":\"string\"}},"
                + "\"name\":{\"type\":\"string\"}},"
                + "\"required\":[\"type\"]}"
                + "},\"required\":[\"assistantMessage\",\"conceptAction\"]}";
    }
}
