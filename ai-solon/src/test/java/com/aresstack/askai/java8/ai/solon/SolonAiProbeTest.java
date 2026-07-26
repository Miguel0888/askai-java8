package com.aresstack.askai.java8.ai.solon;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Confirms Solon AI is on the classpath and its role-based message model works on this Java 8 build. */
public class SolonAiProbeTest {

    @Test
    public void solonChatModelTypeIsResolvable() {
        assertEquals("org.noear.solon.ai.chat.ChatModel", SolonAiProbe.chatModelType());
    }

    @Test
    public void solonMessageModelBuildsAConversation() {
        List<?> conversation = SolonAiProbe.sampleConversation();
        assertEquals(3, conversation.size());
        assertTrue(conversation.get(0).getClass().getName().startsWith("org.noear.solon.ai.chat.message."));
    }
}
