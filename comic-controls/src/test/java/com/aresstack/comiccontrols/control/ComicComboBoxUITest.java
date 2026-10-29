package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JComponent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ComicComboBoxUITest {

    @Test
    public void installSetsTheDelegateAndTheThemeColors() {
        JComboBox<String> combo = new JComboBox<String>(new String[]{"a", "b"});
        assertSame(combo, ComicComboBoxUI.install(combo));
        assertTrue(combo.getUI() instanceof ComicComboBoxUI);
        assertFalse(combo.isOpaque());
        assertEquals(AskAiTheme.active().research("LIGHT_CONTROL_TEXT"), combo.getForeground());
        assertEquals(AskAiTheme.active().research("LIGHT_CONTROL_BG"), combo.getBackground());
        assertTrue(combo.getPreferredSize().height >= 28);
        ComicFormControlsTest.paint(combo);
    }

    @Test
    public void aThemeSwitchLeavesNoOrphanedListenersBehind() {
        JComboBox<String> combo = ComicControls.comboBox(new String[]{"a"});
        int mouseListeners = combo.getMouseListeners().length;
        int focusListeners = combo.getFocusListeners().length;
        for (int i = 0; i < 3; i++) {
            combo.updateUI(); // what updateComponentTreeUI does on every theme switch
        }
        assertEquals(mouseListeners, combo.getMouseListeners().length);
        assertEquals(focusListeners, combo.getFocusListeners().length);
        // hovering after the switch must not reach a stale UI (it used to throw a NullPointerException)
        for (java.awt.event.MouseListener listener : combo.getMouseListeners()) {
            listener.mouseEntered(new java.awt.event.MouseEvent(combo, java.awt.event.MouseEvent.MOUSE_ENTERED,
                    0L, 0, 1, 1, 0, false));
        }
    }

    @Test
    public void editableComboKeepsATransparentThemedEditor() {
        JComboBox<String> combo = new JComboBox<String>(new String[]{"a"});
        combo.setEditable(true);
        ComicComboBoxUI.install(combo);
        JComponent editor = (JComponent) combo.getEditor().getEditorComponent();
        assertFalse(editor.isOpaque());
        assertEquals(AskAiTheme.active().research("LIGHT_CONTROL_TEXT"), editor.getForeground());
    }
}
