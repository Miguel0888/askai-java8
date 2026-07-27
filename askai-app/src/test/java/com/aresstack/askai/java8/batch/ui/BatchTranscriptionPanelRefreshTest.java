package com.aresstack.askai.java8.batch.ui;

import com.aresstack.askai.java8.batch.service.BatchProfileCatalogLoadedEvent;
import com.aresstack.askai.java8.batch.service.BatchSelectionCatalogLoadedEvent;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionEventPublisher;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioProcessingProfile;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Refresh behaviour of the Batch panel: models and profiles reload independently, still-present selection
 * is preserved by stable id, new entries are not auto-selected, a second refresh is blocked while one is
 * running, and Swing list models are only mutated on the EDT.
 */
public class BatchTranscriptionPanelRefreshTest {

    private static final AudioProcessingProfile P1 = profile("id1", "Clean speech");
    private static final AudioProcessingProfile P2 = profile("id2", "Meeting room");

    @Test
    public void refreshLoadsModelsAndProfiles() throws Exception {
        FakeRefresher fake = new FakeRefresher();
        BatchTranscriptionPanel panel = build(asList("a"), asList(P1), fake);

        onEdt(panel::refresh);
        assertEquals(1, fake.modelCalls);
        assertEquals(1, fake.profileCalls);

        fake.modelCallback.accept(BatchSelectionCatalogLoadedEvent.loaded(asList("a", "b")));
        fake.profileCallback.accept(BatchProfileCatalogLoadedEvent.loaded(asList(P1, P2)));
        flushEdt();

        onEdt(() -> {
            assertEquals(2, panel.modelListComponent().getModel().getSize());
            assertEquals(2, panel.profileListComponent().getModel().getSize());
        });
    }

    @Test
    public void stillPresentModelSelectionIsPreservedRemovedDropsNewNotSelected() throws Exception {
        BatchTranscriptionPanel panel = build(new ArrayList<String>(), new ArrayList<AudioProcessingProfile>(),
                new FakeRefresher());
        onEdt(() -> {
            panel.setAvailableModels(asList("model-a", "model-b"));
            panel.modelListComponent().setSelectedIndices(new int[]{0, 1});
            // model-b removed, model-c added
            panel.setAvailableModels(asList("model-a", "model-c"));
            assertEquals(asList("model-a"), panel.modelListComponent().getSelectedValuesList());
            assertEquals(2, panel.modelListComponent().getModel().getSize());
        });
    }

    @Test
    public void renamedProfileStaysSelectedByIdDeletedDropsNewNotSelected() throws Exception {
        BatchTranscriptionPanel panel = build(new ArrayList<String>(), new ArrayList<AudioProcessingProfile>(),
                new FakeRefresher());
        AudioProcessingProfile renamedP1 = profile("id1", "Clean speech (renamed)");
        AudioProcessingProfile p3 = profile("id3", "New profile");
        onEdt(() -> {
            panel.setAvailableProfiles(asList(P1, P2));
            panel.profileListComponent().setSelectedIndices(new int[]{0, 1});
            // id1 renamed, id2 deleted, id3 new
            panel.setAvailableProfiles(asList(renamedP1, p3));
            List<AudioProcessingProfile> selected = panel.profileListComponent().getSelectedValuesList();
            assertEquals(1, selected.size());
            assertEquals("id1", selected.get(0).getId());
            assertEquals("Clean speech (renamed)", selected.get(0).getName());
            assertEquals(2, panel.profileListComponent().getModel().getSize());
        });
    }

    @Test
    public void multiSelectionIsFullyRestoredWhenAllStillPresent() throws Exception {
        BatchTranscriptionPanel panel = build(new ArrayList<String>(), new ArrayList<AudioProcessingProfile>(),
                new FakeRefresher());
        onEdt(() -> {
            panel.setAvailableModels(asList("m-a", "m-b"));
            panel.modelListComponent().setSelectedIndices(new int[]{0, 1});
            panel.setAvailableProfiles(asList(P1, P2));
            panel.profileListComponent().setSelectedIndices(new int[]{0, 1});
            // reorder both lists; every selected entry still present
            panel.setAvailableModels(asList("m-b", "m-a"));
            panel.setAvailableProfiles(asList(P2, P1));
            assertTrue(panel.modelListComponent().getSelectedValuesList().containsAll(asList("m-a", "m-b")));
            assertEquals(2, panel.modelListComponent().getSelectedValuesList().size());
            assertEquals(2, panel.profileListComponent().getSelectedValuesList().size());
        });
    }

    @Test
    public void modelFailureStillAppliesProfileSuccess() throws Exception {
        FakeRefresher fake = new FakeRefresher();
        BatchTranscriptionPanel panel = build(asList("keep"), asList(P1), fake);

        onEdt(panel::refresh);
        fake.modelCallback.accept(BatchSelectionCatalogLoadedEvent.failed("network down"));
        fake.profileCallback.accept(BatchProfileCatalogLoadedEvent.loaded(asList(P1, P2)));
        flushEdt();

        onEdt(() -> {
            assertEquals("failed model list left unchanged", 1, panel.modelListComponent().getModel().getSize());
            assertEquals("succeeded profile list applied", 2, panel.profileListComponent().getModel().getSize());
        });
        // Both loads finished -> a further refresh is accepted (button re-enabled, guard reset).
        onEdt(panel::refresh);
        assertEquals(2, fake.modelCalls);
    }

    @Test
    public void profileFailureStillAppliesModelSuccess() throws Exception {
        FakeRefresher fake = new FakeRefresher();
        BatchTranscriptionPanel panel = build(asList("a"), asList(P1), fake);

        onEdt(panel::refresh);
        fake.modelCallback.accept(BatchSelectionCatalogLoadedEvent.loaded(asList("a", "b", "c")));
        fake.profileCallback.accept(BatchProfileCatalogLoadedEvent.failed("disk error"));
        flushEdt();

        onEdt(() -> {
            assertEquals(3, panel.modelListComponent().getModel().getSize());
            assertEquals("failed profile list left unchanged", 1, panel.profileListComponent().getModel().getSize());
        });
    }

    @Test
    public void secondRefreshWhileOneIsRunningIsIgnored() throws Exception {
        FakeRefresher fake = new FakeRefresher();
        BatchTranscriptionPanel panel = build(asList("a"), asList(P1), fake);

        onEdt(panel::refresh);   // starts; callbacks not fired yet
        onEdt(panel::refresh);   // must be ignored while the first is in flight

        assertEquals(1, fake.modelCalls);
        assertEquals(1, fake.profileCalls);
    }

    @Test
    public void listModelsAreMutatedOnlyOnTheEdt() throws Exception {
        FakeRefresher fake = new FakeRefresher();
        BatchTranscriptionPanel panel = build(asList("only"), asList(P1), fake);

        onEdt(panel::refresh);
        // Fire the callback from THIS (non-EDT) thread: the update must be deferred to the EDT.
        assertFalse(SwingUtilities.isEventDispatchThread());
        fake.modelCallback.accept(BatchSelectionCatalogLoadedEvent.loaded(asList("one", "two", "three")));
        assertEquals("not applied off the EDT", 1, panel.modelListComponent().getModel().getSize());

        flushEdt();
        onEdt(() -> assertEquals(3, panel.modelListComponent().getModel().getSize()));
    }

    // --- helpers ---

    private static BatchTranscriptionPanel build(List<String> models, List<AudioProcessingProfile> profiles,
                                                 FakeRefresher refresher) throws Exception {
        final BatchTranscriptionPanel[] holder = new BatchTranscriptionPanel[1];
        BatchTranscriptionController controller =
                new BatchTranscriptionController(null, new BatchTranscriptionEventPublisher());
        onEdt(() -> holder[0] = new BatchTranscriptionPanel(controller, models, profiles, refresher));
        return holder[0];
    }

    private static AudioProcessingProfile profile(String id, String name) {
        return new AudioProcessingProfile(id, name, false, new ArrayList<AudioBlockDefinition>());
    }

    private static void onEdt(Runnable runnable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeAndWait(runnable);
        }
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() { }
        });
    }

    private static final class FakeRefresher implements BatchSelectionRefresher {
        private Consumer<BatchSelectionCatalogLoadedEvent> modelCallback;
        private Consumer<BatchProfileCatalogLoadedEvent> profileCallback;
        private int modelCalls;
        private int profileCalls;

        public void loadModels(Consumer<BatchSelectionCatalogLoadedEvent> callback) {
            modelCalls++;
            modelCallback = callback;
        }

        public void loadProfiles(Consumer<BatchProfileCatalogLoadedEvent> callback) {
            profileCalls++;
            profileCallback = callback;
        }
    }
}
