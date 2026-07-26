package com.aresstack.askai.research.ui;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.ResearchPhase;
import com.aresstack.askai.research.state.ResearchRunState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The controller exposes state-machine-gated enablement, no-op illegal commands, and section filtering. */
public class ResearchWorkspaceControllerTest {

    @Test
    public void enablementReflectsTheStateMachine() {
        ResearchWorkspaceController c = new ResearchWorkspaceController("s1");
        assertEquals(ResearchPhase.SCOPING, c.phase());
        assertEquals(ResearchRunState.NEW, c.runState());
        assertTrue(c.canDispatch(ResearchCommandType.START));
        assertFalse(c.canDispatch(ResearchCommandType.APPROVE_OUTLINE));
        assertFalse(c.canDispatch(ResearchCommandType.PAUSE));
    }

    @Test
    public void illegalCommandIsANoOp() {
        ResearchWorkspaceController c = new ResearchWorkspaceController("s1");
        boolean applied = c.dispatch(ResearchCommandType.APPROVE_OUTLINE);
        assertFalse(applied);
        assertEquals(ResearchPhase.SCOPING, c.phase());
        assertEquals(ResearchRunState.NEW, c.runState());

        assertTrue(c.dispatch(ResearchCommandType.START));
        assertEquals(ResearchRunState.RUNNING, c.runState());
    }

    @Test
    public void activeSectionFiltersSourcesAndFindings() {
        ResearchWorkspaceController c = new ResearchWorkspaceController("s1");
        // No selection → everything.
        assertEquals(3, c.sourcesForActiveSection().size());
        assertEquals(3, c.findingsForActiveSection().size());

        c.setActiveSection("s3");
        assertEquals(2, c.sourcesForActiveSection().size()); // src1 + src3
        assertEquals(2, c.findingsForActiveSection().size()); // f1 + f3

        c.setActiveSection("s2");
        assertEquals(1, c.sourcesForActiveSection().size()); // src2
        assertEquals(1, c.findingsForActiveSection().size()); // f2
    }

    @Test
    public void outlineEditsAreGatedAndValidated() {
        ResearchWorkspaceController c = new ResearchWorkspaceController("s1");
        assertTrue(c.canEditOutline());
        long before = c.getOutline().getRevision();
        assertTrue(c.addSection("", "sX", "Extra"));
        assertTrue(c.getOutline().getRevision() > before);
        // Adding under a missing parent is rejected (no throw, returns false).
        assertFalse(c.addSection("missing", "sY", "Bad"));
    }
}
