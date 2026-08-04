package com.aresstack.askai.research.store;

import com.aresstack.askai.research.backend.ScopingAssistantUpdate;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The scoping projection (yellow tags) is persisted per project so it survives a restart. */
public class FileScopingProjectionStoreTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void savedProjectionRoundTrips() throws Exception {
        FileScopingProjectionStore store = new FileScopingProjectionStore(tmp.newFolder("scoping"));
        ScopingAssistantUpdate projection = new ScopingAssistantUpdate("scoping",
                Arrays.asList(
                        new ScopingAssistantUpdate.Suggestion("current wearable technology trends 2026",
                                "map the field", 1),
                        new ScopingAssistantUpdate.Suggestion("neuroscience wearable technology applications",
                                "narrow to neuro", 2)),
                "CONTINUE", "enough breadth to proceed");

        store.save(projection);
        ScopingAssistantUpdate back = store.load();

        assertEquals("scoping", back.getPhaseId());
        assertEquals("CONTINUE", back.getAdviceRecommendation());
        assertEquals("enough breadth to proceed", back.getAdviceReason());
        assertEquals(2, back.getSearchSuggestions().size());
        assertEquals("current wearable technology trends 2026",
                back.getSearchSuggestions().get(0).getQuery());
        assertEquals("narrow to neuro", back.getSearchSuggestions().get(1).getPurpose());
        assertEquals(2, back.getSearchSuggestions().get(1).getPriority());
    }

    @Test
    public void loadReturnsNullWhenNothingPersisted() throws Exception {
        FileScopingProjectionStore store = new FileScopingProjectionStore(tmp.newFolder("empty"));
        assertNull(store.load());
    }

    @Test
    public void aProjectionWithNoSuggestionsIsNotWorthRestoring() throws Exception {
        FileScopingProjectionStore store = new FileScopingProjectionStore(tmp.newFolder("nosugg"));
        store.save(new ScopingAssistantUpdate("scoping",
                java.util.Collections.<ScopingAssistantUpdate.Suggestion>emptyList(), "NEUTRAL", ""));
        assertNull(store.load());
    }
}
