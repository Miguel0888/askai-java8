package com.aresstack.askai.research.connector;

/** A typed OAuth protocol error: HTTP status + RFC 6749 error code (+ optional human description). */
public final class OAuthError extends RuntimeException {

    private final int statusCode;
    private final String error;
    private final String errorDescription;

    public OAuthError(int statusCode, String error) {
        this(statusCode, error, null);
    }

    public OAuthError(int statusCode, String error, String errorDescription) {
        super(error + (errorDescription == null ? "" : ": " + errorDescription));
        this.statusCode = statusCode;
        this.error = error;
        this.errorDescription = errorDescription;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getError() {
        return error;
    }

    public String getErrorDescription() {
        return errorDescription;
    }
}
