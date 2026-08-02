package com.aresstack.askai.research.visualize;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The snapshot's content hash is the stale-result marker: stable for identical content, different otherwise. */
public class ArtifactSnapshotTest {

    @Test
    public void identicalContentHashesEqualAndDifferentContentDoesNot() {
        ArtifactSnapshot a = new ArtifactSnapshot("research-brief", "# Brief\nWearables", "scoping");
        ArtifactSnapshot sameContent = new ArtifactSnapshot("research-brief", "# Brief\nWearables", "scoping");
        ArtifactSnapshot changed = new ArtifactSnapshot("research-brief", "# Brief\nWearables audio", "scoping");

        assertEquals("same content -> same hash", a.getContentHash(), sameContent.getContentHash());
        assertFalse("changed content -> new hash", a.getContentHash().equals(changed.getContentHash()));
        assertTrue(a.getContentHash().length() > 0);
    }
}
