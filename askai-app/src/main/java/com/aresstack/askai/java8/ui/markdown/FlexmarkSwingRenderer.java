package com.aresstack.askai.java8.ui.markdown;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.Arrays;

/** Map Flexmark block nodes to native Swing components. */
final class FlexmarkSwingRenderer {

    private final Parser parser;
    private final MarkdownTheme theme;
    private final InlineMarkdownRenderer inlineRenderer;
    private final MermaidImageRenderer mermaidImageRenderer;

    FlexmarkSwingRenderer(MarkdownTheme theme, DesktopLinkOpener linkOpener,
                          MermaidImageRenderer mermaidImageRenderer) {
        this.theme = theme;
        this.inlineRenderer = new InlineMarkdownRenderer(theme, linkOpener);
        this.mermaidImageRenderer = mermaidImageRenderer;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    JPanel render(String markdown, boolean renderMermaid) {
        Node document = parser.parse(markdown == null ? "" : markdown);
        return renderContainer(document, renderMermaid);
    }

    private JPanel renderContainer(Node container, boolean renderMermaid) {
        JPanel panel = verticalPanel();
        Node child = container.getFirstChild();
        while (child != null) {
            JComponent component = renderBlock(child, renderMermaid);
            if (component != null) {
                component.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(component);
                panel.add(Box.createVerticalStrut(blockGap(child)));
            }
            child = child.getNext();
        }
        return panel;
    }

    private JComponent renderBlock(Node node, boolean renderMermaid) {
        if (node instanceof Heading) {
            return renderHeading((Heading) node);
        }
        if (node instanceof Paragraph) {
            return inlineRenderer.render(node, theme.getBodyFont());
        }
        if (node instanceof FencedCodeBlock) {
            return renderFencedCode((FencedCodeBlock) node, renderMermaid);
        }
        if (node instanceof IndentedCodeBlock) {
            String code = ((IndentedCodeBlock) node).getContentChars().toString();
            return code.trim().isEmpty() ? null : new CodeBlockPanel("", code, theme);
        }
        if (node instanceof BulletList) {
            return renderList(node, false, 1, renderMermaid);
        }
        if (node instanceof OrderedList) {
            return renderList(node, true, ((OrderedList) node).getStartNumber(), renderMermaid);
        }
        if (node instanceof BlockQuote) {
            return renderQuote(node, renderMermaid);
        }
        if (node instanceof TableBlock) {
            return new MarkdownTablePanel((TableBlock) node, theme);
        }
        if (node instanceof ThematicBreak) {
            JSeparator separator = new JSeparator();
            separator.setForeground(theme.getSeparatorColor());
            return separator;
        }
        if (node.hasChildren()) {
            return renderContainer(node, renderMermaid);
        }
        JLabel fallback = new JLabel(node.getChars().toString());
        fallback.setFont(theme.getBodyFont());
        fallback.setForeground(theme.getForeground());
        return fallback;
    }

    private JComponent renderHeading(Heading heading) {
        int level = Math.max(1, Math.min(6, heading.getLevel()));
        int bodySize = theme.getBodyFont().getSize();
        int size = Math.max(bodySize + 1, bodySize + (7 - level) * 2);
        Font font = theme.getBodyFont().deriveFont(Font.BOLD, size);
        return inlineRenderer.render(heading, font);
    }

    private JComponent renderFencedCode(FencedCodeBlock block, boolean renderMermaid) {
        String language = block.getInfo().toString().trim();
        String code = block.getContentChars().toString();
        if ("mermaid".equalsIgnoreCase(language) && renderMermaid) {
            return new MermaidDiagramPanel(code, theme, mermaidImageRenderer);
        }
        if (code.trim().isEmpty()) {
            return null; // don't paint a big empty box for a blank fence
        }
        return new CodeBlockPanel(language, code, theme);
    }

    private JComponent renderList(Node list, boolean ordered, int startNumber, boolean renderMermaid) {
        JPanel listPanel = verticalPanel();
        listPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        Node child = list.getFirstChild();
        int number = startNumber;
        while (child != null) {
            if (child instanceof ListItem) {
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(false);
                JLabel marker = new JLabel(ordered ? number + "." : "•");
                marker.setFont(theme.getBodyFont());
                marker.setForeground(theme.getForeground());
                marker.setVerticalAlignment(JLabel.TOP);
                row.add(marker, BorderLayout.WEST);
                row.add(renderContainer(child, renderMermaid), BorderLayout.CENTER);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                listPanel.add(row);
                listPanel.add(Box.createVerticalStrut(2));
                number++;
            }
            child = child.getNext();
        }
        return listPanel;
    }

    private JComponent renderQuote(Node quote, boolean renderMermaid) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, theme.getQuoteBorder()),
                BorderFactory.createEmptyBorder(1, 10, 1, 0)));
        wrapper.add(renderContainer(quote, renderMermaid), BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private int blockGap(Node node) {
        if (node instanceof Heading) {
            return 6;
        }
        if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock || node instanceof BlockQuote) {
            return 10;
        }
        return 5;
    }
}
