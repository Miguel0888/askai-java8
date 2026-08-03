package com.aresstack.askai.research.acp;

import com.aresstack.askai.acp.AcpPromptState;
import com.aresstack.askai.acp.AcpUpdate;
import com.aresstack.askai.research.backend.ResearchActivityKind;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;

/**
 * THE single place that maps neutral ACP events onto {@link ResearchBackendEvent}s — no mapping logic in the
 * session, UI or backend. Sequence numbers stay the backend's per-session monotonic counter (assigned by the
 * caller); the ACP update's own sequence is carried in the detail for diagnostics. Tokens never appear in any
 * mapped text.
 */
public final class ResearchAcpEventMapper {

    private ResearchAcpEventMapper() {
    }

    /** The one in-place progress card per run is addressed by this stable id. */
    public static String runActivityId(String promptId) {
        return "research-run-" + promptId;
    }

    /** ACP content/thought/other update → a backend event builder (caller stamps envelope + sequence). */
    public static ResearchBackendEvent.Builder mapUpdate(AcpUpdate update) {
        switch (update.getKind()) {
            case THOUGHT:
                return ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                        .activity("acp-th-" + update.getPromptId(), ResearchActivityKind.THINKING_UPDATE,
                                "Thinking", update.getText());
            case MESSAGE:
                // The research ACP extension travels as MESSAGE lines with a machine envelope; those are
                // decoded into TYPED events and never rendered as chat text. Everything else stays a
                // normal assistant message.
                if (ResearchRunWire.isWireLine(update.getText())) {
                    return mapWireLine(update);
                }
                return ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                        .text(update.getText());
            case OTHER:
            default:
                // Custom/unknown research extensions surface as tool-style activity, never crash the flow.
                return ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                        .activity("acp-x-" + update.getPromptId(), ResearchActivityKind.TOOL_UPDATE,
                                "Agent event", update.getText());
        }
    }

    /** Decode one machine-envelope line; unknown wire types degrade to a RUN_LOG (never a bubble). */
    private static ResearchBackendEvent.Builder mapWireLine(AcpUpdate update) {
        String text = update.getText();
        String type = ResearchRunWire.typeOf(text);
        String activityId = runActivityId(update.getPromptId());
        if (ResearchRunWire.TYPE_PROGRESS.equals(type)) {
            java.util.Map<String, String> f = ResearchRunWire.fields(text);
            return ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                    .activity(activityId, ResearchActivityKind.TOOL_UPDATE, "", "")
                    .runProgress(new com.aresstack.askai.research.backend.ResearchRunProgressInfo(
                            update.getPromptId(),
                            ResearchRunWire.intField(f, "pages"),
                            ResearchRunWire.intField(f, "sources"),
                            ResearchRunWire.intField(f, "hosts"),
                            ResearchRunWire.intField(f, "tools"),
                            f.get("activity"),
                            ResearchRunWire.decodedField(f, "query"),
                            f.get("url"),
                            ResearchRunWire.decodedField(f, "host"),
                            ResearchRunWire.decodedField(f, "title")));
        }
        if (ResearchRunWire.TYPE_OUTCOME.equals(type)) {
            java.util.Map<String, String> f = ResearchRunWire.fields(text);
            return ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                    .activity(activityId, ResearchActivityKind.TOOL_UPDATE, "", "")
                    .runOutcome(new com.aresstack.askai.research.backend.ResearchRunOutcomeInfo(
                            update.getPromptId(),
                            f.get("stop"),
                            ResearchRunWire.intField(f, "pages"),
                            ResearchRunWire.intField(f, "sources"),
                            ResearchRunWire.intField(f, "hosts"),
                            ResearchRunWire.intField(f, "min_sources"),
                            ResearchRunWire.intField(f, "min_hosts"),
                            Boolean.parseBoolean(f.get("recoverable")),
                            f.get("limitation"), f.get("action")));
        }
        if (ResearchRunWire.TYPE_SCOPE.equals(type)) {
            java.util.Map<String, String> f = ResearchRunWire.fields(text);
            // command → title, question → text, aspects → newline-joined technicalDetail. The host
            // re-validates the command against its live state machine before executing it.
            StringBuilder aspects = new StringBuilder();
            for (String aspect : ResearchRunWire.decodedList(f, "aspects")) {
                if (aspects.length() > 0) {
                    aspects.append('\n');
                }
                aspects.append(aspect);
            }
            return ResearchBackendEvent.builder(ResearchBackendEventType.SCOPE_PROPOSAL)
                    .title(f.get("command") == null ? "" : f.get("command"))
                    .text(ResearchRunWire.decodedField(f, "question"))
                    .messages("", aspects.toString());
        }
        if (ResearchRunWire.TYPE_GREETED.equals(type)) {
            return ResearchBackendEvent.builder(ResearchBackendEventType.GREETING_DONE);
        }
        if (ResearchRunWire.TYPE_BRIEF.equals(type)) {
            java.util.Map<String, String> f = ResearchRunWire.fields(text);
            return ResearchBackendEvent.builder(ResearchBackendEventType.RESEARCH_BRIEF)
                    .title(ResearchRunWire.decodedField(f, "phase"))
                    .text(ResearchRunWire.decodedField(f, "content"));
        }
        if (ResearchRunWire.TYPE_SCOPEASSIST.equals(type)) {
            java.util.Map<String, String> f = ResearchRunWire.fields(text);
            java.util.List<com.aresstack.askai.research.backend.ScopingAssistantUpdate.Suggestion> suggestions =
                    new java.util.ArrayList<
                            com.aresstack.askai.research.backend.ScopingAssistantUpdate.Suggestion>();
            for (String[] record : ResearchRunWire.decodedSuggestions(f)) {
                int priority;
                try {
                    priority = Integer.parseInt(record[2]);
                } catch (RuntimeException notANumber) {
                    priority = 1;
                }
                suggestions.add(new com.aresstack.askai.research.backend.ScopingAssistantUpdate.Suggestion(
                        record[0], record[1], priority));
            }
            com.aresstack.askai.research.backend.ScopingAssistantUpdate projection =
                    new com.aresstack.askai.research.backend.ScopingAssistantUpdate(
                            ResearchRunWire.decodedField(f, "phase"),
                            suggestions,
                            f.get("advice") == null ? "NEUTRAL" : f.get("advice"),
                            ResearchRunWire.decodedField(f, "advicereason"));
            return ResearchBackendEvent.builder(ResearchBackendEventType.SCOPING_PROJECTION)
                    .scopingProjection(projection);
        }
        if (ResearchRunWire.TYPE_ATTENTION.equals(type)) {
            java.util.Map<String, String> f = ResearchRunWire.fields(text);
            // reason → title, state (REQUIRED|RESOLVED) → text, domain family + url → messages.
            return ResearchBackendEvent.builder(ResearchBackendEventType.USER_ATTENTION)
                    .activity("attention-" + f.get("domain"), ResearchActivityKind.TOOL_UPDATE,
                            f.get("reason") == null ? "UNKNOWN" : f.get("reason"),
                            f.get("state") == null ? "REQUIRED" : f.get("state"))
                    .messages(f.get("domain") == null ? "" : f.get("domain"),
                            f.get("url") == null ? "" : f.get("url"));
        }
        if (ResearchRunWire.TYPE_MANUAL_SEARCH_STARTED.equals(type)
                || ResearchRunWire.TYPE_MANUAL_SEARCH_PROGRESS.equals(type)
                || ResearchRunWire.TYPE_MANUAL_SEARCH_COMPLETED.equals(type)
                || ResearchRunWire.TYPE_MANUAL_SEARCH_REVIEW.equals(type)
                || ResearchRunWire.TYPE_MANUAL_SEARCH_FAILED.equals(type)) {
            return mapManualSearch(text, type);
        }
        // TYPE_LOG and anything unknown: technical details only.
        return ResearchBackendEvent.builder(ResearchBackendEventType.RUN_LOG)
                .activity(activityId, ResearchActivityKind.TOOL_UPDATE, "", "")
                .text(ResearchRunWire.TYPE_LOG.equals(type) ? ResearchRunWire.logText(text) : text);
    }

    /**
     * A user-triggered web search lifecycle line → a {@code MANUAL_SEARCH} event: {@code title} is the sub-kind
     * (started|progress|completed|failed), {@code text} is the user-facing line and {@code technicalDetail}
     * carries the correlating requestId. The activity id is keyed by the requestId so one search owns one card.
     */
    private static ResearchBackendEvent.Builder mapManualSearch(String text, String type) {
        java.util.Map<String, String> f = ResearchRunWire.fields(text);
        String requestId = f.get("request_id") == null ? "" : f.get("request_id");
        String subKind;
        String message;
        if (ResearchRunWire.TYPE_MANUAL_SEARCH_STARTED.equals(type)) {
            subKind = "started";
            String query = ResearchRunWire.decodedField(f, "query");
            message = query.isEmpty() ? "Websuche läuft…" : "Websuche: " + query;
        } else if (ResearchRunWire.TYPE_MANUAL_SEARCH_PROGRESS.equals(type)) {
            subKind = "progress";
            message = ResearchRunWire.decodedField(f, "note");
        } else if (ResearchRunWire.TYPE_MANUAL_SEARCH_COMPLETED.equals(type)) {
            subKind = "completed";
            int results = ResearchRunWire.intField(f, "results");
            message = results == 1 ? "1 Treffer" : results + " Treffer";
        } else if (ResearchRunWire.TYPE_MANUAL_SEARCH_REVIEW.equals(type)) {
            subKind = "review_" + (f.get("state") == null ? "" : f.get("state").trim());
            message = "";
        } else {
            subKind = "failed";
            message = manualSearchFailureText(f.get("reason"));
        }
        return ResearchBackendEvent.builder(ResearchBackendEventType.MANUAL_SEARCH)
                .activity("manual-search-" + requestId, ResearchActivityKind.TOOL_UPDATE, subKind, message)
                .messages("", requestId);
    }

    private static String manualSearchFailureText(String reason) {
        if ("SEARCH_UNAVAILABLE".equals(reason)) {
            return "Websuche nicht verfügbar.";
        }
        if ("CANCELLED".equals(reason)) {
            return "Websuche abgebrochen.";
        }
        if ("EMPTY_QUERY".equals(reason)) {
            return "Leere Suchanfrage.";
        }
        if ("SEARCH_TECHNICAL_PROBLEM".equals(reason) || "MCP_UNAVAILABLE".equals(reason)
                || "RERANKER_UNAVAILABLE".equals(reason) || "RERANKER_TIMEOUT".equals(reason)
                || "RERANKER_INVALID_RESPONSE".equals(reason)
                || "RERANKER_CONFIGURATION_ERROR".equals(reason)) {
            // A technical failure the user can retry (browser/SERP/reranker) — not an honest empty result.
            return "Websuche technisch fehlgeschlagen. Bitte erneut versuchen.";
        }
        return "Websuche fehlgeschlagen.";
    }

    /** ACP terminal → completion/failure builder ({@code null} for CANCELLED: cancel is user-driven, silent). */
    public static ResearchBackendEvent.Builder mapTerminal(AcpPromptState state, String detail) {
        switch (state) {
            case COMPLETED:
                // MUST be the COMPLETED event type: the session clears its turn-in-flight flag on it.
                // The terminal stays INVISIBLE (empty text, no bubble) — the user-facing message comes
                // exclusively from the structured RUN_OUTCOME result card.
                return ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED)
                        .text("");
            case FAILED:
                return ResearchBackendEvent.builder(ResearchBackendEventType.ERROR)
                        .messages("The research agent failed.", detail == null ? "" : detail);
            case CANCELLED:
            default:
                return null;
        }
    }
}
