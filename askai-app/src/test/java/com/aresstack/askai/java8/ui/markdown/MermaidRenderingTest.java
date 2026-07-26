package com.aresstack.askai.java8.ui.markdown;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Verify Mermaid detection, off-EDT rendering, error isolation and caching. */
public class MermaidRenderingTest {

    private FlexmarkSwingRenderer renderer(MermaidImageRenderer mermaid) {
        return new FlexmarkSwingRenderer(MarkdownTheme.fromUiDefaults(),
                DesktopLinkOpener.systemDefault(), mermaid);
    }

    @Test
    public void closedMermaidFenceProducesADiagramPanel() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        assertTrue(MarkdownTestSupport.containsType(
                renderer.render("```mermaid\ngraph LR\nA-->B\n```", true), MermaidDiagramPanel.class));
    }

    @Test
    public void mermaidLanguageIsDetectedCaseInsensitively() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        assertTrue(MarkdownTestSupport.containsType(
                renderer.render("```MERMAID\ngraph LR\nA-->B\n```", true), MermaidDiagramPanel.class));
    }

    @Test
    public void plainCodeFenceIsNotAMermaidDiagram() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        assertFalse(MarkdownTestSupport.containsType(
                renderer.render("```java\nint x = 1;\n```", true), MermaidDiagramPanel.class));
    }

    @Test
    public void extraInfoStringDoesNotTriggerMermaid() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        assertFalse(MarkdownTestSupport.containsType(
                renderer.render("```mermaid diagram\ngraph LR\nA-->B\n```", true), MermaidDiagramPanel.class));
    }

    @Test
    public void mermaidIsNotRenderedWhileStreaming() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        // renderMermaid = false models the streaming phase; the fence stays a code block.
        assertFalse(MarkdownTestSupport.containsType(
                renderer.render("```mermaid\ngraph LR\nA-->B\n```", false), MermaidDiagramPanel.class));
        assertTrue(MarkdownTestSupport.containsType(
                renderer.render("```mermaid\ngraph LR\nA-->B\n```", false), CodeBlockPanel.class));
    }

    @Test
    public void rendererRunsOffTheEventDispatchThread() throws Exception {
        FakeMermaidImageRenderer fake = new FakeMermaidImageRenderer();
        renderer(fake).render("```mermaid\ngraph LR\nA-->B\n```", true);
        assertTrue("renderer invoked", fake.firstCall.await(5, TimeUnit.SECONDS));
        assertTrue("render ran off the EDT", !fake.lastCallOnEventDispatchThread);
    }

    @Test
    public void aFailingRendererDoesNotBreakTheMessage() {
        FakeMermaidImageRenderer fake = new FakeMermaidImageRenderer();
        fake.fail = true;
        // render() returns synchronously; the diagram failure happens on the worker and is contained.
        java.awt.Component panel = renderer(fake)
                .render("Intro text.\n\n```mermaid\ngraph LR\nA-->B\n```\n\nOutro text.", true);
        assertNotNull(panel);
        assertTrue("other content survives", MarkdownTestSupport.containsType(panel, WrappingTextPane.class));
        assertTrue("diagram area still present", MarkdownTestSupport.containsType(panel, MermaidDiagramPanel.class));
    }

    @Test
    public void cacheReusesIdenticalRenders() {
        final AtomicInteger delegateCalls = new AtomicInteger();
        MermaidImageRenderer counting = new MermaidImageRenderer() {
            @Override
            public BufferedImage render(String diagramCode, int width) {
                delegateCalls.incrementAndGet();
                return new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
            }
        };
        CachingMermaidImageRenderer cache = new CachingMermaidImageRenderer(counting, 8);
        cache.render("graph LR\nA-->B", 480);
        cache.render("graph LR\nA-->B", 480);
        assertEquals("identical code+width rendered once", 1, delegateCalls.get());
        cache.render("graph LR\nA-->B", 900);
        assertEquals("different width rendered again", 2, delegateCalls.get());
    }
}
