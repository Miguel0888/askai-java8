package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchPhaseState;
import com.aresstack.askai.research.state.oo.ResearchStateFactory;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The restored decision buttons are derived from {@code getAllowedCommands()} of the REAL state objects:
 * every decision state re-offers exactly its user decisions, working/terminal states offer nothing.
 */
public class AllowedCommandsActionsProviderTest {

    private final ResearchStateFactory factory = ResearchStateFactory.getInstance();
    private final AllowedCommandsActionsProvider provider = new AllowedCommandsActionsProvider();

    private List<String> actionIdsFor(String phaseId, String stateId, String continuation,
                                      String approvalId) {
        ResearchPhaseState phase = factory.phase(phaseId,
                factory.state(phaseId, stateId, continuation, approvalId));
        List<String> ids = new ArrayList<String>();
        for (RestoredActionsProvider.RestoredAction action : provider.deriveFrom(phase)) {
            ids.add(action.getActionId());
        }
        return ids;
    }

    @Test
    public void anApprovalGateOffersApproveAndRequestChanges() {
        List<String> ids = actionIdsFor(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL,
                null, "a1");
        assertTrue("approve is offered", ids.contains("approve"));
        assertTrue("request changes is offered", ids.contains("changes"));
        assertEquals("nothing else is offered", 2, ids.size());
    }

    @Test
    public void aReadyToStartWaitOffersContinue() {
        List<String> ids = actionIdsFor(ResearchStateIds.RESEARCH, ResearchStateIds.WAITING, null, null);
        assertEquals("the ready-to-start gate offers exactly the continue decision",
                java.util.Collections.singletonList("continue"), ids);
    }

    @Test
    public void interruptionsOfferTheirContinuationDecision() {
        assertEquals(java.util.Collections.singletonList("resume"),
                actionIdsFor(ResearchStateIds.RESEARCH, ResearchStateIds.PAUSED,
                        ResearchStateIds.RUNNING, null));
        assertEquals(java.util.Collections.singletonList("resume"),
                actionIdsFor(ResearchStateIds.RESEARCH, ResearchStateIds.BLOCKED,
                        ResearchStateIds.RUNNING, null));
        assertEquals(java.util.Collections.singletonList("retry"),
                actionIdsFor(ResearchStateIds.RESEARCH, ResearchStateIds.FAILED,
                        ResearchStateIds.RUNNING, null));
    }

    @Test
    public void workingStatesOfferNoRestoredButtons() {
        assertTrue("running is agent-driven, no user decision",
                actionIdsFor(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null).isEmpty());
        assertTrue("a fresh scoping state has no restored decision",
                actionIdsFor(ResearchStateIds.SCOPING, ResearchStateIds.NEW, null, null).isEmpty());
    }

    @Test
    public void anInterruptedApprovalGateStillResumesIntoTheSameApproval() {
        // Pausing an approval gate keeps the pending approval id; the restored button is the resume
        // decision (the gate itself comes back after resuming).
        List<String> ids = actionIdsFor(ResearchStateIds.OUTLINE, ResearchStateIds.PAUSED,
                ResearchStateIds.WAITING_APPROVAL, "a1");
        assertEquals(java.util.Collections.singletonList("resume"), ids);
    }

    @Test
    public void mappedCommandsStayUserDecisions() {
        // Guard against future enum growth: every mapped command is one the user can meaningfully press.
        ResearchPhaseState gate = factory.phase(ResearchStateIds.OUTLINE,
                factory.state(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL, null, "a1"));
        for (RestoredActionsProvider.RestoredAction action : provider.deriveFrom(gate)) {
            ResearchCommandType c = action.getCommand();
            assertTrue("restored buttons never carry interrupt machinery",
                    c != ResearchCommandType.PAUSE && c != ResearchCommandType.BLOCK
                            && c != ResearchCommandType.FAIL && c != ResearchCommandType.CANCEL);
        }
    }
}
