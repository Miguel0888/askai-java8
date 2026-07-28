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

    /**
     * Bot participation policies: answer only on explicit mention (default), consider every
     * message and decide itself (paired with the always-prompt), or never answer.
     */
    public static final String BOT_POLICY_MENTION = "mention";
    public static final String BOT_POLICY_ALWAYS = "always";
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
    private static final String KEY_BOT_ALWAYS_PROMPT = "party.botAlwaysPrompt";
    private static final String KEY_BOT_CHIME_GATE = "party.botChimeGate";
    private static final String KEY_BOT_CONTEXT_MODE = "party.botContextMode";

    /**
     * Bot context modes: the users appear as one collective dialogue partner (merged chat turns,
     * default), transcript-as-context (answer the mention), or one chat turn per message.
     */
    public static final String BOT_CONTEXT_COLLECTIVE = "collective";
    public static final String BOT_CONTEXT_TRANSCRIPT = "transcript";
    public static final String BOT_CONTEXT_CONVERSATION = "conversation";
    private static final String KEY_ROOM_ID = "party.roomId";
    private static final String KEY_ROOM_NAME = "party.roomName";
    private static final String KEY_ROOM_SECRET = "party.roomSecret";
    private static final String KEY_HISTORY_DIR = "party.historyDirectory";
    private static final String KEY_HISTORY_AGE_CAP = "party.historyAgeCap";
    private static final String KEY_HISTORY_MAX_AGE_DAYS = "party.historyMaxAgeDays";
    private static final String KEY_HISTORY_SIZE_CAP = "party.historySizeCap";
    private static final String KEY_HISTORY_MAX_SIZE_MB = "party.historyMaxSizeMb";
    private static final String KEY_HISTORY_MAX_RECORD_MB = "party.historyMaxRecordMb";

    /** History retention defaults: age cap on (30 days), size cap on (50 MB), 32 MB per record. */
    public static final int DEFAULT_HISTORY_MAX_AGE_DAYS = 30;
    public static final int DEFAULT_HISTORY_MAX_SIZE_MB = 50;
    public static final int DEFAULT_HISTORY_MAX_RECORD_MB = 32;

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

    /**
     * The bot participation policy; default: see every message and decide via the YES/NO gate
     * with the built-in obviously-false-facts chime-in rules (mentions always answer).
     */
    public String botPolicy() {
        String policy = state == null ? BOT_POLICY_ALWAYS : state.get(KEY_BOT_POLICY, BOT_POLICY_ALWAYS);
        if (BOT_POLICY_OFF.equals(policy)) {
            return BOT_POLICY_OFF;
        }
        return BOT_POLICY_MENTION.equals(policy) ? BOT_POLICY_MENTION : BOT_POLICY_ALWAYS;
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
     * The prompt paired with {@link #BOT_POLICY_ALWAYS} that explains when the bot should chime
     * in unprompted; empty/{@code null} means the built-in default.
     */
    public String botAlwaysPrompt() {
        String prompt = state == null ? null : state.get(KEY_BOT_ALWAYS_PROMPT, null);
        return prompt == null || prompt.trim().isEmpty() ? null : prompt;
    }

    public void setBotAlwaysPrompt(String prompt) {
        put(KEY_BOT_ALWAYS_PROMPT, prompt);
    }

    /**
     * Whether unprompted always-policy replies first run the binary YES/NO chime-in gate.
     * Recommended (and default) for small models; large models that follow the silence contract
     * reliably can disable it and save the extra model call.
     */
    public boolean chimeInGateEnabled() {
        return state == null || state.getBoolean(KEY_BOT_CHIME_GATE, true);
    }

    public void setChimeInGateEnabled(boolean enabled) {
        put(KEY_BOT_CHIME_GATE, String.valueOf(enabled));
    }

    /**
     * How the room context reaches the bot:
     * {@link #BOT_CONTEXT_COLLECTIVE} (default) merges consecutive human messages into one user
     * turn with {@code Name: } prefixes, so the whole group talks to the bot as one collective
     * dialogue partner in clean alternating turns;
     * {@link #BOT_CONTEXT_TRANSCRIPT} puts the room history into the system prompt and the
     * mentioning message as the single user turn;
     * {@link #BOT_CONTEXT_CONVERSATION} turns every room message into its own chat turn.
     */
    public String botContextMode() {
        String mode = state == null ? BOT_CONTEXT_COLLECTIVE
                : state.get(KEY_BOT_CONTEXT_MODE, BOT_CONTEXT_COLLECTIVE);
        if (BOT_CONTEXT_TRANSCRIPT.equals(mode)) {
            return BOT_CONTEXT_TRANSCRIPT;
        }
        return BOT_CONTEXT_CONVERSATION.equals(mode) ? BOT_CONTEXT_CONVERSATION : BOT_CONTEXT_COLLECTIVE;
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

    // ------------------------------------------------------------------ history retention

    /** Whether the age cap is active (default {@code true}). */
    public boolean historyAgeCapEnabled() {
        return state == null || state.getBoolean(KEY_HISTORY_AGE_CAP, true);
    }

    public void setHistoryAgeCapEnabled(boolean enabled) {
        put(KEY_HISTORY_AGE_CAP, String.valueOf(enabled));
    }

    /** Maximum history age in days when the age cap is active. */
    public int historyMaxAgeDays() {
        return intValue(KEY_HISTORY_MAX_AGE_DAYS, DEFAULT_HISTORY_MAX_AGE_DAYS);
    }

    public void setHistoryMaxAgeDays(int days) {
        put(KEY_HISTORY_MAX_AGE_DAYS, String.valueOf(Math.max(1, days)));
    }

    /** Whether the total-size cap is active (default {@code true}). */
    public boolean historySizeCapEnabled() {
        return state == null || state.getBoolean(KEY_HISTORY_SIZE_CAP, true);
    }

    public void setHistorySizeCapEnabled(boolean enabled) {
        put(KEY_HISTORY_SIZE_CAP, String.valueOf(enabled));
    }

    /** Maximum total history size in MB when the size cap is active. */
    public int historyMaxSizeMb() {
        return intValue(KEY_HISTORY_MAX_SIZE_MB, DEFAULT_HISTORY_MAX_SIZE_MB);
    }

    public void setHistoryMaxSizeMb(int mb) {
        put(KEY_HISTORY_MAX_SIZE_MB, String.valueOf(Math.max(1, mb)));
    }

    /** Per-message size guard in MB (default {@value #DEFAULT_HISTORY_MAX_RECORD_MB}). */
    public int historyMaxRecordMb() {
        return intValue(KEY_HISTORY_MAX_RECORD_MB, DEFAULT_HISTORY_MAX_RECORD_MB);
    }

    public void setHistoryMaxRecordMb(int mb) {
        put(KEY_HISTORY_MAX_RECORD_MB, String.valueOf(Math.max(1, mb)));
    }

    /** Build the retention policy from the configured caps. */
    public com.aresstack.askai.java8.groupchat.HistoryRetentionPolicy historyRetentionPolicy() {
        long maxAge = historyAgeCapEnabled() ? historyMaxAgeDays() * 24L * 60L * 60L * 1000L : 0L;
        long maxSize = historySizeCapEnabled() ? historyMaxSizeMb() * 1024L * 1024L : 0L;
        int maxRecord = Math.max(1, historyMaxRecordMb()) * 1024 * 1024;
        return new com.aresstack.askai.java8.groupchat.HistoryRetentionPolicy(maxAge, maxSize, maxRecord);
    }

    private int intValue(String key, int fallback) {
        if (state == null) {
            return fallback;
        }
        try {
            String raw = state.get(key, null);
            return raw == null || raw.trim().isEmpty() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void put(String key, String value) {
        if (state != null) {
            state.putAndSave(key, value == null || value.trim().isEmpty() ? null : value.trim());
        }
    }
}
