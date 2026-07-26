package com.aresstack.askai.research.domain;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** Outline tree invariants and editing operations. */
public class ResearchOutlineTest {

    @Test
    public void addsTopLevelAndChildWithAssignedOrder() {
        ResearchOutline outline = ResearchOutline.empty()
                .addSection("", "a", "Intro")
                .addSection("", "b", "Body")
                .addSection("a", "a1", "Background");

        assertEquals(2, outline.childrenOf("").size());
        assertEquals(1, outline.childrenOf("a").size());
        assertEquals(0, outline.childrenOf("").get(0).getOrder());
        assertEquals(1, outline.childrenOf("").get(1).getOrder());
        assertEquals(3L, outline.getRevision());
    }

    @Test
    public void rejectsAddingUnderAMissingParent() {
        try {
            ResearchOutline.empty().addSection("nope", "x", "X");
            fail("missing parent must throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void renameAndReorderSiblings() {
        ResearchOutline outline = ResearchOutline.empty()
                .addSection("", "a", "A").addSection("", "b", "B").addSection("", "c", "C");
        outline = outline.renameSection("b", "B renamed");
        assertEquals("B renamed", outline.section("b").getTitle());

        // Move c up: order of b and c swap.
        ResearchOutline moved = outline.reorderSection("c", -1);
        List<ResearchSection> top = moved.childrenOf("");
        assertEquals("a", top.get(0).getId());
        assertEquals("c", top.get(1).getId());
        assertEquals("b", top.get(2).getId());

        // Moving the first up is a no-op (no revision change).
        long rev = moved.getRevision();
        assertEquals(rev, moved.reorderSection("a", -1).getRevision());
    }

    @Test
    public void removeCascadeDropsTheSubtree() {
        ResearchOutline outline = ResearchOutline.empty()
                .addSection("", "a", "A").addSection("a", "a1", "A1").addSection("a1", "a11", "A11");
        ResearchOutline pruned = outline.removeSection("a", ResearchOutline.ChildStrategy.CASCADE);
        assertNull(pruned.section("a"));
        assertNull(pruned.section("a1"));
        assertNull(pruned.section("a11"));
        assertEquals(0, pruned.getSections().size());
    }

    @Test
    public void removePromoteReparentsChildren() {
        ResearchOutline outline = ResearchOutline.empty()
                .addSection("", "a", "A").addSection("a", "a1", "A1");
        ResearchOutline promoted = outline.removeSection("a", ResearchOutline.ChildStrategy.PROMOTE);
        assertNull(promoted.section("a"));
        assertEquals("", promoted.section("a1").getParentId());
    }

    @Test
    public void constructorRejectsDuplicateMissingParentAndCycles() {
        try {
            new ResearchOutline(Arrays.asList(
                    section("a", ""), section("a", "")), 0L);
            fail("duplicate id must throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new ResearchOutline(Arrays.asList(section("x", "missing")), 0L);
            fail("missing parent must throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            // a -> b -> a cycle
            new ResearchOutline(new ArrayList<ResearchSection>(Arrays.asList(
                    section("a", "b"), section("b", "a"))), 0L);
            fail("cycle must throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static ResearchSection section(String id, String parentId) {
        return new ResearchSection(id, parentId, id, 0, ResearchSectionStatus.NOT_STARTED, 0, 0, 0, 0L);
    }
}
