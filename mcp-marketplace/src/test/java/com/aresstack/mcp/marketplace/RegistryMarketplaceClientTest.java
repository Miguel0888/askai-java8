package com.aresstack.mcp.marketplace;

import org.junit.Test;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The registry page URL must percent-encode the pagination cursor with the Java-8 encoding API, so a cursor
 * carrying reserved / non-ASCII characters produces a valid, correctly escaped query.
 */
public final class RegistryMarketplaceClientTest {

    @Test
    public void encodesACursorWithSpecialCharacters() throws IOException {
        String cursor = "page 2+/ä";
        String url = RegistryMarketplaceClient.buildPageUrl("https://registry.example.com", cursor);

        String expectedCursor = URLEncoder.encode(cursor, StandardCharsets.UTF_8.name()); // page+2%2B%2F%C3%A4
        assertEquals("page+2%2B%2F%C3%A4", expectedCursor);
        assertEquals("https://registry.example.com/v0.1/servers?limit=100&cursor=" + expectedCursor, url);
        // The raw reserved characters must NOT appear unescaped in the query.
        assertTrue(url.endsWith("&cursor=page+2%2B%2F%C3%A4"));
    }

    @Test
    public void omitsTheCursorParameterWhenAbsent() throws IOException {
        assertEquals("https://registry.example.com/v0.1/servers?limit=100",
                RegistryMarketplaceClient.buildPageUrl("https://registry.example.com/", null));
        assertEquals("https://registry.example.com/v0.1/servers?limit=100",
                RegistryMarketplaceClient.buildPageUrl("https://registry.example.com", "   "));
    }
}
