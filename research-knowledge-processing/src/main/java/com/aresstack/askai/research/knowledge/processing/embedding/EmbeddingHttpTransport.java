package com.aresstack.askai.research.knowledge.processing.embedding;

import java.io.IOException;

/**
 * The narrow HTTP seam the {@link HttpEmbeddingPortAdapter} uses: POST a JSON body to a URL, return the raw
 * response body. Isolated so the adapter's request-building, response-parsing and validation are unit-testable
 * without a live server. The productive {@link UrlConnectionEmbeddingHttpTransport} uses {@code HttpURLConnection}.
 */
public interface EmbeddingHttpTransport {

    /** @return the response body on HTTP 200. @throws IOException on transport failure or a non-200 status. */
    String post(String url, String jsonBody) throws IOException;
}
