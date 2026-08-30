package com.aresstack.askai.research.jsontree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A HOST-SIDE address of one array-valued property inside a document — the node handle for branch
 * edits. It lives outside the JSON on purpose: the model-edited branch stays free of technical
 * IDs, the host alone knows where the branch came from. Each step names a property whose value
 * must be an ARRAY; to descend further, the step's {@code elementIndex} picks the container
 * object inside that array which holds the next step's property (0 for the common single-container
 * layout, e.g. {@code $.FreeRTOS[0].Synchronisation}).
 */
public final class JsonBranchPath {

    /** One hop: the array-valued property, and which array element carries the NEXT hop. */
    public static final class Step {
        private final String property;
        private final int elementIndex;

        public Step(String property, int elementIndex) {
            this.property = property;
            this.elementIndex = elementIndex;
        }

        public String getProperty() {
            return property;
        }

        public int getElementIndex() {
            return elementIndex;
        }
    }

    private final List<Step> steps;

    private JsonBranchPath(List<Step> steps) {
        this.steps = Collections.unmodifiableList(steps);
    }

    /** The common shorthand: every hop descends through array element 0. */
    public static JsonBranchPath of(String... properties) {
        List<Step> steps = new ArrayList<Step>();
        for (String property : properties) {
            steps.add(new Step(property, 0));
        }
        return new JsonBranchPath(steps);
    }

    public static JsonBranchPath ofSteps(Step... steps) {
        return new JsonBranchPath(new ArrayList<Step>(Arrays.asList(steps)));
    }

    public List<Step> getSteps() {
        return steps;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** JSONPath-style rendering, e.g. {@code $.FreeRTOS[0].Synchronisation} — for diagnostics. */
    public String describe() {
        return describePrefix(steps.size());
    }

    /** The first {@code stepCount} hops as a path — pinpoints WHERE resolution failed. */
    public String describePrefix(int stepCount) {
        StringBuilder sb = new StringBuilder("$");
        for (int i = 0; i < stepCount && i < steps.size(); i++) {
            Step step = steps.get(i);
            sb.append('.').append(step.getProperty());
            if (i < stepCount - 1 && i < steps.size() - 1) {
                sb.append('[').append(step.getElementIndex()).append(']');
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
