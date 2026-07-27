package com.aresstack.askai.research.state.oo;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Commit-21c strictness: the native {@link ResearchStateFactory} rejects an approval gate — or an interruption
 * continuing into one — without a pending approval id, and never silently invents one. Legacy data is repaired
 * only through the explicit {@link LegacyResearchStateMigration}.
 */
public class ApprovalMementoStrictnessTest {

    private final ResearchStateFactory factory = ResearchStateFactory.getInstance();

    @Test
    public void approvalGateWithoutIdIsRejected() {
        try {
            factory.state(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL, null, null);
            fail("expected rejection of an approval gate without a pending approval id");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void interruptionContinuingIntoApprovalWithoutIdIsRejected() {
        try {
            factory.state(ResearchStateIds.OUTLINE, ResearchStateIds.BLOCKED,
                    ResearchStateIds.WAITING_APPROVAL, null);
            fail("expected rejection of a blocked-approval interruption without a pending approval id");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void approvalIdOnANonApprovalContinuationIsRejected() {
        try {
            factory.state(ResearchStateIds.OUTLINE, ResearchStateIds.PAUSED,
                    ResearchStateIds.RUNNING, "approval-1");
            fail("expected rejection of an approval id on a non-approval continuation");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void restoreRejectsAnApprovalGateMementoWithoutId() {
        try {
            factory.restore(new ResearchStateMemento(ResearchStateIds.OUTLINE,
                    ResearchStateIds.WAITING_APPROVAL, null, 3L, null));
            fail("restore must reject an approval gate without an id, never fabricate one");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void restorePreservesTheExactApprovalIdAndNeverInventsANewOne() {
        ResearchStateMemento memento = new ResearchStateMemento(ResearchStateIds.OUTLINE,
                ResearchStateIds.WAITING_APPROVAL, null, 3L, "approval-xyz");
        ResearchPhaseState restored = factory.restore(memento);
        assertEquals("approval-xyz", restored.getCurrentState().getPendingApprovalId());
        ResearchStateMemento again = factory.snapshot(restored, 3L);
        assertEquals("approval-xyz", again.getPendingApprovalId());
    }

    @Test
    public void legacyMigrationSynthesizesAnApprovalIdForLegacyApprovalGates() {
        final AtomicInteger n = new AtomicInteger();
        LegacyResearchStateMigration migration = new LegacyResearchStateMigration(factory,
                new LegacyResearchStateMigration.IdGenerator() {
                    public String newId() {
                        return "legacy-" + n.incrementAndGet();
                    }
                });
        ResearchPhaseState gate =
                migration.reconstruct(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL, null, null);
        assertNotNull("migration must synthesize an approval id for legacy gate data",
                gate.getCurrentState().getPendingApprovalId());

        ResearchPhaseState blocked = migration.reconstruct(ResearchStateIds.OUTLINE,
                ResearchStateIds.BLOCKED, ResearchStateIds.WAITING_APPROVAL, null);
        assertNotNull("migration also covers interruptions continuing into an approval gate",
                blocked.getCurrentState().getPendingApprovalId());
    }
}
