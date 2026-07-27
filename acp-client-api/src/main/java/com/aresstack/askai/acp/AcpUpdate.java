package com.aresstack.askai.acp;

/** One streamed update, attributed by sessionId + promptId and ordered by a monotonic sequenceNumber. */
public final class AcpUpdate {

    public enum Kind { MESSAGE, THOUGHT, OTHER }

    private final String sessionId;
    private final String promptId;
    private final long sequenceNumber;
    private final Kind kind;
    private final String text;

    public AcpUpdate(String sessionId, String promptId, long sequenceNumber, Kind kind, String text) {
        this.sessionId = sessionId;
        this.promptId = promptId;
        this.sequenceNumber = sequenceNumber;
        this.kind = kind == null ? Kind.OTHER : kind;
        this.text = text == null ? "" : text;
    }

    public String getSessionId() { return sessionId; }
    public String getPromptId() { return promptId; }
    public long getSequenceNumber() { return sequenceNumber; }
    public Kind getKind() { return kind; }
    public String getText() { return text; }
}
