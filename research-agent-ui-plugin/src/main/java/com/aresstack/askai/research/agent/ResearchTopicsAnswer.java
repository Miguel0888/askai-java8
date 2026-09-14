package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * #42 — the host-narrated "topics from sources" answer: topic DISCOVERY presented as
 * suggestions the USER decides about. Deterministic on purpose (the established receipt-truth
 * pattern): no model turn can invent, mutate or recommend an excluded topic — a topic whose
 * title matches a blacklist term is never presented positively, and the answer never touches
 * the concept; the user's normal reply ("Trag X ein") runs through the existing concept
 * operations.
 */
final class ResearchTopicsAnswer {

    private ResearchTopicsAnswer() {
    }

    /** Topic titles minus exact blacklist matches (the user's exclusions stay settled). */
    static List<String> presentableTitles(List<LiveTopicProjection> topics,
                                          List<String> blacklistTerms) {
        List<String> normalizedBlacklist = new ArrayList<String>();
        for (String term : blacklistTerms == null ? new ArrayList<String>() : blacklistTerms) {
            normalizedBlacklist.add(normalize(term));
        }
        List<String> titles = new ArrayList<String>();
        for (LiveTopicProjection topic : topics) {
            String title = topic.getTitle() == null ? "" : topic.getTitle().trim();
            if (title.isEmpty() || normalizedBlacklist.contains(normalize(title))
                    || titles.contains(title)) {
                continue;
            }
            titles.add(title);
        }
        return titles;
    }

    /** The visible chat answer (session language); {@code stale} adds one honest note. */
    static String render(List<String> titles, boolean stale, boolean german) {
        if (titles.isEmpty()) {
            return german
                    ? "Aus den bisher ausgewerteten Quellen ergeben sich keine zusätzlichen "
                            + "Themenfelder jenseits von Konzept und Ausschlüssen."
                    : "The sources analyzed so far yield no additional topic areas beyond "
                            + "the concept and the exclusions.";
        }
        StringBuilder sb = new StringBuilder(german
                ? "Aus den bisher ausgewerteten Quellen ergeben sich zusätzlich diese "
                        + "Themenfelder:"
                : "From the sources analyzed so far, these additional topic areas emerge:");
        for (String title : titles) {
            sb.append("\n- ").append(title);
        }
        sb.append(german
                ? "\n\nDas sind Vorschläge aus dem Quellenmaterial — nichts wurde in dein "
                        + "Konzept übernommen. Sag mir, welche davon ich eintragen soll "
                        + "(z. B. „Trag alle ein" + '“' + " oder „Nur X und Y" + '“'
                        + ")."
                : "\n\nThese are suggestions from the source material — nothing was added to "
                        + "your concept. Tell me which ones to add (e.g. \"add all\" or "
                        + "\"only X and Y\").");
        if (stale) {
            sb.append(german
                    ? "\n\nHinweis: Seitdem sind neue Quellen hinzugekommen — die Themenliste "
                            + "wird im Hintergrund aktualisiert."
                    : "\n\nNote: new sources arrived since this analysis — the topic list is "
                            + "being refreshed in the background.");
        }
        return sb.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
