package com.aresstack.askai.research.runtime.team;

import java.util.Map;

/**
 * The model's ONE concept tool step for one inference — an OPERATION, never a whole tree. Exactly
 * one action per inference by design: the runtime owns the loop, the model only ever decides its
 * NEXT step ("read this branch", "apply this refined branch", "remove this node", or nothing).
 * A {@code read} is a regular working step, not a repair — the distinction lives in
 * {@link ConceptToolRounds}, which counts tool rounds and repair attempts separately.
 */
public final class ConceptAction {

    public enum Type { READ, UPDATE, REMOVE }

    private final Type type;
    private final String path;
    private final int depth;
    private final String handle;
    private final String branchJson;
    private final boolean allowRemovals;

    private ConceptAction(Type type, String path, int depth, String handle, String branchJson,
                          boolean allowRemovals) {
        this.type = type;
        this.path = path == null ? "" : path.trim();
        this.depth = depth;
        this.handle = handle == null ? "" : handle.trim();
        this.branchJson = branchJson == null ? "" : branchJson;
        this.allowRemovals = allowRemovals;
    }

    public Type getType() {
        return type;
    }

    /** For READ: the '/'-separated node-name path (empty = whole concept). */
    public String getPath() {
        return path;
    }

    /** For READ: the depth limit (0 = full depth, editable handle). */
    public int getDepth() {
        return depth;
    }

    /** For UPDATE/REMOVE: the branch handle from a previous read. */
    public String getHandle() {
        return handle;
    }

    /** For UPDATE: the complete refined branch ({@code {"Name": [ ... ]}}). */
    public String getBranchJson() {
        return branchJson;
    }

    public boolean isAllowRemovals() {
        return allowRemovals;
    }

    /** A compact trace label ("read path='A/B' depth=1", "update handle=b-3"). */
    public String describe() {
        switch (type) {
            case READ:
                return "read path='" + path + "'" + (depth > 0 ? " depth=" + depth : "");
            case UPDATE:
                return "update handle=" + handle + (allowRemovals ? " allowRemovals" : "");
            default:
                return "remove handle=" + handle;
        }
    }

    // ------------------------------------------------------------------ parsing

    /** Either a valid action, or the reason it is invalid (fed back to the model, never dropped). */
    public static final class Parsed {
        private final ConceptAction action;
        private final String error;

        private Parsed(ConceptAction action, String error) {
            this.action = action;
            this.error = error;
        }

        public boolean isPresent() {
            return action != null || error != null;
        }

        public ConceptAction getAction() {
            return action;
        }

        public String getError() {
            return error;
        }

        static Parsed absent() {
            return new Parsed(null, null);
        }

        static Parsed ok(ConceptAction action) {
            return new Parsed(action, null);
        }

        static Parsed invalid(String error) {
            return new Parsed(null, error);
        }
    }

    /**
     * Parse the optional {@code conceptAction} value of a scoping answer. Absent/null → absent.
     * A present but malformed action is NOT silently dropped — the reason travels back to the
     * model as a rejection so it can correct itself (the same philosophy as every diagnostic
     * in this pipeline).
     */
    @SuppressWarnings("unchecked")
    public static Parsed parse(Object value) {
        if (value == null) {
            return Parsed.absent();
        }
        if (!(value instanceof Map)) {
            return Parsed.invalid("conceptAction must be a JSON object with a \"type\" field");
        }
        Map<String, Object> map = (Map<String, Object>) value;
        String type = asString(map.get("type"));
        if ("read".equalsIgnoreCase(type)) {
            return Parsed.ok(new ConceptAction(Type.READ, asString(map.get("path")),
                    asInt(map.get("depth")), null, null, false));
        }
        if ("update".equalsIgnoreCase(type)) {
            String handle = asString(map.get("handle"));
            Object branch = map.get("branchJson");
            // The branch may arrive as an embedded JSON object (preferred) or as a string; both
            // are re-serialized/passed through — the HOST's strict parser is the authority.
            String branchJson = branch instanceof String ? (String) branch
                    : branch instanceof Map ? MiniJsonWriter.write(branch) : null;
            if (handle == null || handle.trim().isEmpty()) {
                return Parsed.invalid("conceptAction type \"update\" requires \"handle\" "
                        + "(from a previous concept read)");
            }
            if (branchJson == null || branchJson.trim().isEmpty()) {
                return Parsed.invalid("conceptAction type \"update\" requires \"branchJson\" — "
                        + "the complete refined branch as {\"Name\": [ ... ]}");
            }
            return Parsed.ok(new ConceptAction(Type.UPDATE, null, 0, handle, branchJson,
                    Boolean.TRUE.equals(map.get("allowRemovals"))
                            || "true".equalsIgnoreCase(asString(map.get("allowRemovals")))));
        }
        if ("remove".equalsIgnoreCase(type)) {
            String handle = asString(map.get("handle"));
            if (handle == null || handle.trim().isEmpty()) {
                return Parsed.invalid("conceptAction type \"remove\" requires \"handle\"");
            }
            return Parsed.ok(new ConceptAction(Type.REMOVE, null, 0, handle, null, false));
        }
        return Parsed.invalid("conceptAction has unknown type \"" + type
                + "\" — allowed: read, update, remove");
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static int asInt(Object value) {
        if (value instanceof Number) {
            return (int) Math.round(((Number) value).doubleValue());
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException notANumber) {
                return 0;
            }
        }
        return 0;
    }
}
