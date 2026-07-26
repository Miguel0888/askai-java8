package com.aresstack.askai.plugin.api;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** The descriptor is immutable, validates required fields and defaults the optional ones. */
public class WorkspacePluginDescriptorTest {

    @Test
    public void buildsWithDefaultsForOptionalFields() {
        WorkspacePluginDescriptor descriptor = WorkspacePluginDescriptor.builder()
                .id("com.aresstack.askai.research")
                .displayName("Research Agent")
                .version("0.1.0")
                .build();

        assertEquals("com.aresstack.askai.research", descriptor.getId());
        assertEquals("Research Agent", descriptor.getDisplayName());
        assertEquals("0.1.0", descriptor.getVersion());
        assertEquals(1, descriptor.getPluginApiVersion());
        assertEquals("", descriptor.getProvider());
        assertEquals("", descriptor.getDescription());
    }

    @Test
    public void rejectsMissingId() {
        try {
            WorkspacePluginDescriptor.builder().displayName("X").version("1.0.0").build();
            fail("missing id must throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsAnIdWithoutAReverseDomainShape() {
        assertRejected("research");         // single segment
        assertRejected("com aresstack x");  // whitespace
        assertRejected("com..double");      // empty segment
    }

    @Test
    public void rejectsPluginApiVersionBelowOne() {
        try {
            base().pluginApiVersion(0).build();
            fail("pluginApiVersion < 1 must throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void trimsStringFieldsAndAllowsNegativeDisplayOrder() {
        WorkspacePluginDescriptor descriptor = base()
                .displayName("  Research Agent  ")
                .provider("  AresStack  ")
                .displayOrder(-5)
                .build();
        assertEquals("Research Agent", descriptor.getDisplayName());
        assertEquals("AresStack", descriptor.getProvider());
        assertEquals(-5, descriptor.getDisplayOrder());
    }

    private static WorkspacePluginDescriptor.Builder base() {
        return WorkspacePluginDescriptor.builder()
                .id("com.aresstack.askai.research")
                .displayName("Research Agent")
                .version("0.1.0");
    }

    private static void assertRejected(String id) {
        try {
            WorkspacePluginDescriptor.builder().id(id).displayName("X").version("1.0.0").build();
            fail("id must be rejected: " + id);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
