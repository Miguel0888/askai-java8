package com.aresstack.askai.java8.ui;

/**
 * Semantic state of the connection to the Ollama server, so the UI can show "Connected" /
 * "Not reachable" instead of leaving the user to interpret a bare URL. Swing-free on purpose (only a
 * label and an RGB colour) so the mapping is unit-testable without a display.
 */
public enum ConnectionStatus {

    NOT_CHECKED("Not checked", 0x757575),
    CONNECTING("Connecting…", 0xF9A825),
    CONNECTED("Connected", 0x2E7D32),
    NOT_REACHABLE("Not reachable", 0xC62828),
    ERROR("Error", 0xC62828);

    private final String label;
    private final int colorRgb;

    ConnectionStatus(String label, int colorRgb) {
        this.label = label;
        this.colorRgb = colorRgb;
    }

    public String getLabel() {
        return label;
    }

    public int getColorRgb() {
        return colorRgb;
    }

    /** @return {@link #CONNECTED} when the server returned a non-empty version, else {@link #NOT_REACHABLE}. */
    public static ConnectionStatus forVersion(String version) {
        return version != null && version.trim().length() > 0 ? CONNECTED : NOT_REACHABLE;
    }
}
