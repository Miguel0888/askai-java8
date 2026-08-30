package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The model's ONE concept step for one inference — a tiny atomic operation addressed by
 * UNAMBIGUOUS PATH SEGMENTS (K2c hardening): {@code path}/{@code parent} are ARRAYS of card
 * names, never slash-joined strings. The gate showed why: a model that misses a parent turns
 * "FreeRTOS/ESP32/Grundlagen" into a literal root card name, and real-world names (TCP/IP,
 * Client/Server, C/C++) make '/' unusable as an implicit separator. A plain string is accepted
 * as exactly ONE segment — it is never split.
 */
public final class ConceptAction {

    public enum Type { READ, ADD, REMOVE }

    private final Type type;
    private final List<String> path;
    private final List<String> parent;
    private final String name;

    private ConceptAction(Type type, List<String> path, List<String> parent, String name) {
        this.type = type;
        this.path = path == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(path);
        this.parent = parent == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(parent);
        this.name = name == null ? "" : name.trim();
    }

    public Type getType() {
        return type;
    }

    /** For READ/REMOVE: the card-name segments from the concept root (READ: empty = all). */
    public List<String> getPath() {
        return path;
    }

    /** For ADD: the parent card's segments (empty = a new top-level card). */
    public List<String> getParent() {
        return parent;
    }

    /** For ADD: the new card's name — ONE label, never a path. */
    public String getName() {
        return name;
    }

    /** A compact trace label ('add parent=["A","B"] name="C"'). */
    public String describe() {
        switch (type) {
            case READ:
                return "read path=" + segmentsLabel(path);
            case ADD:
                return "add parent=" + segmentsLabel(parent) + " name=\"" + name + "\"";
            default:
                return "remove path=" + segmentsLabel(path);
        }
    }

    private static String segmentsLabel(List<String> segments) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < segments.size(); i++) {
            sb.append(i > 0 ? "," : "").append('"').append(segments.get(i)).append('"');
        }
        return sb.append(']').toString();
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
     * Parse the optional {@code conceptAction} value. Absent, {@code null} or an explicit
     * {@code type:"none"} → absent. A malformed action carries its reason back to the model with
     * a concrete example. Segment lists accept a JSON array of strings; a bare string counts as
     * ONE segment (never split on '/').
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
                    segments(map.get("path"), map.get("parent")), null, null));
        }
        if ("add".equalsIgnoreCase(type)) {
            String name = asString(map.get("name"));
            if (name == null || name.trim().isEmpty()) {
                return Parsed.invalid("conceptAction type \"add\" requires \"name\" — example: "
                        + "{\"type\":\"add\",\"parent\":[\"FreeRTOS\"],"
                        + "\"name\":\"Synchronisation\"}");
            }
            return Parsed.ok(new ConceptAction(Type.ADD, null,
                    segments(map.get("parent"), map.get("parent_path"), map.get("path")), name));
        }
        if ("remove".equalsIgnoreCase(type)) {
            List<String> path = segments(map.get("path"), map.get("parent"));
            if (path.isEmpty()) {
                return Parsed.invalid("conceptAction type \"remove\" requires \"path\" — "
                        + "example: {\"type\":\"remove\",\"path\":[\"FreeRTOS\",\"Praxis\","
                        + "\"ESP-IDF\"]}");
            }
            return Parsed.ok(new ConceptAction(Type.REMOVE, path, null, null));
        }
        return Parsed.invalid("conceptAction has unknown type \"" + type
                + "\" — allowed: none, read, add, remove");
    }

    /** First present value wins; array of strings verbatim, a bare string = ONE segment. */
    private static List<String> segments(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate instanceof List) {
                List<String> out = new ArrayList<String>();
                for (Object element : (List<Object>) candidate) {
                    if (element instanceof String && !((String) element).trim().isEmpty()) {
                        out.add(((String) element).trim());
                    }
                }
                return out;
            }
            if (candidate instanceof String && !((String) candidate).trim().isEmpty()) {
                List<String> out = new ArrayList<String>();
                out.add(((String) candidate).trim());
                return out;
            }
        }
        return new ArrayList<String>();
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
