package com.aresstack.mcp.marketplace;

/** Receives recoverable marketplace diagnostics without binding the core to a logging framework. */
public interface MarketplaceLog {
    void warn(String message, Throwable cause);

    MarketplaceLog NO_OP = new MarketplaceLog() {
        @Override
        public void warn(String message, Throwable cause) {
            // Ignore diagnostics when the embedding application provides no logger.
        }
    };
}
