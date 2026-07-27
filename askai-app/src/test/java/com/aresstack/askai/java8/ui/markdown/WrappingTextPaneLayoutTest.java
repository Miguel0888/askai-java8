package com.aresstack.askai.java8.ui.markdown;

import org.junit.Test;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Guards the wrapped-paragraph height contract: a paragraph measures its height at the width the layout
 * actually assigns, so a following heading is placed below the fully rendered paragraph instead of
 * overlapping a paragraph that was clipped to a stale single-line height.
 */
public class WrappingTextPaneLayoutTest {

    private static final String PARAGRAPH =
            "This is a deliberately long paragraph that must wrap onto several lines when the chat bubble "
            + "is narrow, so that its measured height reflects the real number of wrapped lines and not a "
            + "single line height computed before the final width was known to the layout manager.";

    private static final String DOCUMENT =
            "# First Heading\n\n" + PARAGRAPH + "\n\n## Second Heading\n\nShort tail.";

    @Test
    public void wrappedHeightGrowsAsTheWidthShrinks() {
        WrappingTextPane pane = new WrappingTextPane();
        pane.setText(PARAGRAPH);

        pane.setSize(600, Short.MAX_VALUE);
        int wide = pane.getPreferredSize().height;
        pane.setSize(240, Short.MAX_VALUE);
        int narrow = pane.getPreferredSize().height;

        assertTrue("narrower width wraps to more lines -> taller (" + narrow + " > " + wide + ")",
                narrow > wide);
    }

    @Test
    public void aWrappedParagraphKeepsItsFullHeightBetweenTwoHeadings() {
        JComponent root = (JComponent) render(DOCUMENT);
        layoutAt(root, 260);

        WrappingTextPane paragraph = paneContaining(root, "deliberately long paragraph");
        WrappingTextPane secondHeading = paneContaining(root, "Second Heading");

        int lineHeight = paragraph.getFontMetrics(paragraph.getFont()).getHeight();
        assertTrue("paragraph is not clipped to one line (h=" + paragraph.getHeight()
                        + ", line=" + lineHeight + ")",
                paragraph.getHeight() >= lineHeight * 3);

        int paragraphBottom = yRelativeTo(root, paragraph) + paragraph.getHeight();
        assertTrue("second heading starts below the full paragraph",
                yRelativeTo(root, secondHeading) >= paragraphBottom);
    }

    @Test
    public void narrowingTheRenderAreaRecomputesTheParagraphHeight() {
        JComponent root = (JComponent) render(DOCUMENT);

        layoutAt(root, 620);
        int wide = paneContaining(root, "deliberately long paragraph").getHeight();
        layoutAt(root, 260);
        int narrow = paneContaining(root, "deliberately long paragraph").getHeight();

        assertTrue("narrower render area -> taller paragraph (" + narrow + " > " + wide + ")",
                narrow > wide);
    }

    // --- helpers ---

    private static Component render(String markdown) {
        FlexmarkSwingRenderer renderer = new FlexmarkSwingRenderer(
                MarkdownTheme.fromUiDefaults(), DesktopLinkOpener.systemDefault(),
                new FakeMermaidImageRenderer());
        return renderer.render(markdown, true);
    }

    /**
     * Lay the tree out at a fixed width. Invalidating before each pass drops BoxLayout's cached child size
     * requirements so it re-queries the wrapped height at the newly assigned width — headless stand-in for
     * the revalidate() a live UI performs when {@link WrappingTextPane#setBounds} sees the width change.
     */
    private static void layoutAt(JComponent root, int width) {
        root.setSize(width, 10_000);
        for (int pass = 0; pass < 3; pass++) {
            invalidateTree(root);
            layoutTree(root);
        }
    }

    private static void invalidateTree(Container container) {
        container.invalidate();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                invalidateTree((Container) child);
            }
        }
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutTree((Container) child);
            }
        }
    }

    private static WrappingTextPane paneContaining(Component root, String needle) {
        List<WrappingTextPane> panes = MarkdownTestSupport.collect(root, WrappingTextPane.class);
        for (WrappingTextPane pane : panes) {
            if (pane.getText().contains(needle)) {
                return pane;
            }
        }
        throw new AssertionError("no WrappingTextPane containing '" + needle + "'");
    }

    private static int yRelativeTo(Component ancestor, Component component) {
        int y = 0;
        Component current = component;
        while (current != null && current != ancestor) {
            y += current.getY();
            current = current.getParent();
        }
        return y;
    }
}
