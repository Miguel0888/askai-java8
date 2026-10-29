package com.aresstack.comiccontrols.theme;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.UIResource;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Table-driven pin of the UIManager key table installed by {@link ComicTheme#install}. */
public class ComicThemeInstallTest {

    private Object previousButtonUi;
    private Object previousScrollBarUi;

    @Before
    public void rememberDelegates() {
        previousButtonUi = UIManager.get("ButtonUI");
        previousScrollBarUi = UIManager.get("ScrollBarUI");
    }

    @After
    public void restoreDelegates() {
        // keep other tests in this JVM independent of the global delegates
        UIManager.put("ButtonUI", previousButtonUi);
        UIManager.put("ScrollBarUI", previousScrollBarUi);
    }

    @Test
    public void everyKeyCarriesItsTokenAsUiResource() {
        AskAiTheme t = AskAiTheme.classic();
        ComicPalette p = t.comicPalette();
        Map<String, Color> expected = new LinkedHashMap<String, Color>();
        for (String k : new String[]{"Panel", "Viewport", "ScrollPane", "SplitPane", "OptionPane"}) {
            expected.put(k + ".background", p.getSurface());
        }
        expected.put("Label.foreground", p.getInk());
        expected.put("Button.foreground", p.getInk());
        for (String k : new String[]{"TextField", "TextArea", "PasswordField", "FormattedTextField"}) {
            expected.put(k + ".background", t.fieldBg());
            expected.put(k + ".foreground", p.getInk());
            expected.put(k + ".caretForeground", p.getInk());
            expected.put(k + ".selectionBackground", p.getAccentYellow());
            expected.put(k + ".selectionForeground", p.getInk());
            expected.put(k + ".inactiveForeground", t.hintTextSoft());
            expected.put(k + ".inactiveBackground", t.fieldBgQuiet());
            expected.put(k + ".disabledBackground", t.fieldBgQuiet());
        }
        for (String k : new String[]{"CheckBox", "RadioButton"}) {
            expected.put(k + ".background", p.getSurface());
            expected.put(k + ".foreground", p.getInk());
        }
        expected.put("Table.background", t.fieldBg());
        expected.put("Table.foreground", p.getInk());
        expected.put("Table.gridColor", t.fieldBorder());
        expected.put("Table.selectionBackground", p.getAccentYellow());
        expected.put("Table.selectionForeground", p.getInk());
        expected.put("TableHeader.background", p.getSurface());
        expected.put("TableHeader.foreground", p.getInk());
        for (String k : new String[]{"List", "Tree"}) {
            expected.put(k + ".selectionBackground", p.getAccentYellow());
            expected.put(k + ".selectionForeground", p.getInk());
        }
        expected.put("ComboBox.background", t.fieldBg());
        expected.put("ComboBox.foreground", p.getInk());
        expected.put("ComboBox.selectionBackground", p.getAccentYellow());
        expected.put("ComboBox.selectionForeground", p.getInk());
        expected.put("TabbedPane.background", p.getSurface());
        expected.put("TabbedPane.foreground", p.getInk());
        expected.put("TabbedPane.selected", t.buttonPlate());
        expected.put("TabbedPane.contentAreaColor", p.getSurface());
        expected.put("ProgressBar.foreground", p.getAccentOrange());
        expected.put("ProgressBar.background", p.getSurface());
        expected.put("ProgressBar.selectionForeground", p.getSurface());
        expected.put("ProgressBar.selectionBackground", p.getInk());
        expected.put("ToolTip.background", t.warmFocus());
        expected.put("ToolTip.foreground", p.getInk());
        expected.put("MenuBar.background", p.getSurface());
        for (String k : new String[]{"Menu", "MenuItem", "PopupMenu"}) {
            expected.put(k + ".background", t.buttonPlate());
        }

        ComicTheme.install(t);

        for (Map.Entry<String, Color> e : expected.entrySet()) {
            Object value = UIManager.get(e.getKey());
            assertTrue(e.getKey() + " must be a UIResource", value instanceof UIResource);
            assertEquals(e.getKey(), e.getValue(), UIManager.getColor(e.getKey()));
        }
        assertEquals(ComicTheme.BUTTON_UI, UIManager.get("ButtonUI"));
        assertEquals(ComicTheme.SCROLL_BAR_UI, UIManager.get("ScrollBarUI"));
        assertEquals("com.aresstack.comiccontrols.control.ComicButtonUI", ComicTheme.BUTTON_UI);
        assertEquals("com.aresstack.comiccontrols.control.ComicScrollBarUI", ComicTheme.SCROLL_BAR_UI);

        Object fieldBorder = UIManager.get("TextField.border");
        assertTrue(fieldBorder instanceof UIResource);
        assertTrue(fieldBorder instanceof CompoundBorder);
        assertEquals(t.fieldBorder(),
                ((LineBorder) ((CompoundBorder) fieldBorder).getOutsideBorder()).getLineColor());
        Object tipBorder = UIManager.get("ToolTip.border");
        assertTrue(tipBorder instanceof UIResource);
        assertEquals(p.getInk(), ((LineBorder) tipBorder).getLineColor());

        // the menu part is still installed
        assertEquals(p.getAccentYellow(), UIManager.getColor("MenuItem.selectionBackground"));
    }
}
