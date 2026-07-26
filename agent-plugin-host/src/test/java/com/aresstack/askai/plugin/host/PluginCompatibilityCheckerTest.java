package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/** Table-driven checks that the first failing compatibility rule wins. */
public class PluginCompatibilityCheckerTest {

    private final PluginCompatibilityChecker checker = new PluginCompatibilityChecker(1);

    private static WorkspacePluginDescriptor descriptor(int apiVersion, String id, String version) {
        return WorkspacePluginDescriptor.builder()
                .id(id).displayName("X").version(version).pluginApiVersion(apiVersion).build();
    }

    private static Set<String> none() {
        return Collections.emptySet();
    }

    @Test
    public void compatibleWhenEverythingMatches() {
        assertEquals(PluginCompatibility.COMPATIBLE, checker.check(
                descriptor(1, "com.x.a", "1.0.0"), "com.x.a", "1.0.0", 1, none()));
    }

    @Test
    public void missingExtension() {
        assertEquals(PluginCompatibility.MISSING_EXTENSION, checker.check(
                descriptor(1, "com.x.a", "1.0.0"), "com.x.a", "1.0.0", 0, none()));
    }

    @Test
    public void multipleExtensions() {
        assertEquals(PluginCompatibility.MULTIPLE_EXTENSIONS, checker.check(
                descriptor(1, "com.x.a", "1.0.0"), "com.x.a", "1.0.0", 2, none()));
    }

    @Test
    public void incompatibleApiVersion() {
        assertEquals(PluginCompatibility.INCOMPATIBLE_API_VERSION, checker.check(
                descriptor(2, "com.x.a", "1.0.0"), "com.x.a", "1.0.0", 1, none()));
    }

    @Test
    public void idMismatch() {
        assertEquals(PluginCompatibility.ID_MISMATCH, checker.check(
                descriptor(1, "com.x.a", "1.0.0"), "com.x.b", "1.0.0", 1, none()));
    }

    @Test
    public void versionMismatch() {
        assertEquals(PluginCompatibility.VERSION_MISMATCH, checker.check(
                descriptor(1, "com.x.a", "1.0.0"), "com.x.a", "2.0.0", 1, none()));
    }

    @Test
    public void duplicateId() {
        Set<String> seen = new HashSet<String>();
        seen.add("com.x.a");
        assertEquals(PluginCompatibility.DUPLICATE_ID, checker.check(
                descriptor(1, "com.x.a", "1.0.0"), "com.x.a", "1.0.0", 1, seen));
    }

    @Test
    public void blankManifestFieldsAreTolerated() {
        assertEquals(PluginCompatibility.COMPATIBLE, checker.check(
                descriptor(1, "com.x.a", "1.0.0"), "", "", 1, none()));
    }
}
