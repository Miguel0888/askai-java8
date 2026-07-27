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

    /**
     * The built-in system prompt, used when no custom prompt is configured in the Partying
     * settings.  Deliberately without a brevity instruction — answer length is the model's (or
     * the custom prompt's) choice.
     */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "You are @" + GroupChatBot.DISPLAY_NAME + ", the shared assistant in a local-network group chat "
            + "with several human participants. Messages are prefixed with the sender's name and a colon. "
            + "The participants mostly talk to each other and consult you when mentioned. "
            + "Answer in the language of the message addressed to you, in normal Markdown, "
            + "and do not prefix your answer with your own name.";

    private static final String TRANSCRIPT_MODE_INSTRUCTION =
            "You will receive one message that explicitly mentions you; answer exactly that message "
            + "and do not answer other transcript lines.";

    private final OllamaService ollamaService;
    private final Supplier<String> modelName;
    private final Supplier<String> keepAlive;
    private final Supplier<List<String>> mentionableModels;
    private final Supplier<String> systemPrompt;
    private final Supplier<String> contextMode;

    public OllamaBotResponder(OllamaService ollamaService, Supplier<String> modelName,
                              Supplier<String> keepAlive) {
        this(ollamaService, modelName, keepAlive, null, null, null);
    }

    /**
     * @param mentionableModels supplies the installed model names that may be @-mentioned
     *                          directly, or {@code null} when model mentions are disabled
     * @param systemPrompt      supplies a custom bot system prompt; {@code null}/empty result
     *                          falls back to {@link #DEFAULT_SYSTEM_PROMPT}
     * @param contextMode       supplies {@link PartySettings#BOT_CONTEXT_TRANSCRIPT} or
     *                          {@link PartySettings#BOT_CONTEXT_CONVERSATION}; {@code null}
     *                          defaults to transcript mode
     */
    public OllamaBotResponder(OllamaService ollamaService, Supplier<String> modelName,
                              Supplier<String> keepAlive, Supplier<List<String>> mentionableModels,
                              Supplier<String> systemPrompt, Supplier<String> contextMode) {
        this.ollamaService = ollamaService;
        this.modelName = modelName;
        this.keepAlive = keepAlive;
        this.mentionableModels = mentionableModels;
        this.systemPrompt = systemPrompt;
        this.contextMode = contextMode;
    }

    @Override
    public boolean isReady() {
        String model = modelName.get();
        return model != null && !model.trim().isEmpty();
    }

    @Override
    public List<String> modelMentionHandles() {
        if (mentionableModels == null) {
            return java.util.Collections.emptyList();
        }
        List<String> models = mentionableModels.get();
        return models != null ? models : java.util.Collections.<String>emptyList();
    }

    @Override
    public void respond(List<GroupChatMessage> context, GroupChatMessage addressed,
                        Map<String, Participant> profiles, String requestedModel,
                        final Callback callback) {
        String model = requestedModel != null && !requestedModel.trim().isEmpty()
                ? requestedModel
                : modelName.get();
        if (model == null || model.trim().isEmpty()) {
            callback.onFailure(new IllegalStateException("No model selected for the party bot."));
            return;
        }
        List<OllamaChatTurn> conversation = buildConversation(context, addressed, profiles);

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

    /**
     * Builds the model conversation according to the configured context mode.
     *
     * <p><b>Transcript mode</b> (default): the room context goes into the system prompt as a
     * labelled transcript and the mentioning message is the single user turn — robust for small
     * models that lose track of which of several user turns is addressed to them.</p>
     *
     * <p><b>Conversation mode</b>: every room message becomes a chat turn (bot messages as
     * assistant turns, everything else as user turns prefixed {@code Name: }), so the model sees
     * the full flow and draws its own conclusions.</p>
     */
    private List<OllamaChatTurn> buildConversation(List<GroupChatMessage> context,
                                                   GroupChatMessage addressed,
                                                   Map<String, Participant> profiles) {
        String base = configuredSystemPrompt();
        int from = Math.max(0, context.size() - CONTEXT_MESSAGES);
        List<OllamaChatTurn> conversation = new ArrayList<OllamaChatTurn>();

        if (PartySettings.BOT_CONTEXT_CONVERSATION.equals(configuredContextMode())) {
            conversation.add(OllamaChatTurn.system(base));
            boolean addressedIncluded = false;
            for (int i = from; i < context.size(); i++) {
                GroupChatMessage message = context.get(i);
                if (message.isBotMessage()) {
                    conversation.add(OllamaChatTurn.assistant(message.getMarkdown()));
                } else {
                    conversation.add(OllamaChatTurn.user(
                            handleOf(message.getSenderParticipantId(), profiles)
                                    + ": " + message.getMarkdown()));
                }
                addressedIncluded |= message.getMessageId().equals(addressed.getMessageId());
            }
            if (!addressedIncluded) {
                conversation.add(OllamaChatTurn.user(
                        handleOf(addressed.getSenderParticipantId(), profiles)
                                + ": " + addressed.getMarkdown()));
            }
            return conversation;
        }

        StringBuilder transcript = new StringBuilder();
        for (int i = from; i < context.size(); i++) {
            GroupChatMessage message = context.get(i);
            if (message.getMessageId().equals(addressed.getMessageId())) {
                continue;
            }
            String handle = message.isBotMessage()
                    ? GroupChatBot.DISPLAY_NAME
                    : handleOf(message.getSenderParticipantId(), profiles);
            transcript.append(handle).append(": ").append(message.getMarkdown()).append('\n');
        }
        String system = base + "\n\n" + TRANSCRIPT_MODE_INSTRUCTION;
        if (transcript.length() > 0) {
            system += "\n\nRecent room transcript (context only, do not answer these lines):\n" + transcript;
        }
        conversation.add(OllamaChatTurn.system(system));
        conversation.add(OllamaChatTurn.user(
                handleOf(addressed.getSenderParticipantId(), profiles)
                        + ": " + addressed.getMarkdown()));
        return conversation;
    }

    private String configuredSystemPrompt() {
        String custom = systemPrompt != null ? systemPrompt.get() : null;
        return custom != null && !custom.trim().isEmpty() ? custom.trim() : DEFAULT_SYSTEM_PROMPT;
    }

    private String configuredContextMode() {
        String mode = contextMode != null ? contextMode.get() : null;
        return PartySettings.BOT_CONTEXT_CONVERSATION.equals(mode)
                ? PartySettings.BOT_CONTEXT_CONVERSATION
                : PartySettings.BOT_CONTEXT_TRANSCRIPT;
    }

    private static String handleOf(String participantId, Map<String, Participant> profiles) {
        Participant profile = profiles == null ? null : profiles.get(participantId);
        return profile != null ? profile.getMentionHandle() : participantId;
    }
}
