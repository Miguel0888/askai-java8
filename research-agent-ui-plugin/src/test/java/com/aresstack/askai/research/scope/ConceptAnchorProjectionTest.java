package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeFacet;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Zielbild slice 1: the fence's IN posts come from the EFFECTIVE mindmap — stored concept minus
 * exact blacklist matches, subtree-deep. Suppression is exact by design: semantic neighbourhood
 * must never silently widen the negative boundary.
 */
public class ConceptAnchorProjectionTest {

    private static final String DOCUMENT = "{\"title\":\"\",\"subtitle\":\"\",\"concept\":["
            + "{\"RTOS-Grundlagen\":[{\"Arduino\":[],\"ESP-IDF\":[{\"Task Notifications\":[]}]}],"
            + "\"Praxis\":[]}]}";

    @Test
    public void mindmapCardsBecomeInPostsAndSuppressedSubtreesStayOut() {
        ResearchScopeDraft draft = ResearchScopeDraft.builder()
                .putFacet(new ScopeFacet("esp-idf", "ESP-IDF", ScopeFacet.Status.EXCLUDED,
                        "explicit user exclusion"))
                .build();

        List<ScopeAnchor> anchors = ConceptAnchorProjection.anchorsOf(DOCUMENT, draft);

        List<String> texts = new ArrayList<String>();
        for (ScopeAnchor anchor : anchors) {
            assertEquals(ScopeAnchor.Membership.IN, anchor.getMembership());
            assertTrue(anchor.getAnchorId().startsWith("anchor-concept-"));
            texts.add(anchor.getSemanticText());
        }
        // ESP-IDF is blacklisted: neither the card NOR its child becomes an IN post — the
        // suppression is functional immediately, even though the stored mindmap still has both.
        assertEquals(java.util.Arrays.asList("RTOS-Grundlagen", "Arduino", "Praxis"), texts);
    }

    @Test
    public void plainExclusionStringsSuppressExactlyLikeExcludedFacets() {
        ResearchScopeDraft draft = ResearchScopeDraft.builder().addExclusion("arduino").build();
        List<ScopeAnchor> anchors = ConceptAnchorProjection.anchorsOf(DOCUMENT, draft);
        for (ScopeAnchor anchor : anchors) {
            assertTrue("case-insensitive exact match rules the card out",
                    !anchor.getSemanticText().equals("Arduino"));
        }
        assertEquals(4, anchors.size()); // ESP-IDF stays IN here — nothing excluded it
    }

    @Test
    public void idsAreStableAcrossRunsAndBrokenInputYieldsNothing() {
        ResearchScopeDraft draft = ResearchScopeDraft.empty();
        List<ScopeAnchor> first = ConceptAnchorProjection.anchorsOf(DOCUMENT, draft);
        List<ScopeAnchor> second = ConceptAnchorProjection.anchorsOf(DOCUMENT, draft);
        assertEquals("the vector cache keys on stable ids",
                first.get(2).getAnchorId(), second.get(2).getAnchorId());
        assertEquals(0, ConceptAnchorProjection.anchorsOf("not json", draft).size());
        assertEquals(0, ConceptAnchorProjection.anchorsOf(null, draft).size());
    }
}
