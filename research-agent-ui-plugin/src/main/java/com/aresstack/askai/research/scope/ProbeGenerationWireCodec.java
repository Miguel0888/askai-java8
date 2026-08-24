package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeCalibrationProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGeneration;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationRequest;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Host side of the Z3b-3 probe-generation wire (Gson, like every host-side scope codec): encode
 * the request the runtime's {@code ProbeGenerationWire} parses, decode the result it renders. The
 * decoder NEVER throws — a malformed payload is a typed {@code INVALID_RESPONSE}, because no
 * exception may bypass the sweep's outcome contract. The generator knobs travel WITH the request:
 * the host owns configuration, the runtime obeys.
 */
public final class ProbeGenerationWireCodec {

    private ProbeGenerationWireCodec() {
    }

    public static String encodeRequest(ProbeGenerationRequest request,
                                       double temperature, int maxOutputTokens,
                                       int controlsPerAnchor) {
        // Deliberately NO scopeRevision on this wire: the runtime never uses it, and a send-time
        // revision could contradict the payload snapshot — the host orchestration owns R.
        JsonObject root = new JsonObject();
        root.addProperty("mission", request.getMission());
        root.add("domains", texts(request.getDomains()));
        root.add("contexts", texts(request.getContexts()));
        root.add("knownFacetLabels", texts(request.getKnownFacetLabels()));
        JsonArray anchors = new JsonArray();
        for (ScopeAnchor anchor : request.getAnchors()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("anchorId", anchor.getAnchorId());
            entry.addProperty("membership", anchor.getMembership().name());
            entry.addProperty("semanticText", anchor.getSemanticText());
            anchors.add(entry);
        }
        root.add("anchors", anchors);
        root.addProperty("targetCount", request.getTargetCount());
        root.addProperty("temperature", temperature);
        root.addProperty("maxOutputTokens", maxOutputTokens);
        root.addProperty("controlsPerAnchor", controlsPerAnchor);
        return root.toString();
    }

    /** Decode the runtime's result payload; malformed input is a typed failure, never a throw. */
    public static ProbeGenerationResult decodeResult(String payloadJson) {
        try {
            JsonObject root = JsonParser.parseString(payloadJson).getAsJsonObject();
            ProbeGenerationResult.Status status =
                    ProbeGenerationResult.Status.valueOf(root.get("status").getAsString());
            String message = root.has("message") ? root.get("message").getAsString() : "";
            if (status != ProbeGenerationResult.Status.OK) {
                return ProbeGenerationResult.failure(status, message);
            }
            List<ScopeProbe> broadProbes = new ArrayList<ScopeProbe>();
            for (JsonElement entry : root.getAsJsonArray("broadProbes")) {
                JsonObject probe = entry.getAsJsonObject();
                broadProbes.add(new ScopeProbe(
                        probe.get("id").getAsString(), probe.get("text").getAsString()));
            }
            List<ScopeCalibrationProbe> calibrationProbes =
                    new ArrayList<ScopeCalibrationProbe>();
            for (JsonElement entry : root.getAsJsonArray("calibrationProbes")) {
                JsonObject control = entry.getAsJsonObject();
                calibrationProbes.add(new ScopeCalibrationProbe(
                        control.get("id").getAsString(),
                        control.get("parentAnchorId").getAsString(),
                        control.get("text").getAsString()));
            }
            return ProbeGenerationResult.ok(new ProbeGeneration(broadProbes, calibrationProbes,
                    root.get("requestedBroadCount").getAsInt()), message);
        } catch (RuntimeException malformed) {
            return ProbeGenerationResult.failure(ProbeGenerationResult.Status.INVALID_RESPONSE,
                    "malformed wire payload: " + malformed.getMessage());
        }
    }

    private static JsonArray texts(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
