package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The accepted research sources (from source_list) reach the model's per-turn context, so the scoping agent
 * can reference them instead of asking the user to re-describe what a web search just found.
 */
public class TeamAgentSourcesContextTest {

    @Test
    public void acceptedSourcesAppearInTheModelContext() {
        TeamAgentStateView view = new TeamAgentStateView("scoping", "running",
                Collections.<String>emptyList())
                .withSources("source-1: Wearables market 2024 [ACCEPTED] rev=1\n"
                        + "source-2: Health metrics review [ACCEPTED] rev=1");
        String context = TeamAgentPlaybook.stateContext(view, "", Collections.<String>emptyList(),
                "", Collections.<String>emptyList());

        assertTrue("the source titles are in the context", context.contains("Wearables market 2024"));
        assertTrue("the source ids are in the context", context.contains("source-2"));
        assertTrue("the model is told these are the accepted sources",
                context.contains("Accepted research sources"));
    }

    @Test
    public void withoutSourcesThereIsNoSourcesSection() {
        TeamAgentStateView view = new TeamAgentStateView("scoping", "running",
                Collections.<String>emptyList());
        String context = TeamAgentPlaybook.stateContext(view, "", Collections.<String>emptyList(),
                "", Collections.<String>emptyList());

        assertFalse(context.contains("Accepted research sources"));
        assertTrue("an empty sources summary stays empty", view.getSourcesSummary().isEmpty());
    }
}
