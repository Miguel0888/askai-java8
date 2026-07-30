package com.aresstack.askai.research.runtime.team;

/**
 * The typed outcome of one main-model chat call. Success carries the assistant's raw text; every failure is
 * NAMED (transport, timeout, HTTP, empty answer) and never fabricated as a success — the TeamAgent turns a
 * non-OK result into an honest {@code MODEL_UNAVAILABLE} status for the user, offering a retry.
 */
public final class MainModelChatResult {

    public enum Status {
        OK,
        TIMEOUT,
        PROVIDER_FAILURE,
        INVALID_RESPONSE
    }

    private final Status status;
    private final String text;
    private final String detail;

    private MainModelChatResult(Status status, String text, String detail) {
        this.status = status;
        this.text = text == null ? "" : text;
        this.detail = detail == null ? "" : detail;
    }

    public static MainModelChatResult ok(String text) {
        return new MainModelChatResult(Status.OK, text, "");
    }

    public static MainModelChatResult failure(Status status, String detail) {
        return new MainModelChatResult(status, "", detail);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    /** The assistant's raw text (empty on any failure). */
    public String getText() {
        return text;
    }

    /** A human-readable, secret-free reason for a non-OK status. */
    public String getDetail() {
        return detail;
    }
}
