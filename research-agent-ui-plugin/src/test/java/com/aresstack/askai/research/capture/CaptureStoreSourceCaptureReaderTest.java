package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The productive SourceCaptureReader maps a stored capture to the canonical SourceCapture (lossless paragraph
 * blocks), resolves the owning source id by canonical URL, and returns null for an unknown capture.
 */
public class CaptureStoreSourceCaptureReaderTest {

    @Test
    public void readsAStoredCaptureAsACanonicalSourceCaptureWithResolvedSourceId() {
        CaptureStore captures = new CaptureStore(10, 123L);
        VisitedCapture visited = captures.record("https://Example.com/a?utm_source=x#frag", "Title",
                "First paragraph.\n\nSecond paragraph.");

        InMemoryResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        repo.put(ResearchSourceRecord.builder("source-7").url("https://example.com/a").build());
        CaptureStoreSourceCaptureReader reader =
                new CaptureStoreSourceCaptureReader(captures, new CanonicalUrlSourceIdResolver(repo));

        SourceCapture capture = reader.read(visited.getCaptureId());
        assertEquals("src provenance resolved via canonical URL", "source-7", capture.getSourceId());
        assertEquals(visited.getCaptureId(), capture.getCaptureId());
        assertEquals(2, capture.getBlocks().size());
        assertEquals("First paragraph.", capture.getBlocks().get(0).getText());
        assertEquals("Second paragraph.", capture.getBlocks().get(1).getText());
    }

    @Test
    public void anUnknownCaptureIdIsNull() {
        CaptureStore captures = new CaptureStore(10, 123L);
        CaptureStoreSourceCaptureReader reader =
                new CaptureStoreSourceCaptureReader(captures, CaptureStoreSourceCaptureReader.SourceIdResolver.NONE);
        assertNull(reader.read("cap-does-not-exist"));
    }

    @Test
    public void anUnlinkedCaptureGetsAnEmptySourceId() {
        CaptureStore captures = new CaptureStore(10, 123L);
        VisitedCapture visited = captures.record("https://nowhere.test/x", "T", "Body text.");
        CaptureStoreSourceCaptureReader reader = new CaptureStoreSourceCaptureReader(captures,
                new CanonicalUrlSourceIdResolver(new InMemoryResearchSourceRepository()));

        SourceCapture capture = reader.read(visited.getCaptureId());
        assertEquals("", capture.getSourceId());
    }
}
