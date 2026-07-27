package com.aresstack.askai.java8.party;

import com.aresstack.askai.java8.groupchat.GroupChatMessage;
import com.aresstack.askai.java8.groupchat.Participant;

import java.util.List;
import java.util.Map;

/**
 * Port through which the party bot obtains a model response, decoupling group chat from the
 * concrete chat runtime (Ollama today).  Implementations must be asynchronous and call back
 * exactly once.
 */
public interface BotResponder {

    /** Delivered exactly once per {@link #respond} call. */
    interface Callback {
        void onResponse(String markdown);

        void onFailure(Exception error);
    }

    /** @return {@code true} when this peer could answer right now (model selected + reachable). */
    boolean isReady();

    /**
     * Produce the bot answer for {@code addressed} given the recent room context.
     *
     * @param context   recent room messages, oldest first (including {@code addressed})
     * @param addressed the {@code @AskAI}-mentioning message to answer
     * @param profiles  participantId → profile, for labelling senders in the prompt
     */
    void respond(List<GroupChatMessage> context, GroupChatMessage addressed,
                 Map<String, Participant> profiles, Callback callback);
}
