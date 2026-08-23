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
    public void everyProcessorCommandHasASlashTwin() {
        Set<String> names = new HashSet<String>();
        for (ChatCommandContribution contribution : ResearchChatCommands.all()) {
            names.add(contribution.getDescriptor().getName());
        }
        // The text adapters…
        assertTrue(names.contains("search"));
        assertTrue(names.contains("open"));
        // …the full semantic state vocabulary (the red tags' ids) AND the derived-action service
        // commands — "Neue Quellen auswerten" (review-sources) was tag-only once, never again.
        for (String command : ResearchChatCommands.processorCommandNames()) {
            assertTrue("missing slash twin for processor command: " + command,
                    names.contains(command));
        }
        assertTrue(names.contains("review-sources"));
        assertTrue(names.contains("generate-visualization"));
        assertTrue(names.contains("generate-outline"));
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
