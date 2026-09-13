package com.aresstack.askai.research.runtime.team;

/**
 * The SCOPING phase contract: {@link ScopingAssistantOutputParser} producing a
 * {@link ScopingAssistantOutput}. (Historical note: the RA-P6.5 USEFUL-FIRST-TURN rejection —
 * brief-only first turns failing the parse — is long gone; only a non-blank assistantMessage is
 * required, and the first-turn HELP pressure lives in the prompt plus the gate-9b offer nudge.)
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
                // K4: the concept is the ONE scoping artifact — the legacy brief field left the
                // grammar entirely (this schema only exists WITH the concept tools).
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
                // Zielbild slice 1: the concept (mindmap) IS the positive working space —
                // addFacet/confirmFacet left the model contract (no more facet duplication of
                // card names), and excludeFacet has its own one-command action. The runtime
                // validator still accepts them for host paths and old transcripts.
                + "{\"type\":\"object\",\"properties\":{\"kind\":{\"type\":\"string\","
                + "\"enum\":["
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
                + "\"remove\",\"exclude\",\"resolve\",\"offer\",\"rename\",\"rewrite\"]},"
                + "\"path\":{\"type\":\"array\",\"maxItems\":6,\"items\":"
                + "{\"type\":\"string\"}},"
                + "\"parent\":{\"type\":\"array\",\"maxItems\":6,\"items\":"
                + "{\"type\":\"string\"}},"
                + "\"name\":{\"type\":\"string\"},"
                + "\"topic\":{\"type\":\"string\"},"
                + "\"conflictId\":{\"type\":\"string\"},"
                + "\"decision\":{\"type\":\"string\",\"enum\":[\"REMOVE\","
                + "\"KEEP_SUPPRESSED\"]},"
                + "\"suggestions\":{\"type\":\"array\",\"maxItems\":5,\"items\":"
                + "{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"minLength\":1},"
                + "\"purpose\":{\"type\":\"string\"}},"
                + "\"required\":[\"query\"]}},"
                + "\"leaves\":{\"type\":\"array\",\"maxItems\":12,\"items\":"
                + "{\"type\":\"string\",\"minLength\":1}}},"
                + "\"required\":[\"type\"]}"
                + "},\"required\":[\"assistantMessage\",\"conceptAction\"]}";
    }
}
