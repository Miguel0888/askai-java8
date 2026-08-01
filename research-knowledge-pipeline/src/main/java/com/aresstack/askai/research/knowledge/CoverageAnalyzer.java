package com.aresstack.askai.research.knowledge;

import com.aresstack.askai.research.domain.DetailedResearchPlan;
import com.aresstack.askai.research.domain.EvidenceReview;
import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.OutlineRevision;
import com.aresstack.askai.research.domain.ResearchGap;
import com.aresstack.askai.research.domain.ResearchProject;

import java.util.ArrayList;
import java.util.List;

/**
 * The detail-research FEEDBACK LOOP, fachlich: section + existing claims/evidence → coverage → gaps →
 * a detailed research plan with query proposals. Knowledge → gaps → new queries → new sources → new
 * knowledge → re-analysis. The actual web search stays behind the existing SearchStrategy; this class
 * only produces the ORDERS.
 */
public final class CoverageAnalyzer {

    private final IdSequence ids;

    public CoverageAnalyzer(IdSequence ids) {
        this.ids = ids;
    }

    /**
     * Records a gap + plan for every ACTIVE section whose coverage is below the threshold or whose
     * questions are still uncovered; fully covered sections produce nothing. Query proposals combine
     * the section title with the uncovered question — precise enough for the next search round and
     * fully traceable to the gap they address.
     */
    public List<DetailedResearchPlan> planDetailedResearch(ResearchProject project,
                                                           double coverageThreshold) {
        EvidenceReview review = project.buildEvidenceReview();
        List<DetailedResearchPlan> plans = new ArrayList<DetailedResearchPlan>();
        for (EvidenceReview.SectionReview section : review.getSections()) {
            OutlineRevision.Section outlineSection = sectionOf(project, section.getSectionId());
            List<String> gapIds = new ArrayList<String>();
            List<DetailedResearchPlan.SearchQueryProposal> queries =
                    new ArrayList<DetailedResearchPlan.SearchQueryProposal>();
            for (String question : section.getUncoveredQuestions()) {
                ResearchGap gap = project.recordResearchGap(section.getSectionId(),
                        "uncovered question: " + question);
                gapIds.add(gap.getGapId());
                queries.add(new DetailedResearchPlan.SearchQueryProposal(
                        outlineSection.getTitle() + " " + question,
                        "answers uncovered question of section " + section.getSectionId()));
            }
            if (section.getCoverage() < coverageThreshold && !section.getClaims().isEmpty()) {
                ResearchGap gap = project.recordResearchGap(section.getSectionId(),
                        "claims without supporting evidence (coverage " + section.getCoverage() + ")");
                gapIds.add(gap.getGapId());
                queries.add(new DetailedResearchPlan.SearchQueryProposal(
                        outlineSection.getTitle() + " evidence",
                        "raises claim support of section " + section.getSectionId()));
            }
            if (!gapIds.isEmpty()) {
                plans.add(project.recordDetailedResearchPlan(new DetailedResearchPlan(
                        ids.next("detail-plan"), section.getSectionId(), gapIds, queries)));
            }
        }
        return plans;
    }

    private static OutlineRevision.Section sectionOf(ResearchProject project, String sectionId) {
        for (OutlineRevision.Section section : project.activeOutline().getSections()) {
            if (section.getSectionId().equals(sectionId)) {
                return section;
            }
        }
        throw new IllegalArgumentException("unknown section " + sectionId);
    }
}
