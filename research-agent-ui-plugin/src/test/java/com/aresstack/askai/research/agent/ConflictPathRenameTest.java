package com.aresstack.askai.research.agent;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Safety slice: the interim referential-integrity rule until the ID sidecar. The slice-2 gate
 * renamed "Setup" to "Entwicklungsumgebung" while conflict-1 still pointed at
 * [Grundlagen, Setup, ESP-IDF] — the later "Ja." died on TARGET_NODE_NOT_FOUND. A rename must
 * rewrite exactly the conflict paths that run THROUGH the renamed node, and nothing else.
 */
public class ConflictPathRenameTest {

    private static final List<String> CONFLICT =
            Arrays.asList("Grundlagen", "Setup", "ESP-IDF");

    @Test
    public void aParentRenameRewritesTheSegmentAndKeepsTheRest() {
        assertEquals(Arrays.asList("Grundlagen", "Entwicklungsumgebung", "ESP-IDF"),
                ResearchAgentSession.renamedConflictPath(CONFLICT,
                        Arrays.asList("Grundlagen", "Setup"), "Entwicklungsumgebung"));
        // The conflict card itself renamed — the registered path follows it too.
        assertEquals(Arrays.asList("Grundlagen", "Setup", "ESP-IDF (alt)"),
                ResearchAgentSession.renamedConflictPath(CONFLICT,
                        CONFLICT, "ESP-IDF (alt)"));
    }

    @Test
    public void unrelatedRenamesNeverTouchTheConflictPath() {
        // A different branch, a mere name coincidence deeper down, and a path longer than the
        // conflict: none of them is a prefix, none may rewrite anything.
        assertNull(ResearchAgentSession.renamedConflictPath(CONFLICT,
                Arrays.asList("Praxis", "Setup"), "X"));
        assertNull(ResearchAgentSession.renamedConflictPath(CONFLICT,
                Arrays.asList("Setup"), "X"));
        assertNull(ResearchAgentSession.renamedConflictPath(CONFLICT,
                Arrays.asList("Grundlagen", "Setup", "ESP-IDF", "Tief"), "X"));
        // A no-op "rename" reports unaffected instead of a fake update.
        assertNull(ResearchAgentSession.renamedConflictPath(CONFLICT,
                Arrays.asList("Grundlagen", "Setup"), "Setup"));
    }
}
