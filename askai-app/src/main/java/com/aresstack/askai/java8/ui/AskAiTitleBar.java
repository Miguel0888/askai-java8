package com.aresstack.askai.java8.ui;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.search.AgentSearchContribution;
import com.aresstack.comiccontrols.control.ComicSearchTag;
import com.aresstack.comiccontrols.control.ComicWindowHoldCloseButton;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.util.function.Supplier;

/**
 * AskAI's own window title bar (the window is frameless): on the LEFT the app icon and the window title,
 * exactly where Windows shows them; in the CENTER the title-bar SEARCH of the active agent; on the RIGHT the
 * comic window control (click minimizes, hold closes). The whole bar is the drag handle (double-click
 * maximizes) — wired by the frame.
 *
 * <p>The search is a container: the host owns ONE field, the active agent's {@link AgentSearchContribution}
 * defines placeholder, tooltip and what a search does. Without a contribution the field is hidden.</p>
 */
public final class AskAiTitleBar extends JPanel {

    private static final long serialVersionUID = 1L;
    /** The bar's height (Windows 11 caption height). */
    static final int HEIGHT = 34;

    private final JLabel titleLabel = new JLabel();
    private final JPanel searchSlot = new JPanel(new GridBagLayout());
    private AgentSearchContribution boundSearch;
    private Supplier<AgentSession> sessionSource;
    private ComicSearchTag searchField;

    public AskAiTitleBar(String title, Runnable minimizeAction, Runnable closeAction) {
        super(new BorderLayout(8, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 6));
        setPreferredSize(new Dimension(10, HEIGHT));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.setOpaque(false);
        Image icon = AppIcon.sized(16);
        if (icon != null) {
            JLabel iconLabel = new JLabel(new ImageIcon(icon.getScaledInstance(16, 16, Image.SCALE_SMOOTH)));
            left.add(iconLabel);
            left.add(Box.createHorizontalStrut(8));
        }
        titleLabel.setText(title == null ? "" : title);
        titleLabel.setFont(ResearchUiTypography.regular(12.5f));
        left.add(titleLabel);
        add(left, BorderLayout.WEST);

        searchSlot.setOpaque(false);
        searchSlot.setVisible(false);
        add(searchSlot, BorderLayout.CENTER);

        JPanel right = new JPanel(new GridBagLayout());
        right.setOpaque(false);
        right.add(new ComicWindowHoldCloseButton(ComicPalette.defaultPalette(), minimizeAction, closeAction, 24));
        add(right, BorderLayout.EAST);
    }

    /** The title shown next to the icon (follows the frame title). */
    public void setTitle(String title) {
        titleLabel.setText(title == null ? "" : title);
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    /**
     * Binds the search field to the active agent's contribution ({@code null} hides the field); the
     * session supplier yields the session a search runs for. Rebuilds the field only when the
     * contribution changes, so typing is never interrupted by unrelated agent events.
     */
    public void bindSearch(AgentSearchContribution search, Supplier<AgentSession> sessions) {
        this.sessionSource = sessions;
        if (search == boundSearch && (search == null || searchField != null)) {
            return;
        }
        boundSearch = search;
        searchSlot.removeAll();
        searchField = null;
        if (search != null) {
            final ComicSearchTag field = new ComicSearchTag(search.getPlaceholder(), search.getTooltip());
            field.addSearchAction(event -> runSearch(field));
            searchField = field;
            searchSlot.add(field);
        }
        searchSlot.setVisible(search != null);
        searchSlot.revalidate();
        searchSlot.repaint();
    }

    private void runSearch(ComicSearchTag field) {
        String query = field.getText().trim();
        AgentSearchContribution search = boundSearch;
        AgentSession session = sessionSource == null ? null : sessionSource.get();
        if (query.isEmpty() || search == null || session == null) {
            return;
        }
        field.setText(""); // the plugin shows the running search itself (e.g. a breadcrumb)
        search.search(session, query);
    }

    /** The visible search field, or {@code null} while no agent search is bound (tests). */
    JComponent searchField() {
        return searchSlot.isVisible() ? searchField : null;
    }
}
