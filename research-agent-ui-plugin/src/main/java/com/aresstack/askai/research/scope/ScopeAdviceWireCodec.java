package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.AdviceDecision;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.CandidateOffer;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceRequest;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Host side of the Z4b advice-chooser wire (Gson): encode the offers the runtime's
 * {@code ScopeAdviceWire} parses, decode the decision it renders. The decoder NEVER throws — a
 * malformed payload is a typed {@code INVALID_RESPONSE}, because "the model/wire broke" must stay
 * distinguishable from "nothing worth asking". The chooser knobs travel WITH the request: the
 * host owns configuration, the runtime obeys.
 */
public final class ScopeAdviceWireCodec {

    private ScopeAdviceWireCodec() {
    }

    public static String encodeRequest(ChoiceRequest request, double temperature,
                                       int maxOutputTokens) {
        JsonObject root = new JsonObject();
        root.addProperty("mission", request.getMission());
        JsonArray candidates = new JsonArray();
        for (CandidateOffer offer : request.getCandidates()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", offer.getCandidateId());
            entry.addProperty("reason", offer.getReason().name());
            entry.addProperty("topic", offer.getTopicText());
            entry.addProperty("context", offer.getContextNote());
            candidates.add(entry);
        }
        root.add("candidates", candidates);
        JsonArray guards = new JsonArray();
        for (String note : request.getDriftGuardNotes()) {
            guards.add(note);
        }
        root.add("driftGuards", guards);
        root.addProperty("temperature", temperature);
        root.addProperty("maxOutputTokens", maxOutputTokens);
        return root.toString();
    }

    /** Decode the runtime's decision payload; malformed input is a typed failure, never a throw. */
    public static ChoiceResult decodeResult(String payloadJson) {
        try {
            JsonObject root = JsonParser.parseString(payloadJson).getAsJsonObject();
            ChoiceResult.Status status =
                    ChoiceResult.Status.valueOf(root.get("status").getAsString());
            String message = root.has("message") ? root.get("message").getAsString() : "";
            if (status != ChoiceResult.Status.OK) {
                return ChoiceResult.failure(status, message);
            }
            String decision = root.get("decision").getAsString();
            String assistantMessage = root.has("assistantMessage")
                    ? root.get("assistantMessage").getAsString() : "";
            if (AdviceDecision.Decision.NONE.name().equals(decision)) {
                return ChoiceResult.ok(AdviceDecision.none(assistantMessage));
            }
            return ChoiceResult.ok(AdviceDecision.ask(
                    root.get("candidateId").getAsString(), assistantMessage));
        } catch (RuntimeException malformed) {
            return ChoiceResult.failure(ChoiceResult.Status.INVALID_RESPONSE,
                    "malformed wire payload: " + malformed.getMessage());
        }
    }
}
