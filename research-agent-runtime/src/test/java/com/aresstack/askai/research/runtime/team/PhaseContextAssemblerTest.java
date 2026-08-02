package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;

/** The per-turn context carries the host-supplied current date, so the model never invents a year. */
public class PhaseContextAssemblerTest {

    @Test
    public void theHostSuppliedCurrentDateIsPartOfTheContext() {
        PhaseContextAssembler assembler = new PhaseContextAssembler(new PhaseContextAssembler.CurrentDate() {
            public String today() {
                return "2026-08-02";
            }
        });
        PhaseAssistantProfile profile = PhaseAssistantProfileRegistry.defaults().forPhase("scoping");
        TeamAgentStateView state = new TeamAgentStateView("scoping", "new", Arrays.<String>asList());

        List<ChatMessage> messages = assembler.assemble(profile, state, "",
                Collections.<String>emptyList(), "", Collections.<String>emptyList(), null);

        boolean found = false;
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.SYSTEM
                    && message.getContent().contains("Current date: 2026-08-02")) {
                found = true;
            }
        }
        assertTrue("the current date is supplied as runtime context", found);
    }
}
