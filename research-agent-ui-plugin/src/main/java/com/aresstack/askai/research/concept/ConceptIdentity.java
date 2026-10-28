package com.aresstack.askai.research.concept;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ID sidecar's in-memory model (ratified design V3): a POSITION tree mirroring the concept's
 * card structure — same nesting, same order, but only UUIDs, never names. A rename therefore
 * never touches identity (invariant 1), and the mapping ID↔card is always exact or the sidecar
 * is invalid — there is no heuristic reconstruction anywhere in this class.
 *
 * <p>Terminology from the design: the IDENTITY CORE is {@code (epoch, nodes)} — the semantic
 * state no-op checks compare; {@code revision} and {@code documentHash} are STAMPS applied only
 * at commit time ({@link #stampedJson}). A card is a container property whose value is a JSON
 * ARRAY (the same structural rule the tree layer and the topic scanner use); sealed value
 * properties carry no identity, and {@code title}/{@code subtitle} never appear here.</p>
 */
public final class ConceptIdentity {

    public static final int FORMAT_VERSION = 1;

    /** One identity node: a UUID and the child cards' nodes, in document order. */
    public static final class Node {
        final String id;
        final List<Node> children;

        Node(String id, List<Node> children) {
            this.id = id;
            this.children = children;
        }
    }

    private final String epoch;
    private final List<Node> nodes;
    /** Stamps as read from a persisted sidecar; -1/null on freshly built cores. */
    private final long revision;
    private final String documentHash;

    private ConceptIdentity(String epoch, List<Node> nodes, long revision, String documentHash) {
        this.epoch = epoch;
        this.nodes = nodes;
        this.revision = revision;
        this.documentHash = documentHash;
    }

    public String epoch() {
        return epoch;
    }

    public long stampedRevision() {
        return revision;
    }

    public String stampedDocumentHash() {
        return documentHash;
    }

    // ------------------------------------------------------------------ minting & parsing

    /** A brand-new epoch over {@code documentRoot}: fresh UUIDs for every card, recursively. */
    public static ConceptIdentity mintFor(JsonElement documentRoot) {
        return new ConceptIdentity("e-" + UUID.randomUUID(),
                mintNodes(cardChildren(conceptArrayOf(documentRoot))), -1L, null);
    }

    private static List<Node> mintNodes(List<JsonArray> cardValues) {
        List<Node> minted = new ArrayList<Node>();
        for (JsonArray value : cardValues) {
            minted.add(new Node(UUID.randomUUID().toString(), mintNodes(cardChildren(value))));
        }
        return minted;
    }

    /**
     * Parse a persisted sidecar. Returns {@code null} on ANY malformation (unknown format
     * version, missing fields, wrong types) — the caller escalates through the staged recovery,
     * it never repairs creatively.
     */
    public static ConceptIdentity parse(String sidecarJson) {
        try {
            JsonElement root = com.google.gson.JsonParser.parseString(sidecarJson);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject sidecar = root.getAsJsonObject();
            if (sidecar.get("formatVersion") == null
                    || sidecar.get("formatVersion").getAsInt() != FORMAT_VERSION
                    || sidecar.get("epoch") == null || sidecar.get("nodes") == null
                    || !sidecar.get("nodes").isJsonArray()) {
                return null;
            }
            List<Node> nodes = parseNodes(sidecar.get("nodes").getAsJsonArray());
            if (nodes == null) {
                return null;
            }
            long revision = sidecar.get("revision") == null
                    ? -1L : sidecar.get("revision").getAsLong();
            String documentHash = sidecar.get("documentHash") == null
                    ? null : sidecar.get("documentHash").getAsString();
            return new ConceptIdentity(sidecar.get("epoch").getAsString(), nodes,
                    revision, documentHash);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static List<Node> parseNodes(JsonArray array) {
        List<Node> nodes = new ArrayList<Node>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject node = element.getAsJsonObject();
            if (node.get("i") == null || node.get("c") == null
                    || !node.get("c").isJsonArray()) {
                return null;
            }
            List<Node> children = parseNodes(node.get("c").getAsJsonArray());
            if (children == null) {
                return null;
            }
            nodes.add(new Node(node.get("i").getAsString(), children));
        }
        return nodes;
    }

    // ------------------------------------------------------------------ serialization

    /** The canonical CORE serialization — the input of core-equality/no-op hashing. */
    public String coreJson() {
        StringBuilder sb = new StringBuilder("{\"formatVersion\":").append(FORMAT_VERSION)
                .append(",\"epoch\":\"").append(epoch).append("\",\"nodes\":");
        appendNodes(sb, nodes);
        return sb.append('}').toString();
    }

    /** The full persisted sidecar: core plus the commit-time stamps. */
    public String stampedJson(long stampRevision, String stampDocumentHash) {
        StringBuilder sb = new StringBuilder("{\"formatVersion\":").append(FORMAT_VERSION)
                .append(",\"epoch\":\"").append(epoch)
                .append("\",\"revision\":").append(stampRevision)
                .append(",\"documentHash\":\"").append(stampDocumentHash)
                .append("\",\"nodes\":");
        appendNodes(sb, nodes);
        return sb.append('}').toString();
    }

    private static void appendNodes(StringBuilder sb, List<Node> nodes) {
        sb.append('[');
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"i\":\"").append(nodes.get(i).id).append("\",\"c\":");
            appendNodes(sb, nodes.get(i).children);
            sb.append('}');
        }
        sb.append(']');
    }

    // ------------------------------------------------------------------ validation & lookups

    /** Whether this identity's shape matches the document's card tree exactly (arity check). */
    public boolean matchesShape(JsonElement documentRoot) {
        return shapeMatches(nodes, cardChildren(conceptArrayOf(documentRoot)));
    }

    private static boolean shapeMatches(List<Node> nodes, List<JsonArray> cards) {
        if (nodes.size() != cards.size()) {
            return false;
        }
        for (int i = 0; i < nodes.size(); i++) {
            if (!shapeMatches(nodes.get(i).children, cardChildren(cards.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /** The UUID of the card at {@code names} in {@code documentRoot}, or {@code null}. */
    public String idAtPath(JsonElement documentRoot, List<String> names) {
        List<Integer> ordinals = ordinalPath(documentRoot, names);
        if (ordinals == null) {
            return null;
        }
        Node node = nodeAt(ordinals);
        return node == null ? null : node.id;
    }

    /** Every node id of this identity, document order (the novelty window's raw material). */
    public List<String> allIds() {
        List<String> ids = new ArrayList<String>();
        collectIds(nodes, ids);
        return ids;
    }

    private static void collectIds(List<Node> nodes, List<String> ids) {
        for (Node node : nodes) {
            ids.add(node.id);
            collectIds(node.children, ids);
        }
    }

    /** The current name path of the card carrying {@code id}, or {@code null} when gone. */
    public List<String> pathOfId(JsonElement documentRoot, String id) {
        return findPath(nodes, cardEntries(conceptArrayOf(documentRoot)), id,
                new ArrayList<String>());
    }

    private static List<String> findPath(List<Node> nodes, List<Map.Entry<String, JsonArray>> cards,
                                         String id, List<String> prefix) {
        for (int i = 0; i < nodes.size() && i < cards.size(); i++) {
            List<String> path = new ArrayList<String>(prefix);
            path.add(cards.get(i).getKey());
            if (nodes.get(i).id.equals(id)) {
                return path;
            }
            List<String> deeper = findPath(nodes.get(i).children,
                    cardEntries(cards.get(i).getValue()), id, path);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private Node nodeAt(List<Integer> ordinals) {
        List<Node> level = nodes;
        Node current = null;
        for (int ordinal : ordinals) {
            if (ordinal < 0 || ordinal >= level.size()) {
                return null;
            }
            current = level.get(ordinal);
            level = current.children;
        }
        return current;
    }

    // ------------------------------------------------------------------ per-operation mutations
    //
    // Each mutation returns a NEW identity core in the SAME epoch. The caller supplies the
    // document state the ordinal computation needs (pre- or post-image, as documented) — the
    // mapping is exact per operation, never a diff heuristic.

    /** After an ADD: {@code newDocumentRoot} already contains the card at {@code cardPath}. */
    public ConceptIdentity afterAdd(JsonElement newDocumentRoot, List<String> cardPath) {
        List<Integer> ordinals = ordinalPath(newDocumentRoot, cardPath);
        if (ordinals == null) {
            throw new IllegalStateException("added card not found for identity: " + cardPath);
        }
        List<Node> copy = deepCopy(nodes);
        List<Node> level = levelOf(copy, ordinals);
        level.add(ordinals.get(ordinals.size() - 1),
                new Node(UUID.randomUUID().toString(), new ArrayList<Node>()));
        return new ConceptIdentity(epoch, copy, -1L, null);
    }

    /** After a REMOVE: ordinals come from the PRE-image document (the card is gone now). */
    public ConceptIdentity afterRemove(JsonElement oldDocumentRoot, List<String> cardPath) {
        List<Integer> ordinals = ordinalPath(oldDocumentRoot, cardPath);
        if (ordinals == null) {
            throw new IllegalStateException("removed card not found for identity: " + cardPath);
        }
        List<Node> copy = deepCopy(nodes);
        levelOf(copy, ordinals).remove((int) ordinals.get(ordinals.size() - 1));
        return new ConceptIdentity(epoch, copy, -1L, null);
    }

    /**
     * After a terminal-branch REWRITE: the branch's children become exactly {@code leafCount}
     * fresh nodes (replaced leaves are NEW content — old leaf IDs die with their cards).
     * Ordinals come from the PRE-image document (the branch itself did not move).
     */
    public ConceptIdentity afterRewrite(JsonElement oldDocumentRoot, List<String> branchPath,
                                        int leafCount) {
        List<Integer> ordinals = ordinalPath(oldDocumentRoot, branchPath);
        if (ordinals == null) {
            throw new IllegalStateException("rewritten branch not found for identity: "
                    + branchPath);
        }
        List<Node> copy = deepCopy(nodes);
        Node branch = null;
        List<Node> level = copy;
        for (int ordinal : ordinals) {
            branch = level.get(ordinal);
            level = branch.children;
        }
        branch.children.clear();
        for (int i = 0; i < leafCount; i++) {
            branch.children.add(new Node(UUID.randomUUID().toString(), new ArrayList<Node>()));
        }
        return new ConceptIdentity(epoch, copy, -1L, null);
    }

    /**
     * After a LEAF move (move_leaf slice): the node — WITH ITS UUID — leaves its old ordinal
     * slot and lands at the moved card's ordinal in the post-image document. Invariant 1 made
     * mechanical: a move relocates the identity node, it never re-mints. The leaf has no
     * children, so removal only shifts the SOURCE level; every ordinal of the post-image walk
     * matches the intermediate copy except the target level, where the insert index fills the
     * moved card's final position.
     */
    public ConceptIdentity afterMoveLeaf(JsonElement oldDocumentRoot, List<String> oldPath,
                                         JsonElement newDocumentRoot, List<String> newPath) {
        List<Integer> from = ordinalPath(oldDocumentRoot, oldPath);
        List<Integer> to = ordinalPath(newDocumentRoot, newPath);
        if (from == null || to == null) {
            throw new IllegalStateException("moved leaf not found for identity: " + oldPath
                    + " -> " + newPath);
        }
        List<Node> copy = deepCopy(nodes);
        Node moved = levelOf(copy, from).remove((int) from.get(from.size() - 1));
        levelOf(copy, to).add(to.get(to.size() - 1), moved);
        return new ConceptIdentity(epoch, copy, -1L, null);
    }

    private static List<Node> deepCopy(List<Node> nodes) {
        List<Node> copy = new ArrayList<Node>();
        for (Node node : nodes) {
            copy.add(new Node(node.id, deepCopy(node.children)));
        }
        return copy;
    }

    /** The sibling list holding the LAST ordinal's slot (parent level of the target). */
    private static List<Node> levelOf(List<Node> root, List<Integer> ordinals) {
        List<Node> level = root;
        for (int i = 0; i < ordinals.size() - 1; i++) {
            level = level.get(ordinals.get(i)).children;
        }
        return level;
    }

    // ------------------------------------------------------------------ document card walking

    /** Name path → per-level card ordinals in {@code documentRoot}, or {@code null}. */
    private static List<Integer> ordinalPath(JsonElement documentRoot, List<String> names) {
        List<Integer> ordinals = new ArrayList<Integer>();
        JsonArray level = conceptArrayOf(documentRoot);
        for (String name : names) {
            List<Map.Entry<String, JsonArray>> cards = cardEntries(level);
            int found = -1;
            for (int i = 0; i < cards.size(); i++) {
                if (cards.get(i).getKey().equals(name)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return null;
            }
            ordinals.add(found);
            level = cards.get(found).getValue();
        }
        return ordinals.isEmpty() ? null : ordinals;
    }

    private static JsonArray conceptArrayOf(JsonElement documentRoot) {
        if (documentRoot != null && documentRoot.isJsonObject()) {
            JsonElement concept = documentRoot.getAsJsonObject().get("concept");
            if (concept != null && concept.isJsonArray()) {
                return concept.getAsJsonArray();
            }
        }
        return new JsonArray();
    }

    /** The card values of one level, in document order (array-valued container properties). */
    private static List<JsonArray> cardChildren(JsonArray level) {
        List<JsonArray> cards = new ArrayList<JsonArray>();
        for (Map.Entry<String, JsonArray> entry : cardEntries(level)) {
            cards.add(entry.getValue());
        }
        return cards;
    }

    private static List<Map.Entry<String, JsonArray>> cardEntries(JsonArray level) {
        List<Map.Entry<String, JsonArray>> cards =
                new ArrayList<Map.Entry<String, JsonArray>>();
        for (JsonElement element : level) {
            if (!element.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonArray()) {
                    cards.add(new java.util.AbstractMap.SimpleImmutableEntry<String, JsonArray>(
                            entry.getKey(), entry.getValue().getAsJsonArray()));
                }
            }
        }
        return cards;
    }
}
