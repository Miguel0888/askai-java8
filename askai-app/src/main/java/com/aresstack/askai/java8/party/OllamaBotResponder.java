package com.aresstack.askai.java8.party;

import com.aresstack.askai.java8.client.OllamaChatTurn;
import com.aresstack.askai.java8.groupchat.GroupChatBot;
import com.aresstack.askai.java8.groupchat.GroupChatMessage;
import com.aresstack.askai.java8.groupchat.Participant;
import com.aresstack.askai.java8.service.OllamaService;
import com.aresstack.askai.java8.service.ThinkingOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Routes party-bot requests through the existing AskAI chat runtime ({@link OllamaService})
 * instead of coupling group chat directly to Ollama.
 */
public final class OllamaBotResponder implements BotResponder {

    /** How many trailing room messages are given to the model as conversation context. */
    private static final int CONTEXT_MESSAGES = 20;

    private static final String SYSTEM_PROMPT =
            "You are @" + GroupChatBot.DISPLAY_NAME + ", the shared assistant in a local-network group chat. "
            + "Messages are prefixed with the sender's @handle. Answer the last message that mentioned you, "
            + "concisely and in normal Markdown. Do not prefix your answer with your own name.";

    private final OllamaService ollamaService;
    private final Supplier<String> modelName;
    private final Supplier<String> keepAlive;

    public OllamaBotResponder(OllamaService ollamaService, Supplier<String> modelName,
                              Supplier<String> keepAlive) {
        this.ollamaService = ollamaService;
        this.modelName = modelName;
        this.keepAlive = keepAlive;
    }

    @Override
    public boolean isReady() {
        String model = modelName.get();
        return model != null && !model.trim().isEmpty();
    }

    @Override
    public void respond(List<GroupChatMessage> context, GroupChatMessage addressed,
                        Map<String, Participant> profiles, final Callback callback) {
        String model = modelName.get();
        if (model == null || model.trim().isEmpty()) {
            callback.onFailure(new IllegalStateException("No model selected for the party bot."));
            return;
        }
        List<OllamaChatTurn> conversation = new ArrayList<OllamaChatTurn>();
        conversation.add(OllamaChatTurn.system(SYSTEM_PROMPT));
        int from = Math.max(0, context.size() - CONTEXT_MESSAGES);
        for (int i = from; i < context.size(); i++) {
            GroupChatMessage message = context.get(i);
            if (message.isBotMessage()) {
                conversation.add(OllamaChatTurn.assistant(message.getMarkdown()));
            } else {
                conversation.add(OllamaChatTurn.user(
                        "@" + handleOf(message.getSenderParticipantId(), profiles)
                                + ": " + message.getMarkdown()));
            }
        }

        final StringBuilder answer = new StringBuilder();
        final AtomicBoolean done = new AtomicBoolean(false);
        OllamaService.ChatRequest request = new OllamaService.ChatRequest(
                model, keepAlive.get(), conversation, ThinkingOption.defaultOption());
        ollamaService.streamChat(request, new OllamaService.ChatListener() {
            public void onThinkingDelta(String delta) {
                // The party bot publishes only the final answer, never its thinking.
            }

            public void onContent(String content) {
                if (content != null) {
                    answer.append(content);
                }
            }

            public void onStatus(String status) {
            }

            public void onComplete(OllamaService.ChatResult result) {
                if (!done.compareAndSet(false, true)) {
                    return;
                }
                String text = answer.toString().trim();
                if (text.isEmpty() && result != null && !result.getFallbackText().isEmpty()) {
                    text = result.getFallbackText().trim();
                }
                if (text.isEmpty()) {
                    callback.onFailure(new IllegalStateException("The model returned no answer."));
                } else {
                    callback.onResponse(text);
                }
            }

            public void onError(Exception ex) {
                if (done.compareAndSet(false, true)) {
                    callback.onFailure(ex);
                }
            }
        });
    }

    private static String handleOf(String participantId, Map<String, Participant> profiles) {
        Participant profile = profiles == null ? null : profiles.get(participantId);
        return profile != null ? profile.getMentionHandle() : participantId;
    }
}
