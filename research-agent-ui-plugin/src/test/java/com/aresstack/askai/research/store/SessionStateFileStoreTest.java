package com.aresstack.askai.research.store;

import com.aresstack.askai.research.state.oo.ResearchStateFactory;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.state.oo.ResearchStateMemento;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * #43 correction pins: the store stamps its phase-model marker, and a file WITHOUT it is
 * pre-4-phase legacy — its "outline" was the old pre-research phase and proves nothing about
 * Sources being done, so it conservatively loads as Sources/waiting (never as the new phase-3
 * Outline, which could skip research; no old outline approval is carried forward).
 */
public class SessionStateFileStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SessionStateFileStore store() {
        return new SessionStateFileStore(tmp.getRoot());
    }

    private void writeLegacyFile(String phaseId, String stateId, String continuation,
                                 String approvalId) throws Exception {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"phaseId\": \"").append(phaseId).append("\",\n");
        sb.append("  \"stateId\": \"").append(stateId).append("\",\n");
        sb.append("  \"continuationStateId\": ")
                .append(continuation == null ? "null" : "\"" + continuation + "\"").append(",\n");
        sb.append("  \"revision\": 5,\n");
        sb.append("  \"pendingApprovalId\": ")
                .append(approvalId == null ? "null" : "\"" + approvalId + "\"").append("\n");
        sb.append("}\n");
        File file = new File(tmp.getRoot(), "research-session.json");
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
        try {
            writer.write(sb.toString());
        } finally {
            writer.close();
        }
    }

    @Test
    public void aV4SaveRoundTripsIdenticallyIncludingTheOutlinePhase() throws Exception {
        SessionStateFileStore store = store();
        store.save(new ResearchStateMemento(ResearchStateIds.OUTLINE,
                ResearchStateIds.WAITING_APPROVAL, null, 11L, "ap-9"));
        ResearchStateMemento loaded = store.load();
        assertEquals("a NEW-model outline stays outline", ResearchStateIds.OUTLINE,
                loaded.getPhaseId());
        assertEquals(ResearchStateIds.WAITING_APPROVAL, loaded.getStateId());
        assertEquals("ap-9", loaded.getPendingApprovalId());
        assertEquals(11L, loaded.getRevision());
    }

    @Test
    public void aPreV4OutlineApprovalLoadsAsSourcesWaitingWithoutTheOldApproval() throws Exception {
        writeLegacyFile(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL, null, "ap-old");
        ResearchStateMemento loaded = store().load();
        assertEquals("pre-v4 outline was PRE-research — Sources are not proven done",
                ResearchStateIds.RESEARCH, loaded.getPhaseId());
        assertEquals(ResearchStateIds.WAITING, loaded.getStateId());
        assertNull("the old outline approval never becomes a new phase-3 approval",
                loaded.getPendingApprovalId());
        assertEquals(5L, loaded.getRevision());
        // The migrated memento is a legal state of the strict factory as-is.
        assertEquals(ResearchStateIds.RESEARCH,
                ResearchStateFactory.getInstance().restore(loaded).getPhaseId());
    }

    @Test
    public void aPreV4InterruptedOutlineKeepsTheInterruptButContinuesIntoSourcesWaiting()
            throws Exception {
        writeLegacyFile(ResearchStateIds.OUTLINE, ResearchStateIds.PAUSED,
                ResearchStateIds.WAITING_APPROVAL, "ap-old");
        ResearchStateMemento loaded = store().load();
        assertEquals(ResearchStateIds.RESEARCH, loaded.getPhaseId());
        assertEquals("the interrupt status survives", ResearchStateIds.PAUSED, loaded.getStateId());
        assertEquals("but it continues into the Sources gate", ResearchStateIds.WAITING,
                loaded.getContinuationStateId());
        assertNull(loaded.getPendingApprovalId());
    }

    @Test
    public void preV4NonOutlinePhasesLoadUnchangedForTheExistingRestoreRemap() throws Exception {
        writeLegacyFile(ResearchStateIds.EVIDENCE, ResearchStateIds.WAITING_APPROVAL, null, "ap-2");
        ResearchStateMemento loaded = store().load();
        assertEquals("evidence keeps its established factory-restore remap",
                ResearchStateIds.EVIDENCE, loaded.getPhaseId());
        assertEquals("ap-2", loaded.getPendingApprovalId());
    }
}
