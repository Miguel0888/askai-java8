package com.aresstack.askai.browser.search.engine;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Per-engine result paging: page 1 is the plain endpoint template, deeper pages come from the
 * engine's OWN pagination template (offset math is catalog DATA, never engine-specific code), and an
 * engine without a pagination template simply cannot page. The per-engine page count is the user's
 * setting, carried in the selection entries with a backward-compatible flat form.
 */
public class BrowserSearchEnginePagingTest {

    @Test
    public void theCatalogEnginesAddressTheirFollowUpPages() {
        BrowserSearchEngine bing = BrowserSearchEngineCatalog.byId(BrowserSearchEngineCatalog.BING);
        assertEquals("https://www.bing.com/search?q=hase", bing.pageUrl(0, "hase", 1));
        assertEquals("Bing pages via the 1-based `first` offset",
                "https://www.bing.com/search?q=hase&first=11", bing.pageUrl(0, "hase", 2));
        assertEquals("https://www.bing.com/search?q=hase&first=21", bing.pageUrl(0, "hase", 3));

        BrowserSearchEngine ddg = BrowserSearchEngineCatalog.byId(BrowserSearchEngineCatalog.DUCKDUCKGO);
        assertEquals("https://html.duckduckgo.com/html/?q=hase", ddg.pageUrl(0, "hase", 1));
        assertEquals("DuckDuckGo pages via the `s` offset",
                "https://html.duckduckgo.com/html/?q=hase&s=30", ddg.pageUrl(0, "hase", 2));
        assertEquals("the lite transport has its own pagination template",
                "https://lite.duckduckgo.com/lite/?q=hase&s=30", ddg.pageUrl(1, "hase", 2));
    }

    @Test
    public void anEngineWithoutAPaginationTemplateCannotPage() {
        BrowserSearchEngine custom = BrowserSearchEngineCatalog.custom("http://x.test/s?q={query}");
        assertEquals("http://x.test/s?q=hase", custom.pageUrl(0, "hase", 1));
        assertNull("no template, no deeper page", custom.pageUrl(0, "hase", 2));
        assertNull(custom.pageUrl(7, "hase", 1)); // unknown endpoint index
    }

    @Test
    public void selectionEntriesCarryThePageCountBackwardCompatibly() {
        List<BrowserSearchEngineSelection.Entry> entries = Arrays.asList(
                new BrowserSearchEngineSelection.Entry("duckduckgo", true, 5),
                new BrowserSearchEngineSelection.Entry("bing", false));
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(entries, null);
        assertEquals("duckduckgo:on:5,bing:off:3", selection.encodeEntries());

        List<BrowserSearchEngineSelection.Entry> parsed =
                BrowserSearchEngineSelection.parseEntries("duckduckgo:on:5,bing:off:3");
        assertEquals(5, parsed.get(0).getResultPages());
        assertEquals(3, parsed.get(1).getResultPages());

        // Older configurations without the third part keep the documented default.
        List<BrowserSearchEngineSelection.Entry> legacy =
                BrowserSearchEngineSelection.parseEntries("duckduckgo:on,bing:off");
        assertEquals(BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                legacy.get(0).getResultPages());
        assertTrue(legacy.get(0).isEnabled());
        assertEquals("nonsense falls back", BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                BrowserSearchEngineSelection.parseEntries("bing:on:banane").get(0).getResultPages());
    }

    @Test
    public void resultPagesForAnswersFromTheEntriesElseTheDefault() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(
                Collections.singletonList(new BrowserSearchEngineSelection.Entry("bing", true, 7)),
                null);
        assertEquals(7, selection.resultPagesFor("bing"));
        assertEquals(BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                selection.resultPagesFor("unknown-engine"));
    }
}
