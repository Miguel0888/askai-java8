package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertTrue;

/**
 * The slash surface mirrors the semantic command vocabulary: every red action tag has a typed
 * {@code /command} twin (issue #36 follow-up — "Fragestellung freigeben" was tag-only before).
 */
public class ResearchChatCommandsTest {

    @Test
    public void everySemanticStateCommandHasASlashTwin() {
        Set<String> names = new HashSet<String>();
        for (ChatCommandContribution contribution : ResearchChatCommands.all()) {
            names.add(contribution.getDescriptor().getName());
        }
        // The service adapters…
        assertTrue(names.contains("search"));
        assertTrue(names.contains("open"));
        // …and the full semantic vocabulary (the red tags' ids).
        for (String semantic : ResearchSemanticCommands.names()) {
            assertTrue("missing slash twin for semantic command: " + semantic,
                    names.contains(semantic));
        }
    }

    @Test
    public void theSemanticTableRoundTrips() {
        // The reverse lookup is what makes phase plates clickable — it must stay consistent with
        // the forward candidates (one table, no drift).
        for (String name : ResearchSemanticCommands.names()) {
            for (com.aresstack.askai.research.state.ResearchCommandType type
                    : ResearchSemanticCommands.candidates(name)) {
                assertTrue(name.equals(ResearchSemanticCommands.semanticNameFor(type)));
            }
        }
    }
}
