package com.aresstack.askai.plugin.host;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link PluginRuntimeGeneration}'s safe per-plugin retirement: a plugin is unloaded only after
 * its stop is confirmed, {@code unloadPlugin}'s boolean return is honoured, and a generation is {@code complete}
 * only when the manager confirms every originally-loaded plugin is neither started nor loaded.
 */
public class PluginRuntimeGenerationRetireTest {

    @Test
    public void cleanRetirementStopsAndUnloadsEverythingAndIsComplete() {
        Ops ops = new Ops(Arrays.asList("a", "b"), Arrays.asList("a", "b"));
        GenerationRetirementResult result = PluginRuntimeGeneration.retire(ops);
        assertTrue(result.getStopped().containsAll(Arrays.asList("a", "b")));
        assertTrue(result.getUnloaded().containsAll(Arrays.asList("a", "b")));
        assertFalse(result.hasFailures());
        assertTrue(result.isComplete());
        assertTrue("everything unloaded", ops.loaded.isEmpty());
    }

    @Test
    public void stopFailureIsolatesThePluginAndNeverUnloadsIt() {
        Ops ops = new Ops(Arrays.asList("a", "b"), Arrays.asList("a", "b"));
        ops.failStop.add("a");
        GenerationRetirementResult result = PluginRuntimeGeneration.retire(ops);
        assertTrue("stop failure reported", result.getStopFailures().containsKey("a"));
        assertFalse("a must NOT be unloaded after a stop failure", ops.unloadCalls.contains("a"));
        assertTrue("b is still fully retired", ops.unloadCalls.contains("b"));
        assertTrue("a stays started (not stopped)", ops.started.contains("a"));
        assertTrue("a stays loaded (not unloaded)", ops.loaded.contains("a"));
        assertFalse("a still live => incomplete", result.isComplete());
    }

    @Test
    public void unloadReturningFalseIsAnIncompleteRetirement() {
        Ops ops = new Ops(java.util.Collections.<String>emptyList(), Arrays.asList("a"));
        ops.unloadReturnsFalse.add("a");
        GenerationRetirementResult result = PluginRuntimeGeneration.retire(ops);
        assertTrue(result.getUnloadFailures().containsKey("a"));
        assertTrue("a stays loaded when unloadPlugin returned false", ops.loaded.contains("a"));
        assertFalse(result.isComplete());
    }

    @Test
    public void unconfirmedStopIsTreatedAsFailureAndBlocksUnload() {
        Ops ops = new Ops(Arrays.asList("a"), Arrays.asList("a"));
        ops.stopDoesNotConfirm.add("a"); // stop() throws nothing but the manager still reports it started
        GenerationRetirementResult result = PluginRuntimeGeneration.retire(ops);
        assertTrue(result.getStopFailures().containsKey("a"));
        assertFalse("must not unload a plugin whose stop was not confirmed", ops.unloadCalls.contains("a"));
        assertFalse(result.isComplete());
    }

    @Test
    public void retryHandlesOnlyTheRemainingPluginsAndCanComplete() {
        Ops ops = new Ops(Arrays.asList("a", "b"), Arrays.asList("a", "b"));
        ops.failStop.add("a");
        GenerationRetirementResult first = PluginRuntimeGeneration.retire(ops);
        assertFalse(first.isComplete());
        assertFalse("b already gone after the first attempt", ops.loaded.contains("b"));

        ops.failStop.clear();          // the transient problem cleared
        ops.unloadCalls.clear();
        GenerationRetirementResult second = PluginRuntimeGeneration.retire(ops);
        assertTrue("only 'a' remained to retire", second.getUnloaded().contains("a"));
        assertFalse("b is not touched again", ops.unloadCalls.contains("b"));
        assertTrue(second.isComplete());
        assertTrue(ops.loaded.isEmpty());
    }

    @Test
    public void completeRequiresManagerConfirmationNotJustAbsenceOfExceptions() {
        // 'a' unloads (no exception) but the manager keeps reporting it loaded → not complete.
        Ops ops = new Ops(java.util.Collections.<String>emptyList(), Arrays.asList("a"));
        ops.unloadDoesNotConfirm.add("a");
        GenerationRetirementResult result = PluginRuntimeGeneration.retire(ops);
        assertTrue(result.getUnloadFailures().containsKey("a"));
        assertFalse(result.isComplete());
    }

    /** A stateful fake manager: successful stop/unload mutate the started/loaded sets, like the real PF4J one. */
    private static final class Ops implements PluginRuntimeGeneration.RetireOps {
        private final Set<String> started;
        private final Set<String> loaded;
        private final Set<String> failStop = new LinkedHashSet<String>();
        private final Set<String> unloadReturnsFalse = new LinkedHashSet<String>();
        private final Set<String> stopDoesNotConfirm = new LinkedHashSet<String>();
        private final Set<String> unloadDoesNotConfirm = new LinkedHashSet<String>();
        private final List<String> unloadCalls = new ArrayList<String>();

        Ops(List<String> started, List<String> loaded) {
            this.started = new LinkedHashSet<String>(started);
            this.loaded = new LinkedHashSet<String>(loaded);
        }

        public List<String> startedIds() {
            return new ArrayList<String>(started);
        }

        public List<String> loadedIds() {
            return new ArrayList<String>(loaded);
        }

        public void stop(String pluginId) {
            if (failStop.contains(pluginId)) {
                throw new RuntimeException("stop failed for " + pluginId);
            }
            if (!stopDoesNotConfirm.contains(pluginId)) {
                started.remove(pluginId); // a confirmed stop
            }
        }

        public boolean unload(String pluginId) {
            unloadCalls.add(pluginId);
            if (unloadReturnsFalse.contains(pluginId)) {
                return false; // did not unload; stays loaded
            }
            if (!unloadDoesNotConfirm.contains(pluginId)) {
                loaded.remove(pluginId); // a confirmed unload
            }
            return true;
        }
    }
}
