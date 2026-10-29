package com.aresstack.askai.research.sources;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicScrollPane;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;

/**
 * The FULL TEXT of one source, shown in the workspace's main area (where the chat is) while the Sources pane
 * is active: title and address on top, the read page text below — read-only, selectable, wrapping.
 */
final class SourceFullTextView extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel title = new JLabel();
    private final JLabel address = new JLabel();
    private final JTextArea text = ComicControls.textArea();

    SourceFullTextView() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        JPanel head = new JPanel(new BorderLayout(0, 2));
        head.setOpaque(false);
        title.setFont(ResearchUiTypography.semiBold(16f));
        address.setFont(ResearchUiTypography.regular(11.5f));
        head.add(title, BorderLayout.NORTH);
        head.add(address, BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);

        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(ResearchUiTypography.regular(13.5f));
        text.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JScrollPane scroll = new ComicScrollPane(text, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
    }

    /** Show this source (title, address, full text; a parked source explains why there is none yet). */
    void show(ResearchSourceRecord record) {
        title.setText(record.getTitle() == null || record.getTitle().isEmpty()
                ? record.getSourceId() : record.getTitle());
        address.setText(record.getUrl().isEmpty() ? record.getOrigin() : record.getUrl());
        text.setText(record.isParked()
                ? "(parked — the page has not been read yet)" : record.getFullText());
        text.setCaretPosition(0);
    }

    /** The shown text (tests). */
    String shownText() {
        return text.getText();
    }
}
