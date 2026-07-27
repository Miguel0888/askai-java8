package com.aresstack.askai.research.sources;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

/** The sources view builds from the shared repository; two views never share Swing components. */
public class ResearchSourcesViewTest {

    @Test
    public void twoViewsOverOneRepositoryAreIndependentAndShowTheData() throws Exception {
        final ResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        final AtomicReference<ResearchSourcesView> a = new AtomicReference<ResearchSourcesView>();
        final AtomicReference<ResearchSourcesView> b = new AtomicReference<ResearchSourcesView>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                a.set(new ResearchSourcesView(repo, ResearchSourcesView.demoKnownSections()));
                b.set(new ResearchSourcesView(repo, ResearchSourcesView.demoKnownSections()));
            }
        });
        assertNotSame(a.get(), b.get());
        assertEquals(3, a.get().rowCount());
        assertEquals(3, b.get().rowCount());
    }
}
