package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.WorkspaceHostContext;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Host contexts are scoped per (plugin, workspace): state and paths never leak between them. */
public class WorkspaceHostContextIsolationTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private WorkspaceHostContextFactory factory() {
        // The cross-cutting services are not exercised here, so nulls are fine for scoping tests.
        return new WorkspaceHostContextFactory(folder.getRoot(), null, null, null, null, null);
    }

    @Test
    public void stateStoresAreIsolatedPerWorkspace() {
        WorkspaceHostContextFactory factory = factory();
        WorkspaceHostContext a = factory.create("com.x.a", "ws-1");
        WorkspaceHostContext b = factory.create("com.x.a", "ws-2");

        a.getWorkspaceStateStore().put("dividerX", "100");
        assertEquals("100", a.getWorkspaceStateStore().get("dividerX", "?"));
        assertEquals("?", b.getWorkspaceStateStore().get("dividerX", "?"));
    }

    @Test
    public void pathsAreIsolatedPerPlugin() {
        WorkspaceHostContextFactory factory = factory();
        WorkspaceHostContext a = factory.create("com.x.a", "ws-1");
        WorkspaceHostContext b = factory.create("com.x.b", "ws-1");

        String pathA = a.getPluginPathService().getPluginDataDirectory().getAbsolutePath();
        String pathB = b.getPluginPathService().getPluginDataDirectory().getAbsolutePath();
        assertFalse("different plugins must not share a data directory", pathA.equals(pathB));
    }

    @Test
    public void stateSurvivesReopeningTheSameWorkspace() {
        WorkspaceHostContextFactory factory = factory();
        factory.create("com.x.a", "ws-1").getWorkspaceStateStore().put("tab", "Findings");
        WorkspaceHostContext reopened = factory.create("com.x.a", "ws-1");
        assertEquals("Findings", reopened.getWorkspaceStateStore().get("tab", "?"));
    }
}
