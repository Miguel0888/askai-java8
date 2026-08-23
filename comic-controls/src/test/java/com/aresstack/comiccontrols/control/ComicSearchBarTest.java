package com.aresstack.comiccontrols.control;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The three search-bar variants: base wiring, the find bar's step navigation, the tag's firing. */
public class ComicSearchBarTest {

    @Test
    public void enterAndGoButtonBothFireTheSearchAction() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicSearchBar bar = new ComicSearchBar("Search…");
                final List<String> fired = new ArrayList<String>();
                bar.addSearchAction(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        fired.add(bar.getText());
                    }
                });
                bar.setText("comic ui");
                bar.getTextField().postActionEvent(); // Enter
                assertEquals(1, fired.size());
                bar.getGoButton().doClick(); // ▶ button (protected: same-package test access)
                assertEquals(2, fired.size());
                assertEquals("comic ui", fired.get(1));
            }
        });
    }

    @Test
    public void findBarStepsThroughMatchesOnlyInStepMode() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicFindBar bar = new ComicFindBar("Find…");
                final List<String> searches = new ArrayList<String>();
                bar.addSearchAction(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        searches.add(bar.getText());
                    }
                });
                bar.setText("needle");

                // Default: every Enter searches again, no arrow transformation.
                bar.getTextField().postActionEvent();
                assertFalse("no arrows outside step mode", bar.isArrowsVisible());
                assertEquals(1, searches.size());

                // Step mode: the first Enter searches, then ◀ ▶ step through matches.
                bar.setStepSearchEnabled(true);
                bar.getTextField().postActionEvent();
                assertTrue(bar.isArrowsVisible());
                assertEquals(2, searches.size());

                final int[] steps = {0};
                bar.setNextAction(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        steps[0]++;
                    }
                });
                bar.setPrevAction(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        steps[0]--;
                    }
                });
                // Leaving step mode resets to the ↵ button.
                bar.setStepSearchEnabled(false);
                assertFalse(bar.isArrowsVisible());
            }
        });
    }

    @Test
    public void searchTagKeepsTheCompactChipGeometry() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicSearchTag tag = new ComicSearchTag("Websuche…", null);
                final List<String> fired = new ArrayList<String>();
                tag.addSearchAction(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        fired.add(tag.getText());
                    }
                });
                tag.setText("swing comic ui");
                tag.getTextField().postActionEvent();
                assertEquals("swing comic ui", fired.get(0));
                assertTrue("wide enough to type a query", tag.getPreferredSize().width >= 200);
            }
        });
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
