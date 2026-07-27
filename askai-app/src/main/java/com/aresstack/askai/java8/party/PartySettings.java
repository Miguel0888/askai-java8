package com.aresstack.askai.java8.party;

import com.aresstack.askai.java8.settings.AskAiPaths;
import com.aresstack.askai.java8.state.ApplicationStateService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Persisted Partying settings: the stable local participant identity, profile preferences and
 * network options, all stored in the {@link ApplicationStateService} under {@code party.*} keys.
 *
 * <p>The participant ID is generated once per installation and never derived from an IP
 * address.</p>
 */
public final class PartySettings {

    /** Bot participation policies: answer only on explicit mention, or never. */
    public static final String BOT_POLICY_MENTION = "mention";
    public static final String BOT_POLICY_OFF = "off";

    private static final String KEY_PARTICIPANT_ID = "party.participantId";
    private static final String KEY_DISPLAY_NAME = "party.displayName";
    private static final String KEY_PREFERRED_COLOR = "party.preferredColor";
    private static final String KEY_DISCOVERY = "party.discovery";
    private static final String KEY_INTERFACE = "party.networkInterface";
    private static final String KEY_MANUAL_PEERS = "party.manualPeers";
    private static final String KEY_BOT_POLICY = "party.botPolicy";
    private static final String KEY_MODEL_MENTIONS = "party.modelMentions";
    private static final String KEY_BOT_SYSTEM_PROMPT = "party.botSystemPrompt";
    private static final String KEY_BOT_CONTEXT_MODE = "party.botContextMode";

    /** Bot context modes: transcript-as-context (answer the mention) or full chat turns. */
    public static final String BOT_CONTEXT_TRANSCRIPT = "transcript";
    public static final String BOT_CONTEXT_CONVERSATION = "conversation";
    private static final String KEY_ROOM_ID = "party.roomId";
    private static final String KEY_ROOM_NAME = "party.roomName";
    private static final String KEY_ROOM_SECRET = "party.roomSecret";
    private static final String KEY_HISTORY_DIR = "party.historyDirectory";

    private final ApplicationStateService state;

    public PartySettings(ApplicationStateService state) {
        this.state = state;
    }

    /** Stable installation-scoped participant identity; created and persisted on first use. */
    public String participantId() {
        if (state == null) {
            return "local";
        }
        String id = state.get(KEY_PARTICIPANT_ID, null);
        if (id == null || id.trim().isEmpty()) {
            id = UUID.randomUUID().toString();
            state.putAndSave(KEY_PARTICIPANT_ID, id);
        }
        return id;
    }

    /** Public display name; defaults to a friendly abbreviation of the participant ID. */
    public String displayName() {
        String name = state == null ? null : state.get(KEY_DISPLAY_NAME, null);
        if (name == null || name.trim().isEmpty()) {
            String id = participantId();
            name = "User-" + id.substring(0, Math.min(6, id.length()));
        }
        return name;
    }

    public void setDisplayName(String name) {
        put(KEY_DISPLAY_NAME, name);
    }

    /** Preferred palette color token, or {@code null} when no preference is set. */
    public String preferredColor() {
        String token = state == null ? null : state.get(KEY_PREFERRED_COLOR, null);
        return token == null || token.trim().isEmpty() ? null : token;
    }

    public void setPreferredColor(String token) {
        put(KEY_PREFERRED_COLOR, token);
    }

    /** Whether automatic UDP/multicast LAN discovery is enabled (default {@code true}). */
    public boolean discoveryEnabled() {
        return state == null || state.getBoolean(KEY_DISCOVERY, true);
    }

    public void setDiscoveryEnabled(boolean enabled) {
        put(KEY_DISCOVERY, String.valueOf(enabled));
    }

    /** Network interface name to bind, or {@code null} for automatic selection. */
    public String networkInterface() {
        String value = state == null ? null : state.get(KEY_INTERFACE, null);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public void setNetworkInterface(String name) {
        put(KEY_INTERFACE, name);
    }

    /** Manual peer addresses ({@code host} or {@code host:port}) for blocked-multicast networks. */
    public List<String> manualPeers() {
        String raw = state == null ? null : state.get(KEY_MANUAL_PEERS, null);
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> peers = new ArrayList<String>();
        for (String part : raw.split("[,;\\s]+")) {
            if (!part.trim().isEmpty()) {
                peers.add(part.trim());
            }
        }
        return peers;
    }

    public void setManualPeers(String commaSeparated) {
        put(KEY_MANUAL_PEERS, commaSeparated);
    }

    /** Raw manual peers text for the settings field. */
    public String manualPeersText() {
        return state == null ? "" : state.get(KEY_MANUAL_PEERS, "");
    }

    /** The bot participation policy; default: answer only when explicitly mentioned. */
    public String botPolicy() {
        String policy = state == null ? BOT_POLICY_MENTION : state.get(KEY_BOT_POLICY, BOT_POLICY_MENTION);
        return BOT_POLICY_OFF.equals(policy) ? BOT_POLICY_OFF : BOT_POLICY_MENTION;
    }

    public void setBotPolicy(String policy) {
        put(KEY_BOT_POLICY, policy);
    }

    /**
     * Whether the bot can also be addressed by full model name (e.g. {@code @gemma4:e2b}) in
     * addition to {@code @AskAI}; the mentioned model then answers. Default {@code true}.
     */
    public boolean modelMentionsEnabled() {
        return state == null || state.getBoolean(KEY_MODEL_MENTIONS, true);
    }

    public void setModelMentionsEnabled(boolean enabled) {
        put(KEY_MODEL_MENTIONS, String.valueOf(enabled));
    }

    /**
     * Custom system prompt for the party bot; empty/{@code null} means the built-in default.
     */
    public String botSystemPrompt() {
        String prompt = state == null ? null : state.get(KEY_BOT_SYSTEM_PROMPT, null);
        return prompt == null || prompt.trim().isEmpty() ? null : prompt;
    }

    public void setBotSystemPrompt(String prompt) {
        put(KEY_BOT_SYSTEM_PROMPT, prompt);
    }

    /**
     * How the room context reaches the bot: {@link #BOT_CONTEXT_TRANSCRIPT} (default; the
     * transcript goes into the system prompt and the bot answers exactly the mentioning message)
     * or {@link #BOT_CONTEXT_CONVERSATION} (every room message becomes a chat turn prefixed with
     * the sender's name, and the model draws its own conclusions).
     */
    public String botContextMode() {
        String mode = state == null ? BOT_CONTEXT_TRANSCRIPT
                : state.get(KEY_BOT_CONTEXT_MODE, BOT_CONTEXT_TRANSCRIPT);
        return BOT_CONTEXT_CONVERSATION.equals(mode) ? BOT_CONTEXT_CONVERSATION : BOT_CONTEXT_TRANSCRIPT;
    }

    public void setBotContextMode(String mode) {
        put(KEY_BOT_CONTEXT_MODE, mode);
    }

    /** Stable room identifier (defaults to the shared default room). */
    public String roomId() {
        return state == null ? "askai.default" : state.get(KEY_ROOM_ID, "askai.default");
    }

    public void setRoomId(String roomId) {
        put(KEY_ROOM_ID, roomId);
    }

    /** Human-readable room name. */
    public String roomName() {
        return state == null ? "AskAI Party" : state.get(KEY_ROOM_NAME, "AskAI Party");
    }

    public void setRoomName(String name) {
        put(KEY_ROOM_NAME, name);
    }

    /** Room / invitation secret; joining is authenticated with it and traffic encrypted from it. */
    public String roomSecret() {
        return state == null ? "" : state.get(KEY_ROOM_SECRET, "");
    }

    public void setRoomSecret(String secret) {
        put(KEY_ROOM_SECRET, secret);
    }

    /** Directory holding the local append-only room history logs. */
    public File historyDirectory() {
        String custom = state == null ? null : state.get(KEY_HISTORY_DIR, null);
        if (custom != null && !custom.trim().isEmpty()) {
            return new File(custom.trim());
        }
        return AskAiPaths.appDirectory().resolve("party-history").toFile();
    }

    public void setHistoryDirectory(String path) {
        put(KEY_HISTORY_DIR, path);
    }

    private void put(String key, String value) {
        if (state != null) {
            state.putAndSave(key, value == null || value.trim().isEmpty() ? null : value.trim());
        }
    }
}
