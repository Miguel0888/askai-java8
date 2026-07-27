package com.aresstack.askai.plugin.host;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link PluginRuntimeGeneration}'s structured retirement: a stop/unload failure for one plugin
 * is isolated and reported (never swallowed), and an incomplete retirement is flagged so it can be retried.
 */
public class PluginRuntimeGenerationRetireTest {

    @Test
    public void cleanRetirementStopsAndUnloadsEverythingAndIsComplete() {
        RecordingOps ops = new RecordingOps(Arrays.asList("a", "b"), Arrays.asList("a", "b"));
        GenerationRetirementResult result = PluginRuntimeGeneration.retire(ops);
        assertEquals(Arrays.asList("a", "b"), result.getStopped());
        assertEquals(Arrays.asList("a", "b"), result.getUnloaded());
        assertFalse(result.hasFailures());
        assertTrue(result.isComplete());
    }

    @Test
    public void stopFailureIsIsolatedReportedAndOthersStillHandled() {
        RecordingOps ops = new RecordingOps(Arrays.asList("a", "b"), Arrays.asList("a", "b"));
        ops.failStop("a");
        GenerationRetirementResult result = PluginRuntimeGeneration.retire(ops);
        assertEquals("b still stopped", Arrays.asList("b"), result.getStopped());
        assertTrue("stop failure reported", result.getStopFailures().containsKey("a"));
        assertEquals("both still unloaded", Arrays.asList("a", "b"), result.getUnloaded());
        assertFalse("incomplete when a stop failed", result.isComplete());
    }

    @Test
    public void unloadFailureKeepsGenerationIncompleteForRetry() {
        RecordingOps ops = new RecordingOps(Arrays.asList("a"), Arrays.asList("a", "b"));
        ops.failUnload("b");
        GenerationRetirementResult result = PluginRuntimeGeneration.retire(ops);
        assertTrue(result.getUnloadFailures().containsKey("b"));
        assertEquals(Arrays.asList("a"), result.getUnloaded());
        assertFalse(result.isComplete());
    }

    private static final class RecordingOps implements PluginRuntimeGeneration.RetireOps {
        private final List<String> started;
        private final List<String> loaded;
        private final List<String> stopFail = new ArrayList<String>();
        private final List<String> unloadFail = new ArrayList<String>();

        RecordingOps(List<String> started, List<String> loaded) {
            this.started = started;
            this.loaded = loaded;
        }

        void failStop(String id) {
            stopFail.add(id);
        }

        void failUnload(String id) {
            unloadFail.add(id);
        }

        public List<String> startedIds() {
            return started;
        }

        public List<String> loadedIds() {
            return loaded;
        }

        public void stop(String pluginId) {
            if (stopFail.contains(pluginId)) {
                throw new RuntimeException("stop failed for " + pluginId);
            }
        }

        public void unload(String pluginId) {
            if (unloadFail.contains(pluginId)) {
                throw new RuntimeException("unload failed for " + pluginId);
            }
        }
    }
}
