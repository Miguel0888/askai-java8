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
}
