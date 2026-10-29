package com.aresstack.askai.research.sources;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Selecting a source publishes it (the contribution shows its full text in the main area). */
public class SourceFullTextViewTest {

    @Test
    public void selectingASourcePublishesItAndTheReaderShowsItsFullText() throws Exception {
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        repo.put(ResearchSourceRecord.builder("s-1").title("Hares").url("https://example.org/hares")
                .revision(1L).fullText("Hares are fast.").build());
        final List<ResearchSourceRecord> published = new ArrayList<ResearchSourceRecord>();
        final AtomicReference<String> shown = new AtomicReference<String>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchSourcesView view = new ResearchSourcesView(repo, ResearchSourcesView.demoKnownSections());
                view.setSelectionPublisher(published::add);
                view.focusSource("s-1");
                SourceFullTextView reader = new SourceFullTextView();
                if (!published.isEmpty() && published.get(published.size() - 1) != null) {
                    reader.show(published.get(published.size() - 1));
                }
                shown.set(reader.shownText());
            }
        });
        assertTrue("the selection was published", !published.isEmpty());
        assertEquals("s-1", published.get(published.size() - 1).getSourceId());
        assertEquals("Hares are fast.", shown.get());
    }
}
