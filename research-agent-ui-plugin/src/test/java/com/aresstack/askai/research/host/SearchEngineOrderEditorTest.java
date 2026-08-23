package com.aresstack.askai.research.host;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/**
 * The arrows move the real thing. A list that reorders on screen while the stored value stays as it was
 * would be worse than no arrows at all — the user would believe they had decided something.
 */
public class SearchEngineOrderEditorTest {

    @Test
    public void movingAnEngineUpChangesTheStoredOrder() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                SearchEngineOrderEditor editor = new SearchEngineOrderEditor("duckduckgo:on,bing:on");
                assertEquals("duckduckgo:on,bing:on", editor.get());

                select(editor, 1);
                move(editor, -1);

                assertEquals("bing:on,duckduckgo:on", editor.get());
            }
        });
    }

    @Test
    public void anEngineTheStoredValueNeverMentionedAppearsSwitchedOff() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                SearchEngineOrderEditor editor = new SearchEngineOrderEditor("duckduckgo:on");
                assertEquals("gaining an engine is the user's decision, not an update's",
                        "duckduckgo:on,bing:off", editor.get());
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void select(SearchEngineOrderEditor editor, int index) {
        try {
            java.lang.reflect.Field field = SearchEngineOrderEditor.class.getDeclaredField("list");
            field.setAccessible(true);
            ((javax.swing.JList<?>) field.get(editor)).setSelectedIndex(index);
        } catch (Exception noSuchSeam) {
            throw new IllegalStateException(noSuchSeam);
        }
    }

    private static void move(SearchEngineOrderEditor editor, int delta) {
        try {
            Method method = SearchEngineOrderEditor.class.getDeclaredMethod("move", int.class);
            method.setAccessible(true);
            method.invoke(editor, delta);
        } catch (Exception noSuchSeam) {
            throw new IllegalStateException(noSuchSeam);
        }
    }

    private static void onEdt(Runnable body) throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeAndWait(body);
    }
}
