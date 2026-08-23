package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.backend.ScopingAssistantUpdate;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The free-search tag (issue #36 line): a yellow chip-look search field is the tag surface's
 * DEFAULT element; firing it runs the same search consumer a suggestion click runs (= /search),
 * so the captured sources flow into the corpus the bot reviews later.
 */
public class ScopingSupportViewSearchTagTest {

    @Test
    public void theSearchTagIsRenderedFirstEvenWithoutSuggestions() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ScopingSupportView view = new ScopingSupportView();
                view.apply(null, Collections.<ResearchActionTag>emptyList());
                Component first = firstTagComponent(view);
                assertTrue("the free-search tag is the constant first element",
                        first instanceof com.aresstack.comiccontrols.control.ComicSearchTag);
            }
        });
    }

    @Test
    public void firingTheTagRunsTheSearchConsumerAndClearsTheField() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ScopingSupportView view = new ScopingSupportView();
                final List<String> searched = new ArrayList<String>();
                view.setSearchAction(new Consumer<String>() {
                    public void accept(String query) {
                        searched.add(query);
                    }
                });
                view.apply(null, Collections.<ResearchActionTag>emptyList());

                view.getSearchTag().setText("  ada lovelace  ");
                view.getSearchTag().getTextField().postActionEvent(); // Enter

                assertEquals("the trimmed query reaches the /search consumer",
                        Collections.singletonList("ada lovelace"), searched);
                assertEquals("the field is ready for the next query", "",
                        view.getSearchTag().getText());

                view.getSearchTag().getTextField().postActionEvent(); // empty → no search
                assertEquals(1, searched.size());
            }
        });
    }

    @Test
    public void typedTextSurvivesATagReRender() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ScopingSupportView view = new ScopingSupportView();
                view.apply(null, Collections.<ResearchActionTag>emptyList());
                view.getSearchTag().setText("half-typed query");

                // A state change re-renders the tags (e.g. new suggestions arrive) …
                view.apply(new ScopingAssistantUpdate("scoping",
                        Collections.<ScopingAssistantUpdate.Suggestion>emptyList(), "", ""),
                        Collections.<ResearchActionTag>emptyList());

                // … but the SAME tag instance is re-added, so the user's typing is not lost.
                assertEquals("half-typed query", view.getSearchTag().getText());
            }
        });
    }

    private static Component firstTagComponent(ScopingSupportView view) {
        // content panel → tagsPanel → first child
        java.awt.Container content = (java.awt.Container) view.getComponent(0);
        java.awt.Container tags = (java.awt.Container) content.getComponent(0);
        return tags.getComponent(0);
    }

    private static void onEdt(Runnable runnable) throws Exception {
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ex.getCause();
            }
            if (ex.getCause() instanceof Error) {
                throw (Error) ex.getCause();
            }
            throw ex;
        }
    }
}
