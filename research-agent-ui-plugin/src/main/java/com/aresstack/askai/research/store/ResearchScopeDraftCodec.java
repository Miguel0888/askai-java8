package com.aresstack.askai.research.store;

import com.aresstack.askai.research.domain.scope.CoverageEmphasis;
import com.aresstack.askai.research.domain.scope.CrossCuttingEmphasis;
import com.aresstack.askai.research.domain.scope.ResearchDeliverable;
import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeFacet;
import com.aresstack.askai.research.domain.scope.SynthesisPolicy;
import com.aresstack.askai.research.domain.scope.UnresolvedScopeIssue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON codec for the {@link ResearchScopeDraft}. It lives HERE and not in the domain module on purpose: the
 * domain core has no dependencies at all (no Gson), and the persistence format is an adapter concern.
 * <p>
 * The format is VERSIONED. Reading is forward-tolerant for additions (unknown fields are ignored, so an
 * older build can still read a newer draft's known parts) but STRICT about the schema version: a document
 * from a future schema is reported as unsupported rather than silently half-read — a scope the user
 * confirmed must never come back subtly different.
 */
public final class ResearchScopeDraftCodec {

    /** The schema this build writes and can read. */
    public static final int SCHEMA_VERSION = 1;

    private ResearchScopeDraftCodec() {
    }

    public static String toJson(ResearchScopeDraft draft) {
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", SCHEMA_VERSION);
        document.addProperty("revision", draft.getRevision());
        document.addProperty("mission", draft.getMission());
        document.add("domains", strings(draft.getDomains()));
        document.add("contexts", strings(draft.getContexts()));
        document.add("facets", facets(draft.getFacets()));
        document.add("exclusions", strings(draft.getExclusions()));
        document.add("perspectives", strings(draft.getPerspectives()));
        document.add("constraints", strings(draft.getConstraints()));
        document.addProperty("geographicScope", draft.getGeographicScope());
        document.addProperty("temporalScope", draft.getTemporalScope());
        document.add("terminology", strings(draft.getTerminology()));
        document.add("unresolvedIssues", issues(draft.getUnresolvedIssues()));
        document.add("coverageEmphasis", coverage(draft.getCoverageEmphasis()));
        document.add("crossCuttingEmphasis", crossCutting(draft.getCrossCuttingEmphasis()));
        document.add("deliverable", deliverable(draft.getDeliverable()));
        return document.toString();
    }

    /**
     * @throws UnsupportedSchemaException when the document was written by a newer schema
     * @throws IllegalArgumentException   when it is not a readable scope draft at all
     */
    public static ResearchScopeDraft fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("empty document");
        }
        JsonObject document;
        try {
            document = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException notJson) {
            throw new IllegalArgumentException("not a JSON object: " + notJson.getMessage(), notJson);
        }
        int schemaVersion = intOf(document, "schemaVersion", 0);
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("no schemaVersion");
        }
        if (schemaVersion > SCHEMA_VERSION) {
            throw new UnsupportedSchemaException(schemaVersion, SCHEMA_VERSION);
        }
        ResearchScopeDraft.Builder builder = ResearchScopeDraft.builder()
                .revision(longOf(document, "revision"))
                .mission(stringOf(document, "mission"))
                .domains(stringList(document, "domains"))
                .contexts(stringList(document, "contexts"))
                .exclusions(stringList(document, "exclusions"))
                .perspectives(stringList(document, "perspectives"))
                .constraints(stringList(document, "constraints"))
                .geographicScope(stringOf(document, "geographicScope"))
                .temporalScope(stringOf(document, "temporalScope"))
                .terminology(stringList(document, "terminology"))
                .deliverable(readDeliverable(document.getAsJsonObject("deliverable")));
        // Open points were plain strings before they became first-class issues: such a document is still
        // read (description only), so an early draft never loses its open questions.
        for (JsonElement element : array(document, "unresolvedIssues")) {
            if (element.isJsonPrimitive()) {
                builder.addUnresolvedIssue(element.getAsString());
            } else if (element.isJsonObject()) {
                JsonObject issue = element.getAsJsonObject();
                builder.putUnresolvedIssue(new UnresolvedScopeIssue(stringOf(issue, "issueId"),
                        stringOf(issue, "description"), stringList(issue, "affectedFacetIds"),
                        enumOf(UnresolvedScopeIssue.Significance.class, stringOf(issue, "significance"),
                                UnresolvedScopeIssue.Significance.SIGNIFICANT)));
            }
        }
        for (JsonElement element : array(document, "facets")) {
            JsonObject facet = element.getAsJsonObject();
            builder.putFacet(new ScopeFacet(stringOf(facet, "facetId"), stringOf(facet, "label"),
                    enumOf(ScopeFacet.Status.class, stringOf(facet, "status"),
                            ScopeFacet.Status.PROVISIONAL),
                    stringOf(facet, "rationale")));
        }
        for (JsonElement element : array(document, "coverageEmphasis")) {
            JsonObject emphasis = element.getAsJsonObject();
            builder.putCoverageEmphasis(new CoverageEmphasis(stringOf(emphasis, "targetFacetId"),
                    enumOf(CoverageEmphasis.Importance.class, stringOf(emphasis, "importance"),
                            CoverageEmphasis.Importance.MEDIUM),
                    enumOf(CoverageEmphasis.ResearchDepth.class, stringOf(emphasis, "researchDepth"),
                            CoverageEmphasis.ResearchDepth.STANDARD),
                    intOf(emphasis, "outputShareHint", CoverageEmphasis.NO_SHARE_HINT)));
        }
        for (JsonElement element : array(document, "crossCuttingEmphasis")) {
            JsonObject emphasis = element.getAsJsonObject();
            builder.putCrossCuttingEmphasis(new CrossCuttingEmphasis(stringOf(emphasis, "dimension"),
                    enumOf(CoverageEmphasis.Importance.class, stringOf(emphasis, "importance"),
                            CoverageEmphasis.Importance.MEDIUM)));
        }
        return builder.build();
    }

    /** A document from a newer schema — readable only by a newer build. */
    public static final class UnsupportedSchemaException extends RuntimeException {
        private final int documentVersion;

        UnsupportedSchemaException(int documentVersion, int supportedVersion) {
            super("scope draft schema " + documentVersion + " is newer than this build supports ("
                    + supportedVersion + ")");
            this.documentVersion = documentVersion;
        }

        public int getDocumentVersion() {
            return documentVersion;
        }
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray issues(List<UnresolvedScopeIssue> values) {
        JsonArray array = new JsonArray();
        for (UnresolvedScopeIssue issue : values) {
            JsonObject item = new JsonObject();
            item.addProperty("issueId", issue.getIssueId());
            item.addProperty("description", issue.getDescription());
            item.add("affectedFacetIds", strings(issue.getAffectedFacetIds()));
            item.addProperty("significance", issue.getSignificance().name());
            array.add(item);
        }
        return array;
    }

    private static JsonArray facets(List<ScopeFacet> values) {
        JsonArray array = new JsonArray();
        for (ScopeFacet facet : values) {
            JsonObject item = new JsonObject();
            item.addProperty("facetId", facet.getFacetId());
            item.addProperty("label", facet.getLabel());
            item.addProperty("status", facet.getStatus().name());
            item.addProperty("rationale", facet.getRationale());
            array.add(item);
        }
        return array;
    }

    private static JsonArray coverage(List<CoverageEmphasis> values) {
        JsonArray array = new JsonArray();
        for (CoverageEmphasis emphasis : values) {
            JsonObject item = new JsonObject();
            item.addProperty("targetFacetId", emphasis.getTargetFacetId());
            item.addProperty("importance", emphasis.getImportance().name());
            item.addProperty("researchDepth", emphasis.getResearchDepth().name());
            item.addProperty("outputShareHint", emphasis.getOutputShareHint());
            array.add(item);
        }
        return array;
    }

    private static JsonArray crossCutting(List<CrossCuttingEmphasis> values) {
        JsonArray array = new JsonArray();
        for (CrossCuttingEmphasis emphasis : values) {
            JsonObject item = new JsonObject();
            item.addProperty("dimension", emphasis.getDimension());
            item.addProperty("importance", emphasis.getImportance().name());
            array.add(item);
        }
        return array;
    }

    private static JsonObject deliverable(ResearchDeliverable value) {
        JsonObject item = new JsonObject();
        item.addProperty("targetLengthMin", value.getTargetLengthMin());
        item.addProperty("targetLengthMax", value.getTargetLengthMax());
        item.addProperty("lengthUnit", value.getLengthUnit().name());
        SynthesisPolicy policy = value.getSynthesisPolicy();
        JsonObject synthesis = new JsonObject();
        synthesis.addProperty("categoryFirst", policy.isCategoryFirst());
        synthesis.addProperty("contrastRequired", policy.isContrastRequired());
        synthesis.addProperty("repetitiveEntityPolicy", policy.getRepetitiveEntityPolicy().name());
        synthesis.addProperty("examplePolicy", policy.getExamplePolicy().name());
        item.add("synthesisPolicy", synthesis);
        return item;
    }

    private static ResearchDeliverable readDeliverable(JsonObject document) {
        if (document == null) {
            return ResearchDeliverable.unspecified();
        }
        JsonObject synthesis = document.getAsJsonObject("synthesisPolicy");
        SynthesisPolicy policy = synthesis == null ? SynthesisPolicy.defaults() : new SynthesisPolicy(
                booleanOf(synthesis, "categoryFirst", true),
                booleanOf(synthesis, "contrastRequired", true),
                enumOf(SynthesisPolicy.RepetitiveEntityPolicy.class,
                        stringOf(synthesis, "repetitiveEntityPolicy"),
                        SynthesisPolicy.RepetitiveEntityPolicy.GROUP),
                enumOf(SynthesisPolicy.ExamplePolicy.class, stringOf(synthesis, "examplePolicy"),
                        SynthesisPolicy.ExamplePolicy.REPRESENTATIVE));
        return new ResearchDeliverable(
                intOf(document, "targetLengthMin", ResearchDeliverable.NO_LENGTH),
                intOf(document, "targetLengthMax", ResearchDeliverable.NO_LENGTH),
                enumOf(ResearchDeliverable.LengthUnit.class, stringOf(document, "lengthUnit"),
                        ResearchDeliverable.LengthUnit.UNSPECIFIED),
                policy);
    }

    private static JsonArray array(JsonObject document, String field) {
        JsonArray array = document.getAsJsonArray(field);
        return array == null ? new JsonArray() : array;
    }

    private static List<String> stringList(JsonObject document, String field) {
        List<String> values = new ArrayList<String>();
        for (JsonElement element : array(document, field)) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private static String stringOf(JsonObject document, String field) {
        JsonElement element = document.get(field);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static long longOf(JsonObject document, String field) {
        JsonElement element = document.get(field);
        return element == null || !element.isJsonPrimitive() ? 0L : element.getAsLong();
    }

    private static int intOf(JsonObject document, String field, int fallback) {
        JsonElement element = document.get(field);
        return element == null || !element.isJsonPrimitive() ? fallback : element.getAsInt();
    }

    private static boolean booleanOf(JsonObject document, String field, boolean fallback) {
        JsonElement element = document.get(field);
        return element == null || !element.isJsonPrimitive() ? fallback : element.getAsBoolean();
    }

    /** An unknown enum value falls back instead of failing: a scope must survive a vocabulary extension. */
    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, E fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
