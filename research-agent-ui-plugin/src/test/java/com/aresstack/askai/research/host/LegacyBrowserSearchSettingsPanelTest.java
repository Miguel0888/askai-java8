package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A2d surface behaviour: the panel builds every catalog field, prompt editors carry the PRODUCTIVE
 * default text (never an empty editor with a placeholder), and Save on unchanged defaults persists
 * cleanly (revision bump) — validation failures would refuse the save instead.
 */
public class LegacyBrowserSearchSettingsPanelTest {

    private final WorkspaceStateStore store =
            new LegacyBrowserSearchSettingsStoreTest.InMemoryStore();

    @Test
    public void promptEditorsCarryTheProductiveDefaultText() {
        LegacyBrowserSearchSettingsPanel panel = new LegacyBrowserSearchSettingsPanel(store,
                SearchProcessingProfileSnapshot.create("s1", 4L, 1L,
                        LegacyBrowserSearchDefaults.create()));
        List<JTextArea> areas = new ArrayList<JTextArea>();
        collect(panel, JTextArea.class, areas);
        boolean promptSeen = false;
        for (JTextArea area : areas) {
            if (area.getText().contains("{containerDescriptors}")) {
                promptSeen = true; // the real default prompt IS the editor value
            }
        }
        assertTrue("layout resolver prompt must be pre-filled with its productive default",
                promptSeen);
    }

    @Test
    public void savingUnchangedDefaultsBumpsTheRevisionWithoutViolations() {
        LegacyBrowserSearchSettingsPanel panel = new LegacyBrowserSearchSettingsPanel(store, null);
        JButton save = findButton(panel, "Save");
        assertNotNull(save);
        save.doClick();
        assertEquals(1L, LegacyBrowserSearchSettingsStore.revision(store));
        assertTrue(LegacyBrowserSearchSettingsStore.loadValues(store).isEmpty());
    }

    private static JButton findButton(Container root, String text) {
        List<JButton> buttons = new ArrayList<JButton>();
        collect(root, JButton.class, buttons);
        for (JButton button : buttons) {
            if (text.equals(button.getText())) {
                return button;
            }
        }
        fail("button not found: " + text);
        return null;
    }

    private static <T extends Component> void collect(Container root, Class<T> type, List<T> out) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
            if (child instanceof Container) {
                collect((Container) child, type, out);
            }
        }
    }
}
