package com.aresstack.askai.research.runtime.team;

import java.util.Map;

/**
 * The model's ONE concept step for one inference — a tiny atomic operation addressed by a
 * human-readable name path (K2c, the MainframeMate lesson): {@code read} a branch, {@code add}
 * one new card, {@code remove} one card, or {@code none}. Deliberately NO handles, NO revisions,
 * NO branch payloads in the model contract — all transactional machinery lives in the host.
 * A {@code read} is a regular working step, not a repair; the budgets are counted separately
 * in {@link ConceptToolRounds}.
 */
public final class ConceptAction {

    public enum Type { READ, ADD, REMOVE }

    private final Type type;
    private final String path;
    private final String parentPath;
    private final String name;

    private ConceptAction(Type type, String path, String parentPath, String name) {
        this.type = type;
        this.path = path == null ? "" : path.trim();
        this.parentPath = parentPath == null ? "" : parentPath.trim();
        this.name = name == null ? "" : name.trim();
    }

    public Type getType() {
        return type;
    }

    /** For READ/REMOVE: the '/'-separated name path (READ: empty = whole concept). */
    public String getPath() {
        return path;
    }

    /** For ADD: the parent card's name path (empty = a new top-level card). */
    public String getParentPath() {
        return parentPath;
    }

    /** For ADD: the new card's name. */
    public String getName() {
        return name;
    }

    /** A compact trace label ("read path='A/B'", "add parent='A' name='B'", "remove path='A'"). */
    public String describe() {
        switch (type) {
            case READ:
                return "read path='" + path + "'";
            case ADD:
                return "add parent='" + parentPath + "' name='" + name + "'";
            default:
                return "remove path='" + path + "'";
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
     * Parse the optional {@code conceptAction} value of a scoping answer. Absent, {@code null}
     * or an explicit {@code type:"none"} → absent (the turn touches nothing). A present but
     * malformed action is NOT silently dropped — the reason travels back to the model as a
     * rejection with a concrete example.
     */
    @SuppressWarnings("unchecked")
    public static Parsed parse(Object value) {
        if (value == null) {
            return Parsed.absent();
        }
        if (!(value instanceof Map)) {
            return Parsed.invalid("conceptAction must be a JSON object with a \"type\" field "
                    + "(none, read, add or remove)");
        }
        Map<String, Object> map = (Map<String, Object>) value;
        String type = asString(map.get("type"));
        if (type == null || "none".equalsIgnoreCase(type.trim())) {
            return Parsed.absent();
        }
        if ("read".equalsIgnoreCase(type)) {
            return Parsed.ok(new ConceptAction(Type.READ,
                    firstString(map, "path", "parent_path"), null, null));
        }
        if ("add".equalsIgnoreCase(type)) {
            String name = asString(map.get("name"));
            if (name == null || name.trim().isEmpty()) {
                return Parsed.invalid("conceptAction type \"add\" requires \"name\" — example: "
                        + "{\"type\":\"add\",\"parent_path\":\"FreeRTOS\","
                        + "\"name\":\"Synchronisation\"}");
            }
            return Parsed.ok(new ConceptAction(Type.ADD, null,
                    firstString(map, "parent_path", "parentPath", "path"), name));
        }
        if ("remove".equalsIgnoreCase(type)) {
            String path = firstString(map, "path", "parent_path");
            if (path == null || path.trim().isEmpty()) {
                return Parsed.invalid("conceptAction type \"remove\" requires \"path\" — "
                        + "example: {\"type\":\"remove\",\"path\":\"FreeRTOS/Praxis/ESP-IDF\"}");
            }
            return Parsed.ok(new ConceptAction(Type.REMOVE, path, null, null));
        }
        return Parsed.invalid("conceptAction has unknown type \"" + type
                + "\" — allowed: none, read, add, remove");
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = asString(map.get(key));
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
