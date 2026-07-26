package com.aresstack.askai.research.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable outline tree with structural invariants enforced at construction: every {@code parentId} is
 * empty or refers to an existing section, and the parent chain is acyclic. Editing operations return a new
 * outline with the revision advanced by one; illegal edits throw {@link IllegalArgumentException} (a
 * programming error, distinct from the state machine's soft rejections).
 */
public final class ResearchOutline {

    /** What to do with a removed section's children. */
    public enum ChildStrategy {
        /** Remove the whole subtree. */
        CASCADE,
        /** Re-parent the children to the removed section's parent. */
        PROMOTE
    }

    private final List<ResearchSection> sections;
    private final long revision;

    public ResearchOutline(List<ResearchSection> sections, long revision) {
        List<ResearchSection> copy = new ArrayList<ResearchSection>(sections == null
                ? Collections.<ResearchSection>emptyList() : sections);
        validate(copy);
        copy.sort(orderComparator());
        this.sections = Collections.unmodifiableList(copy);
        this.revision = revision;
    }

    public static ResearchOutline empty() {
        return new ResearchOutline(Collections.<ResearchSection>emptyList(), 0L);
    }

    public List<ResearchSection> getSections() {
        return sections;
    }

    public long getRevision() {
        return revision;
    }

    public ResearchSection section(String id) {
        for (ResearchSection s : sections) {
            if (s.getId().equals(id)) {
                return s;
            }
        }
        return null;
    }

    public List<ResearchSection> childrenOf(String parentId) {
        List<ResearchSection> children = new ArrayList<ResearchSection>();
        String key = parentId == null ? "" : parentId;
        for (ResearchSection s : sections) {
            if (s.getParentId().equals(key)) {
                children.add(s);
            }
        }
        children.sort(orderComparator());
        return children;
    }

    public ResearchOutline addSection(String parentId, String newId, String title) {
        String parent = parentId == null ? "" : parentId;
        if (!parent.isEmpty() && section(parent) == null) {
            throw new IllegalArgumentException("parent section does not exist: " + parent);
        }
        if (section(newId) != null) {
            throw new IllegalArgumentException("section id already exists: " + newId);
        }
        long next = revision + 1;
        int order = childrenOf(parent).size();
        List<ResearchSection> updated = new ArrayList<ResearchSection>(sections);
        updated.add(new ResearchSection(newId, parent, title, order, ResearchSectionStatus.NOT_STARTED,
                0, 0, 0, next));
        return new ResearchOutline(updated, next);
    }

    public ResearchOutline renameSection(String id, String title) {
        requireExisting(id);
        long next = revision + 1;
        List<ResearchSection> updated = new ArrayList<ResearchSection>();
        for (ResearchSection s : sections) {
            updated.add(s.getId().equals(id) ? s.withTitle(title, next) : s);
        }
        return new ResearchOutline(updated, next);
    }

    /** Moves the section up ({@code delta < 0}) or down ({@code delta > 0}) among its siblings. */
    public ResearchOutline reorderSection(String id, int delta) {
        ResearchSection target = requireExisting(id);
        List<ResearchSection> siblings = childrenOf(target.getParentId());
        int index = indexOfId(siblings, id);
        int swapWith = index + (delta < 0 ? -1 : 1);
        if (swapWith < 0 || swapWith >= siblings.size()) {
            return this; // already at the edge; no-op, no revision change
        }
        long next = revision + 1;
        ResearchSection a = siblings.get(index);
        ResearchSection b = siblings.get(swapWith);
        Map<String, ResearchSection> replacements = new LinkedHashMap<String, ResearchSection>();
        replacements.put(a.getId(), a.withOrder(b.getOrder(), next));
        replacements.put(b.getId(), b.withOrder(a.getOrder(), next));
        List<ResearchSection> updated = new ArrayList<ResearchSection>();
        for (ResearchSection s : sections) {
            updated.add(replacements.containsKey(s.getId()) ? replacements.get(s.getId()) : s);
        }
        return new ResearchOutline(updated, next);
    }

    public ResearchOutline removeSection(String id, ChildStrategy strategy) {
        ResearchSection target = requireExisting(id);
        if (strategy == null) {
            throw new IllegalArgumentException("a child strategy is required to remove a section");
        }
        long next = revision + 1;
        Set<String> toRemove = new HashSet<String>();
        toRemove.add(id);
        if (strategy == ChildStrategy.CASCADE) {
            collectDescendants(id, toRemove);
        }
        List<ResearchSection> updated = new ArrayList<ResearchSection>();
        for (ResearchSection s : sections) {
            if (toRemove.contains(s.getId())) {
                continue;
            }
            if (strategy == ChildStrategy.PROMOTE && s.getParentId().equals(id)) {
                updated.add(new ResearchSection(s.getId(), target.getParentId(), s.getTitle(), s.getOrder(),
                        s.getStatus(), s.getSourceCount(), s.getFindingCount(), s.getWarningCount(), next));
            } else {
                updated.add(s);
            }
        }
        return new ResearchOutline(updated, next);
    }

    // ------------------------------------------------------------------ helpers

    private void collectDescendants(String parentId, Set<String> into) {
        for (ResearchSection s : sections) {
            if (s.getParentId().equals(parentId) && into.add(s.getId())) {
                collectDescendants(s.getId(), into);
            }
        }
    }

    private ResearchSection requireExisting(String id) {
        ResearchSection s = section(id);
        if (s == null) {
            throw new IllegalArgumentException("section does not exist: " + id);
        }
        return s;
    }

    private static int indexOfId(List<ResearchSection> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static Comparator<ResearchSection> orderComparator() {
        return new Comparator<ResearchSection>() {
            public int compare(ResearchSection a, ResearchSection b) {
                if (!a.getParentId().equals(b.getParentId())) {
                    return a.getParentId().compareTo(b.getParentId());
                }
                return Integer.compare(a.getOrder(), b.getOrder());
            }
        };
    }

    private static void validate(List<ResearchSection> sections) {
        Set<String> ids = new HashSet<String>();
        for (ResearchSection s : sections) {
            if (!ids.add(s.getId())) {
                throw new IllegalArgumentException("duplicate section id: " + s.getId());
            }
        }
        Map<String, String> parentById = new LinkedHashMap<String, String>();
        for (ResearchSection s : sections) {
            String parent = s.getParentId();
            if (!parent.isEmpty() && !ids.contains(parent)) {
                throw new IllegalArgumentException("section " + s.getId() + " has a missing parent: " + parent);
            }
            parentById.put(s.getId(), parent);
        }
        // Acyclic parent chains.
        for (String id : parentById.keySet()) {
            Set<String> seen = new HashSet<String>();
            String cursor = id;
            while (cursor != null && !cursor.isEmpty()) {
                if (!seen.add(cursor)) {
                    throw new IllegalArgumentException("outline has a cycle at section: " + id);
                }
                cursor = parentById.get(cursor);
            }
        }
    }
}
