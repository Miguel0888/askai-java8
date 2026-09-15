package com.aresstack.askai.research.knowledge.processing;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #39 reconciliation pins: only never-processed, corpus-eligible sources with segmentable
 * text get their missing job; already-processed sources (any identity) are left alone; the
 * synthesized capture id is deterministic, so a re-run after restart cannot double-enqueue
 * through the queue's idempotency; and every job carries the session's REAL identity because
 * it travels the same QueueBackedKnowledgeProcessingScheduler (which structurally rejects an
 * empty fingerprint — no fake-world jobs, ever).
 */
public class ProcessingReconciliationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class Src implements ProcessingReconciliation.CandidateSource {
        final String id;
        final boolean text;
        final boolean eligible;

        Src(String id, boolean text, boolean eligible) {
            this.id = id;
            this.text = text;
            this.eligible = eligible;
        }

        public String sourceId() {
            return id;
        }

        public boolean hasSegmentableText() {
            return text;
        }

        public boolean corpusEligible() {
            return eligible;
        }
    }

    @Test
    public void onlyNeverProcessedEligibleSourcesWithTextGetTheirMissingJob() {
        final List<String> enqueued = new ArrayList<String>();
        Set<String> processed = new HashSet<String>(Collections.singletonList("source-done"));
        int count = ProcessingReconciliation.reconcile(Arrays.asList(
                        new Src("source-missing", true, true),
                        new Src("source-done", true, true),      // already processed → not our gap
                        new Src("source-parked", false, true),   // no text → nothing to segment
                        new Src("source-excluded", true, false), // corpus-ineligible → never waste
                        new Src("", true, true)),                // blank id → skip
                processed,
                new ProcessingReconciliation.MissingJobScheduler() {
                    public void enqueue(String captureId, String sourceId) {
                        enqueued.add(captureId + "->" + sourceId);
                    }
                });
        assertEquals(1, count);
        assertEquals(Collections.singletonList(
                "import-reconciled-source-missing->source-missing"), enqueued);
    }

    @Test
    public void aRerunAfterRestartNeverDoubleEnqueuesThroughTheQueueIdempotency() throws Exception {
        FileSourceProcessingQueue queue =
                new FileSourceProcessingQueue(tmp.newFolder("processing"));
        // The SAME scheduler kind the acceptance hook uses — real identity stamping.
        final QueueBackedKnowledgeProcessingScheduler scheduler =
                new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "fpReal");
        ProcessingReconciliation.MissingJobScheduler adapter =
                new ProcessingReconciliation.MissingJobScheduler() {
                    public void enqueue(String captureId, String sourceId) {
                        scheduler.enqueue(captureId, sourceId, "en");
                    }
                };
        List<Src> candidates =
                Collections.singletonList(new Src("source-1", true, true));
        Set<String> processed = Collections.emptySet();
        // Two session starts (restart) reconcile the same world: the deterministic capture
        // id maps to the same idempotency key, so the queue holds exactly ONE job.
        ProcessingReconciliation.reconcile(candidates, processed, adapter);
        ProcessingReconciliation.reconcile(candidates, processed, adapter);
        SourceProcessingJob job = queue.takeNext();
        assertEquals("import-reconciled-source-1", job.getRequest().getCaptureId());
        assertEquals("source-1", job.getRequest().getSourceId());
        assertEquals("the job names the REAL vector world", "fpReal",
                job.getRequest().getEmbeddingModelFingerprint());
        assertTrue("exactly one job despite two reconciliation runs",
                queue.takeNext() == null);
    }

    @Test
    public void aFakeWorldIsStructurallyImpossibleOnTheReconciliationPath() throws Exception {
        FileSourceProcessingQueue queue =
                new FileSourceProcessingQueue(tmp.newFolder("processing"));
        try {
            new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "  ");
            throw new AssertionError("an empty fingerprint must never reach the queue");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("embedding"));
        }
    }
}
