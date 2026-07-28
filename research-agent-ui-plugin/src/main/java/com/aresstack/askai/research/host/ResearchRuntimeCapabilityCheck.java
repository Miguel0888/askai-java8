package com.aresstack.askai.research.host;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured requirement checking for the productive research mode — every prerequisite is reported
 * individually, never as a blanket "research unavailable". Config-level items come from
 * {@link ResearchRuntimeConfig#validate()}; the browser-level status (driver bundle, driver compatibility,
 * installed browser, browser start) comes from the SIDECAR'S OWN readiness probe: the check briefly starts
 * the sidecar and reports its specific status line (READY / INCOMPATIBLE_DRIVER / DRIVER_BUNDLE_NOT_FOUND /
 * BROWSER_NOT_INSTALLED / BROWSER_START_FAILED), then shuts it down. Blocking — callers run it OFF the EDT.
 */
public final class ResearchRuntimeCapabilityCheck {

    /** One requirement with its own verdict. */
    public static final class Item {
        public final String label;
        public final boolean ok;
        public final String detail;

        Item(String label, boolean ok, String detail) {
            this.label = label;
            this.ok = ok;
            this.detail = detail;
        }

        public String render() {
            return (ok ? "[OK]   " : "[FAIL] ") + label + (detail.isEmpty() ? "" : " — " + detail);
        }
    }

    private ResearchRuntimeCapabilityCheck() {
    }

    public static List<Item> run(ResearchRuntimeSettings settings) {
        List<Item> items = new ArrayList<Item>();
        items.add(fileItem("Java 8 runtime for the research agent", settings.getAgentJavaExecutable()));
        items.add(fileItem("Research agent jar", settings.getAgentJar()));
        items.add(fileItem("Java 21 runtime for the browser sidecar", settings.getSidecarJavaExecutable()));
        items.add(fileItem("Browser sidecar jar", settings.getSidecarJar()));
        String search = settings.getSearchUrlTemplate();
        if (search.isEmpty()) {
            items.add(new Item("Search provider URL", true, "not configured — web_search will fail honestly"));
        } else {
            items.add(new Item("Search provider URL", search.contains("{query}"),
                    search.contains("{query}") ? "" : "template must contain {query}"));
        }

        boolean sidecarStartable = new File(settings.getSidecarJavaExecutable()).isFile()
                && new File(settings.getSidecarJar()).isFile();
        if (!sidecarStartable) {
            items.add(new Item("Browser readiness (sidecar probe)", false,
                    "not probed — sidecar runtime/jar missing (see above)"));
            return items;
        }
        try {
            BrowserMcpSidecarProcess probe = BrowserMcpSidecarProcess.start(
                    settings.toRuntimeConfig(), 120);
            try {
                items.add(new Item("Browser readiness (sidecar probe)", true, probe.getReadinessLine()));
            } finally {
                probe.close();
            }
        } catch (IOException notReady) {
            // The message carries the sidecar's SPECIFIC status (e.g. BROWSER_NOT_INSTALLED: ...).
            items.add(new Item("Browser readiness (sidecar probe)", false, notReady.getMessage()));
        }
        return items;
    }

    private static Item fileItem(String label, String path) {
        if (path == null || path.trim().isEmpty()) {
            return new Item(label, false, "not configured");
        }
        return new File(path).isFile() ? new Item(label, true, "")
                : new Item(label, false, "does not exist: " + path);
    }
}
