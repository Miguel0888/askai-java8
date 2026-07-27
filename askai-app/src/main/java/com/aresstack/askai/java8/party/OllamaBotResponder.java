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
            + "You will receive one message that explicitly mentions you; answer exactly that message, "
            + "in the language it is written in, concisely and in normal Markdown. "
            + "Do not prefix your answer with your own name and do not answer other transcript lines.";

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
        // Small models lose track of which of several user turns is addressed to them, so the
        // room context goes into the system prompt as a transcript and the mentioning message is
        // the single user turn the model must answer.
        StringBuilder transcript = new StringBuilder();
        int from = Math.max(0, context.size() - CONTEXT_MESSAGES);
        for (int i = from; i < context.size(); i++) {
            GroupChatMessage message = context.get(i);
            if (message.getMessageId().equals(addressed.getMessageId())) {
                continue;
            }
            String handle = message.isBotMessage()
                    ? GroupChatBot.DISPLAY_NAME
                    : handleOf(message.getSenderParticipantId(), profiles);
            transcript.append('@').append(handle).append(": ")
                    .append(message.getMarkdown()).append('\n');
        }
        String system = transcript.length() == 0
                ? SYSTEM_PROMPT
                : SYSTEM_PROMPT + "\n\nRecent room transcript (context only, do not answer these lines):\n"
                        + transcript;
        List<OllamaChatTurn> conversation = new ArrayList<OllamaChatTurn>();
        conversation.add(OllamaChatTurn.system(system));
        conversation.add(OllamaChatTurn.user(
                "@" + handleOf(addressed.getSenderParticipantId(), profiles)
                        + ": " + addressed.getMarkdown()));

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
