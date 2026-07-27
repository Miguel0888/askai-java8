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
     * settings ("concisely" works well as a group-chat default; the settings field overrides it).
     */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "You are @" + GroupChatBot.DISPLAY_NAME + ", the shared assistant in a local-network group chat "
            + "with several human participants. Messages are prefixed with the sender's name and a colon. "
            + "The participants mostly talk to each other and consult you when needed. "
            + "Answer in the language of the message addressed to you, concisely and in normal Markdown, "
            + "and do not prefix your answer with your own name.";

    /**
     * The built-in explanation of when to chime in for the "always answers" policy; the model
     * declines with {@link #SILENT_MARKER} when it has nothing to add.
     */
    public static final String DEFAULT_ALWAYS_PROMPT =
            "You see every message in the room, not only mentions. Decide yourself whether a reply "
            + "from you adds value: answer direct questions, correct important factual errors, and "
            + "help when the participants seem stuck or ask for ideas. When the participants are "
            + "just talking to each other and you have nothing essential to add, reply with exactly "
            + "[SILENT] and nothing else.";

    /** Exact reply the model uses to stay silent under the always policy. */
    public static final String SILENT_MARKER = "[SILENT]";

    private static final String TRANSCRIPT_MODE_INSTRUCTION =
            "You will receive one message that explicitly mentions you; answer exactly that message "
            + "and do not answer other transcript lines.";

    private static final String TRANSCRIPT_MODE_ALWAYS_INSTRUCTION =
            "You will receive the latest room message.";

    private final OllamaService ollamaService;
    private final Supplier<String> modelName;
    private final Supplier<String> keepAlive;
    private final Supplier<List<String>> mentionableModels;
    private final PartySettings settings;

    public OllamaBotResponder(OllamaService ollamaService, Supplier<String> modelName,
                              Supplier<String> keepAlive) {
        this(ollamaService, modelName, keepAlive, null, null);
    }

    /**
     * @param mentionableModels supplies the installed model names that may be @-mentioned
     *                          directly, or {@code null} when model mentions are disabled
     * @param settings          Partying settings supplying the bot prompts, context mode and
     *                          policy; {@code null} uses the built-in defaults
     */
    public OllamaBotResponder(OllamaService ollamaService, Supplier<String> modelName,
                              Supplier<String> keepAlive, Supplier<List<String>> mentionableModels,
                              PartySettings settings) {
        this.ollamaService = ollamaService;
        this.modelName = modelName;
        this.keepAlive = keepAlive;
        this.mentionableModels = mentionableModels;
        this.settings = settings;
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
                if (isSilent(text)) {
                    callback.onNoAnswer();
                } else if (text.isEmpty()) {
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
        boolean always = settings != null
                && PartySettings.BOT_POLICY_ALWAYS.equals(settings.botPolicy());
        String base = configuredSystemPrompt();
        if (always) {
            base += "\n\n" + configuredAlwaysPrompt();
        }
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
        String system = base + "\n\n"
                + (always ? TRANSCRIPT_MODE_ALWAYS_INSTRUCTION : TRANSCRIPT_MODE_INSTRUCTION);
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
        String custom = settings != null ? settings.botSystemPrompt() : null;
        return custom != null ? custom.trim() : DEFAULT_SYSTEM_PROMPT;
    }

    private String configuredAlwaysPrompt() {
        String custom = settings != null ? settings.botAlwaysPrompt() : null;
        return custom != null ? custom.trim() : DEFAULT_ALWAYS_PROMPT;
    }

    private String configuredContextMode() {
        return settings != null ? settings.botContextMode() : PartySettings.BOT_CONTEXT_TRANSCRIPT;
    }

    /** The model declines by answering exactly (or starting with) the silent marker. */
    private static boolean isSilent(String text) {
        String normalized = text.trim();
        return normalized.equalsIgnoreCase(SILENT_MARKER)
                || normalized.toUpperCase(java.util.Locale.ROOT).startsWith(SILENT_MARKER);
    }

    private static String handleOf(String participantId, Map<String, Participant> profiles) {
        Participant profile = profiles == null ? null : profiles.get(participantId);
        return profile != null ? profile.getMentionHandle() : participantId;
    }
}
