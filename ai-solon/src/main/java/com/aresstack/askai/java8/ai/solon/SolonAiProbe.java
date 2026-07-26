package com.aresstack.askai.java8.ai.solon;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.Arrays;
import java.util.List;

/**
 * Compile-time proof that Solon AI resolves and its API is usable on this Java 8 build. It references the
 * real Solon AI types this project will build the runtime on ({@link ChatModel} and the role-based message
 * model) without any network call. It is intentionally trivial and will be replaced by the real
 * {@code SolonChatRuntime} in the Slice 2 work.
 */
public final class SolonAiProbe {

    private SolonAiProbe() {
    }

    /** @return the fully-qualified name of Solon AI's ChatModel — confirms the type is on the classpath. */
    public static String chatModelType() {
        return ChatModel.class.getName();
    }

    /** @return a small role-based conversation, proving Solon AI's message model compiles on Java 8. */
    public static List<ChatMessage> sampleConversation() {
        return Arrays.<ChatMessage>asList(
                ChatMessage.ofSystem("You are a concise local assistant."),
                ChatMessage.ofUser("Hello"),
                ChatMessage.ofAssistant("Hi — how can I help?"));
    }
}
