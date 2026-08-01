package com.aresstack.askai.research.knowledge;

import com.aresstack.askai.research.domain.AcceptedLimitation;
import com.aresstack.askai.research.domain.Approval;
import com.aresstack.askai.research.domain.Claim;
import com.aresstack.askai.research.domain.DetailedResearchPlan;
import com.aresstack.askai.research.domain.EvidenceBaseline;
import com.aresstack.askai.research.domain.EvidenceLink;
import com.aresstack.askai.research.domain.EvidenceRelation;
import com.aresstack.askai.research.domain.EvidenceReview;
import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.OutlineRevision;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchBrief;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.domain.TopicProposal;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * THE fachliche vertical slice, fully deterministic, without browser and without LLM:
 *
 * <pre>
 * ResearchBrief → SourceCaptures → Sentences → Passages → TopicProposals → OutlineProposal
 * → approved OutlineRevision → section gaps → Claims/EvidenceLinks → EvidenceReview
 * → EvidenceBaseline
 * </pre>
 */
public class ResearchMethodologyVerticalSliceTest {

    private static final Approval USER = new Approval("user", 1000L, "");

    @Test
    public void fromBriefToEvidenceBaseline() {
        IdSequence ids = IdSequence.counting();
        ResearchProject project = new ResearchProject("p1", ids);
        EmbeddingPort embeddings = new VocabularyEmbeddingPort(
                "display", "optics", "battery", "power", "privacy");
        PassageSegmentation segmentation = new PassageSegmentation(
                new RegexSentenceSegmenter(), embeddings, ids);

        // 1. Confirmed research brief (the research PLAN — not the document outline).
        ResearchBrief brief = project.confirmResearchBrief(new ResearchBrief("", 0L,
                "How mature are consumer smart glasses?", "buying advice", "consumers",
                Arrays.asList("consumer devices"), null, "structured report", null, null,
                Arrays.asList("How good are the display optics?",
                        "How long does the battery last under power load?",
                        "What is the regulatory situation?")), USER);
        project.startOrientationResearch(USER);

        // 2. Orientation research produced two captures (structure preserved).
        SourceCapture reviewSite = new SourceCapture("cap-1", "src-1", "https://reviews.example",
                10L, "c1", "Smart glasses review", "", Arrays.asList(
                new SourceCapture.StructuralBlock("b1", SourceCapture.BlockKind.HEADING,
                        "Review", "Display and battery"),
                new SourceCapture.StructuralBlock("b2", SourceCapture.BlockKind.PARAGRAPH, "Review",
                        "The display uses waveguide optics. The display brightness is excellent."
                                + " The optics stay sharp at the edges. The display wins the test."
                                + " The battery drains within two hours. Battery power limits usage."
                                + " Heavy power draw heats the battery. The battery is the weak spot.")));
        SourceCapture vendorSite = new SourceCapture("cap-2", "src-2", "https://vendor.example",
                20L, "c2", "Vendor spec sheet", "", Arrays.asList(
                new SourceCapture.StructuralBlock("b3", SourceCapture.BlockKind.PARAGRAPH, "Specs",
                        "The display resolution is 1080p per eye. The optics module weighs 40 grams."),
                new SourceCapture.StructuralBlock("b4", SourceCapture.BlockKind.PARAGRAPH, "Specs",
                        "The battery holds 450 mAh. Low power mode extends battery life.")));
        project.recordSourceCapture(reviewSite);
        project.recordSourceCapture(vendorSite);

        // 3. Sentences + semantic passages (structure first, then window similarity).
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        for (SourceCapture capture : Arrays.asList(reviewSite, vendorSite)) {
            PassageSegmentation.Result result = segmentation.segment(capture);
            project.recordSentences(result.getSentences());
            project.recordPassages(result.getPassages());
            vectors.putAll(result.getPassageVectors());
        }
        assertTrue("the mixed review paragraph split at the display→battery topic shift",
                project.passages().size() >= 4);

        // 4. Topic clustering: display topics and battery topics separate, no fixed count.
        List<TopicProposal> topicProposals = new TopicClusterer(ids).cluster(
                new java.util.ArrayList<Passage>(project.passages().values()), vectors);
        assertEquals("two evidence topics emerged", 2, topicProposals.size());
        for (TopicProposal proposal : topicProposals) {
            project.proposeTopic(proposal);
        }

        // 5. Concept + outline proposals from evidence; the regulatory question has NO evidence → gap.
        OutlineProposalBuilder.Proposals proposals = new OutlineProposalBuilder(ids)
                .build(brief, topicProposals, project.passages());
        project.proposeConcept(proposals.getConcept());
        project.approveConcept(USER);
        project.proposeOutline(proposals.getOutline());
        assertTrue("the uncovered regulatory question is a visible gap section",
                proposals.getOutline().getSections().size() == 3);
        assertTrue(proposals.getConcept().getOpenQuestions()
                .contains("What is the regulatory situation?"));

        // 6. USER APPROVAL turns proposals into the outline revision with STABLE section ids.
        OutlineRevision outline = project.approveOutline(
                proposals.getOutline().getProposalId(), USER);
        assertEquals(3, outline.getSections().size());
        OutlineRevision.Section displaySection = sectionCovering(project, outline, "display");
        OutlineRevision.Section batterySection = sectionCovering(project, outline, "battery");

        // 7. Claims + evidence links (a claim can be supported AND contradicted — kept visible).
        Passage displayPassage = passageContaining(project, "waveguide");
        Passage specsPassage = passageContaining(project, "1080p");
        Passage batteryPassage = passageContaining(project, "drains");
        Passage lowPowerPassage = passageContaining(project, "Low power");
        Claim displayClaim = project.recordClaim("The display quality is competitive", null,
                Arrays.asList(displaySection.getSectionId()));
        Claim batteryClaim = project.recordClaim("Battery life is the main weakness", null,
                Arrays.asList(batterySection.getSectionId()));
        project.linkEvidence(displayClaim.getClaimId(), displayPassage.getPassageId(),
                EvidenceRelation.SUPPORTS, 0.9, 0.8, 0.9);
        project.linkEvidence(displayClaim.getClaimId(), specsPassage.getPassageId(),
                EvidenceRelation.PROVIDES_CONTEXT, 0.6, 0.9, 0.8);
        project.linkEvidence(batteryClaim.getClaimId(), batteryPassage.getPassageId(),
                EvidenceRelation.SUPPORTS, 0.9, 0.8, 0.9);
        EvidenceLink contradiction = project.linkEvidence(batteryClaim.getClaimId(),
                lowPowerPassage.getPassageId(), EvidenceRelation.CONTRADICTS, 0.7, 0.9, 0.7);

        // 8. Section gap analysis plans the detail research (knowledge → gaps → new queries).
        List<DetailedResearchPlan> plans = new CoverageAnalyzer(ids)
                .planDetailedResearch(project, 0.5);
        assertFalse("the gap section produced a detail research plan", plans.isEmpty());
        boolean regulatoryQueryPlanned = false;
        for (DetailedResearchPlan plan : plans) {
            for (DetailedResearchPlan.SearchQueryProposal query : plan.getQueries()) {
                regulatoryQueryPlanned |= query.getQuery().contains("regulatory situation");
            }
        }
        assertTrue("the uncovered question became a concrete search query", regulatoryQueryPlanned);

        // 9. Evidence review: the contradiction stays visible, coverage is honest.
        EvidenceReview review = project.buildEvidenceReview();
        boolean contradictionVisible = false;
        for (EvidenceReview.SectionReview section : review.getSections()) {
            for (EvidenceReview.ClaimEvidence claim : section.getClaims()) {
                if (claim.getClaimId().equals(batteryClaim.getClaimId())) {
                    contradictionVisible = claim.hasContradiction();
                }
            }
        }
        assertTrue(contradictionVisible);

        // 10. The baseline FIRST: open gaps force accepted limitations, then approval succeeds.
        EvidenceBaseline baseline = project.approveEvidenceBaseline(review,
                Arrays.asList(new AcceptedLimitation("l1",
                        "regulatory evidence still missing", USER)), USER);
        assertTrue(baseline.getIncludedClaimIds().contains(displayClaim.getClaimId()));
        assertTrue(baseline.getKnownContradictionClaimIds().contains(batteryClaim.getClaimId()));
        assertTrue(baseline.getIncludedEvidenceLinkIds().contains(contradiction.getLinkId()));
        assertFalse(project.evidenceBaselines().isEmpty());

        // The whole chain ran deterministically: same ids, same clusters, same baseline every run.
        assertEquals("EvidenceBaselineApproved",
                project.events().get(project.events().size() - 1).getName());
    }

    private static OutlineRevision.Section sectionCovering(ResearchProject project,
                                                           OutlineRevision outline, String keyword) {
        for (OutlineRevision.Section section : outline.getSections()) {
            for (String topicId : section.getTopicIds()) {
                TopicProposal topic = project.topicProposals().get(topicId);
                if (topic != null && representativeTextOf(project, topic).toLowerCase()
                        .contains(keyword)) {
                    return section;
                }
            }
        }
        throw new AssertionError("no section covers '" + keyword + "'");
    }

    private static String representativeTextOf(ResearchProject project, TopicProposal topic) {
        StringBuilder sb = new StringBuilder();
        for (String passageId : topic.getMemberPassageIds()) {
            sb.append(project.passages().get(passageId).getText()).append(' ');
        }
        return sb.toString();
    }

    private static Passage passageContaining(ResearchProject project, String needle) {
        for (Passage passage : project.passages().values()) {
            if (passage.getText().contains(needle)) {
                return passage;
            }
        }
        throw new AssertionError("no passage contains '" + needle + "'");
    }
}
