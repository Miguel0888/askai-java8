package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.research.store.FileResearchBriefStore;
import com.aresstack.askai.research.store.ResearchBriefArtifact;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The Fragestellung (research brief) is a real artifact tab with a working editor. */
public class ResearchBriefTabTest {

    @Test
    public void theBriefIsTheFirstArtifactWithItsOwnType() {
        AgentArtifact first = ResearchArtifacts.all().get(0);
        assertEquals(ResearchBriefArtifact.ARTIFACT_ID, first.getId());
        assertEquals("Fragestellung", first.getDisplayName());
        assertEquals(ResearchArtifacts.TYPE_BRIEF, first.getArtifactTypeId());
        assertEquals(ResearchArtifacts.TYPE_BRIEF,
                new ResearchBriefViewContribution().getArtifactTypeId());
    }

    @Test
    public void savingAndApprovingDriveTheStoreThroughTheView() throws Exception {
        FileResearchBriefStore store = new FileResearchBriefStore(
                Files.createTempDirectory("brief-view-test").toFile());
        ResearchBriefView view = new ResearchBriefView(store);

        textArea(view).setText("# Fragestellung\nWie skaliert X?");
        button(view, "Save draft").doClick();
        assertTrue("draft became the working copy", store.load().hasWorkingCopy());
        assertEquals(0, store.load().latestApprovedRevisionNumber());

        button(view, "Approve").doClick();
        assertEquals("approval created revision 1", 1, store.load().latestApprovedRevisionNumber());
        assertTrue("working copy was consumed by the approval", !store.load().hasWorkingCopy());

        // Approving again without changes must not create a duplicate revision.
        button(view, "Approve").doClick();
        assertEquals(1, store.load().latestApprovedRevisionNumber());
    }

    @Test
    public void anExternalReloadNeverOverwritesUnsavedUserEdits() throws Exception {
        FileResearchBriefStore store = new FileResearchBriefStore(
                Files.createTempDirectory("brief-view-test").toFile());
        store.updateWorkingCopy("agent draft", System.currentTimeMillis());
        ResearchBriefView view = new ResearchBriefView(store);
        assertEquals("agent draft", textArea(view).getText());

        textArea(view).setText("my own words");
        store.updateWorkingCopy("newer agent draft", System.currentTimeMillis());
        view.reloadFromStore(); // dirty editor → refresh skipped
        assertEquals("my own words", textArea(view).getText());

        button(view, "Save draft").doClick(); // saving clears dirty; the next reload works again
        view.reloadFromStore();
        assertEquals("my own words", textArea(view).getText());
    }

    // ------------------------------------------------------------------ helpers

    private static JTextArea textArea(Container root) {
        JTextArea found = find(root, JTextArea.class, null);
        if (found == null) {
            throw new AssertionError("no editor in the brief view");
        }
        return found;
    }

    private static JButton button(Container root, String text) {
        JButton found = find(root, JButton.class, text);
        if (found == null) {
            throw new AssertionError("no button '" + text + "' in the brief view");
        }
        return found;
    }

    private static <T extends Component> T find(Container root, Class<T> type, String text) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)
                    && (text == null || (child instanceof JButton && text.equals(((JButton) child).getText())))) {
                return type.cast(child);
            }
            if (child instanceof Container) {
                T nested = find((Container) child, type, text);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
