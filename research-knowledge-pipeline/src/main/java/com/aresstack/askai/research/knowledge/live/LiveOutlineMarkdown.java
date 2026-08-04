package com.aresstack.askai.research.knowledge.live;

/**
 * Renders a {@link LiveOutlineProjection} as the Markdown shown in the "outline" artifact slot. The header
 * VISIBLY marks it as a live, mobile projection (never an approved outline; no approve/reject controls) so
 * a reader can never mistake it for the frozen document structure that a later slice commits before drafting.
 */
public final class LiveOutlineMarkdown {

    private LiveOutlineMarkdown() {
    }

    public static String render(LiveOutlineProjection projection) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Live Outline\n\n");
        int passages = 0;
        for (LiveTopicProjection topic : projection.getTopics()) {
            passages += topic.getMemberPassageIds().size();
        }
        sb.append("_Automatisch aus dem aktuellen Wissenskorpus abgeleitet — aktualisiert sich mit jeder "
                + "neuen Quelle. Keine freigegebene Gliederung._\n\n");
        sb.append("_Revision ").append(projection.getProjectionRevision())
                .append(" · ").append(projection.getTopics().size()).append(" Themen · ")
                .append(passages).append(" Passagen_\n");
        if (projection.getSections().isEmpty()) {
            sb.append("\n_Noch keine verarbeiteten Quellen — die Gliederung entsteht, sobald erste "
                    + "Passagen vorliegen._\n");
            return sb.toString();
        }
        for (LiveOutlineSection section : projection.getSections()) {
            sb.append("\n## ").append(section.getTitle().isEmpty() ? "(untitled)" : section.getTitle())
                    .append('\n');
            if (!section.getPassageIds().isEmpty()) {
                sb.append("_").append(section.getPassageIds().size()).append(" Passagen_\n");
            }
            for (String question : section.getUncoveredQuestions()) {
                sb.append("- offene Frage: ").append(question).append('\n');
            }
        }
        return sb.toString();
    }
}
