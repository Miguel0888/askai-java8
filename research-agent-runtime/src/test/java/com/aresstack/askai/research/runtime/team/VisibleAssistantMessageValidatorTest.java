package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The hard gate that keeps codec/transport meta-talk out of the visible chat. */
public class VisibleAssistantMessageValidatorTest {

    @Test
    public void warmScopingRepliesAreClean() {
        assertTrue(VisibleAssistantMessageValidator.isCleanBusinessMessage(
                "Got it — you want to explore wearables. Which aspects matter most to you?"));
        assertTrue("everyday words are not banned", VisibleAssistantMessageValidator.isCleanBusinessMessage(
                "You clearly have a good command of the topic; formatting the report comes later."));
    }

    @Test
    public void codecMetaTalkIsRejected() {
        assertFalse(VisibleAssistantMessageValidator.isCleanBusinessMessage(
                "I apologize if my previous response was not formatted correctly."));
        assertFalse(VisibleAssistantMessageValidator.isCleanBusinessMessage(
                "Please respond with one valid JSON object matching the schema."));
        assertFalse(VisibleAssistantMessageValidator.isCleanBusinessMessage(
                "Ready to proceed with the structured research."));
    }

    @Test
    public void blankIsNeverClean() {
        assertFalse(VisibleAssistantMessageValidator.isCleanBusinessMessage(null));
        assertFalse(VisibleAssistantMessageValidator.isCleanBusinessMessage("   "));
    }
}
