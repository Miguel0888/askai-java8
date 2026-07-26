package com.aresstack.audio.pipeline;

import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The registry is the single source of block descriptors, defaults and processors. */
public class AudioBlockRegistryTest {

    private final AudioBlockRegistry registry = AudioBlockRegistry.getInstance();

    @Test
    public void everyBlockTypeHasAUsableDescriptor() {
        assertEquals(AudioBlockType.values().length, registry.all().size());
        for (AudioBlockType type : AudioBlockType.values()) {
            AudioBlockDescriptor descriptor = registry.descriptor(type);
            assertNotNull("descriptor for " + type, descriptor);
            assertEquals(type, descriptor.getType());
            assertNotNull("processor for " + type, descriptor.createProcessor());
            assertNotNull("capabilities for " + type, descriptor.getCapabilities());
            AudioBlockDefinition definition = descriptor.createDefaultDefinition("id-" + type.name());
            assertEquals(type, definition.getType());
            assertTrue(definition.isEnabled());
        }
    }

    @Test
    public void defaultParametersComeFromTheDescriptor() {
        // AudioBlockDefinition.defaultParameters delegates to the registry — one source of truth.
        Map<String, String> viaDefinition = AudioBlockDefinition.defaultParameters(AudioBlockType.LOW_PASS);
        Map<String, String> viaRegistry = registry.defaultParameters(AudioBlockType.LOW_PASS);
        assertEquals(viaRegistry, viaDefinition);
        assertEquals("7200", viaDefinition.get("cutoffHz")); // integer-valued default stays clean
        assertEquals("FIR_65", viaDefinition.get("implementation"));
    }

    @Test
    public void descriptorForIdResolvesKnownTypesAndRejectsUnknown() {
        assertSame(registry.descriptor(AudioBlockType.COMPRESSOR), registry.descriptorForId("COMPRESSOR"));
        assertNull(registry.descriptorForId("NOT_A_REAL_BLOCK"));
        assertNull(registry.descriptorForId(null));
    }
}
