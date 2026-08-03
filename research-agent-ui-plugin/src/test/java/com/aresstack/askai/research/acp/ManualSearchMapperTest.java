package com.aresstack.askai.research.acp;

import com.aresstack.askai.acp.AcpUpdate;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;

import org.junit.Test;

import java.net.URLEncoder;

import static org.junit.Assert.assertEquals;

/** The host decodes {@code #RSX1# manual_search_*} wire lines into typed MANUAL_SEARCH events. */
public class ManualSearchMapperTest {

    private static AcpUpdate message(String text) {
        return new AcpUpdate("s1", "p1", 1L, AcpUpdate.Kind.MESSAGE, text);
    }

    @Test
    public void startedCarriesTheDecodedQueryAndRequestId() throws Exception {
        String line = "#RSX1# manual_search_started request_id=R1 query="
                + URLEncoder.encode("wearables audio", "UTF-8");
        ResearchBackendEvent e = ResearchAcpEventMapper.mapUpdate(message(line)).build();
        assertEquals(ResearchBackendEventType.MANUAL_SEARCH, e.getType());
        assertEquals("started", e.getTitle());
        assertEquals("R1", e.getTechnicalDetail());
        assertEquals("Websuche: wearables audio", e.getText());
        assertEquals("manual-search-R1", e.getActivityId());
    }

    @Test
    public void completedReportsTheResultCount() {
        String line = "#RSX1# manual_search_completed request_id=R1 results=3 status=RESULTS";
        ResearchBackendEvent e = ResearchAcpEventMapper.mapUpdate(message(line)).build();
        assertEquals(ResearchBackendEventType.MANUAL_SEARCH, e.getType());
        assertEquals("completed", e.getTitle());
        assertEquals("R1", e.getTechnicalDetail());
        assertEquals("3 Treffer", e.getText());
    }

    @Test
    public void anUnavailableFailureIsUserReadable() {
        String line = "#RSX1# manual_search_failed request_id=R1 reason=SEARCH_UNAVAILABLE";
        ResearchBackendEvent e = ResearchAcpEventMapper.mapUpdate(message(line)).build();
        assertEquals("failed", e.getTitle());
        assertEquals("Websuche nicht verfügbar.", e.getText());
    }
}
