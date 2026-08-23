package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.backend.ScopingAssistantUpdate;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * The composer accessory surface: ONE justified flow of tags. YELLOW tags are the agent's search suggestions
 * (a click runs the search immediately); RED tags are the currently available ACTIONS — every red tag
 * represents exactly one command of the synchronized command surface (e.g. submit-scope = "Fragestellung
 * freigeben & weiter", approve-evidence, resume, review-sources). Red tags are appended INLINE after the
 * yellow ones; the flow layout wraps only when the row is full. There is deliberately NO second button style
 * and no chat action card — this is the uniform action surface. All methods run on the EDT.
 */
public final class ScopingSupportView extends JPanel {

    /** Client-property key marking a chip as an ACTION tag (value: the command it runs). */
    private static final String ACTION_COMMAND_PROPERTY = "research.action.command";

    private final JPanel tagsPanel;
    /**
     * The DEFAULT tag: a free search field in the suggestion-chip look (yellow, ink magnifier).
     * ONE instance re-added on every render, so typed-but-not-yet-fired text survives re-renders.
     * Firing runs the SAME search consumer a yellow suggestion click runs (= the /search path),
     * so the captured results flow into the corpus and the bot can review them afterwards.
     */
    private final com.aresstack.comiccontrols.control.ComicSearchTag searchTag;

    private Consumer<String> searchAction;
    private Consumer<ResearchActionTag> actionHandler;
    /** The in-flight "burst" animation that shrinks a searched/removed tag away before the re-render. */
    private javax.swing.Timer burstTimer;
    /** The last applied models — so a burst re-render uses the newest state. */
    private List<ScopingAssistantUpdate.Suggestion> currentSuggestions = Collections.emptyList();
    private List<ResearchActionTag> currentActions = Collections.emptyList();

    public ScopingSupportView() {
        super(new BorderLayout());

        searchTag = new com.aresstack.comiccontrols.control.ComicSearchTag(
                "Websuche…", "Direkt im Web suchen (wie /search)");
        searchTag.addSearchAction(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String query = searchTag.getText().trim();
                if (query.isEmpty() || searchAction == null) {
                    return;
                }
                searchTag.setText(""); // the query lives on as the visible "Websuche:" breadcrumb
                searchAction.accept(query);
            }
        });

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        // The tags speak for themselves — no section heading. BoxLayout must not stretch the panel
        // vertically, so its maximum height tracks the wrapped rows' preferred height.
        tagsPanel = new JPanel(new JustifiedTagLayout(6, 6)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        tagsPanel.setOpaque(false);
        tagsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(tagsPanel);

        add(content, BorderLayout.NORTH);
    }

    /** The accessory wires what a suggestion click runs (an immediate search with the query). */
    public void setSearchAction(Consumer<String> searchAction) {
        this.searchAction = searchAction;
    }

    /** The accessory wires what a RED action tag runs (the ONE structured command path). */
    public void setActionHandler(Consumer<ResearchActionTag> actionHandler) {
        this.actionHandler = actionHandler;
    }

    /**
     * The rendered submit-scope action tag ("Fragestellung freigeben & weiter"), or {@code null} while that
     * command is not offered — so a UI/integration test can {@code doClick()} the genuine wired action.
     */
    public JButton getApproveButton() {
        for (Component component : tagsPanel.getComponents()) {
            if (component instanceof JButton && "submit-scope".equals(
                    ((JButton) component).getClientProperty(ACTION_COMMAND_PROPERTY))) {
                return (JButton) component;
            }
        }
        return null;
    }

    /** The default free-search tag (yellow chip look, ink magnifier) — for tests and focus control. */
    public com.aresstack.comiccontrols.control.ComicSearchTag getSearchTag() {
        return searchTag;
    }

    /** The rendered YELLOW suggestion tags only (red action tags are excluded). */
    public java.util.List<javax.swing.AbstractButton> getSuggestionButtons() {
        java.util.List<javax.swing.AbstractButton> buttons =
                new java.util.ArrayList<javax.swing.AbstractButton>();
        for (Component component : tagsPanel.getComponents()) {
            if (component instanceof javax.swing.AbstractButton
                    && ((javax.swing.AbstractButton) component)
                            .getClientProperty(ACTION_COMMAND_PROPERTY) == null) {
                buttons.add((javax.swing.AbstractButton) component);
            }
        }
        return buttons;
    }

    /** The rendered RED action tags, in order — for tests and diagnostics. */
    public java.util.List<javax.swing.AbstractButton> getActionButtons() {
        java.util.List<javax.swing.AbstractButton> buttons =
                new java.util.ArrayList<javax.swing.AbstractButton>();
        for (Component component : tagsPanel.getComponents()) {
            if (component instanceof javax.swing.AbstractButton
                    && ((javax.swing.AbstractButton) component)
                            .getClientProperty(ACTION_COMMAND_PROPERTY) != null) {
                buttons.add((javax.swing.AbstractButton) component);
            }
        }
        return buttons;
    }

    /**
     * Apply the latest state: REPLACE the yellow suggestions and append the RED action tags inline. Either
     * list may be null/empty; the panel simply renders what exists.
     */
    public void apply(ScopingAssistantUpdate projection, List<ResearchActionTag> actions) {
        List<ScopingAssistantUpdate.Suggestion> suggestions = projection == null
                ? Collections.<ScopingAssistantUpdate.Suggestion>emptyList()
                : projection.getSearchSuggestions();
        List<ResearchActionTag> actionTags = actions == null
                ? Collections.<ResearchActionTag>emptyList() : actions;
        if (burstTimer != null && burstTimer.isRunning()) {
            burstTimer.stop(); // a newer state supersedes an in-flight burst
        }
        // Burst only DISAPPEARING yellow suggestions; action tags come and go without theater.
        java.util.Set<String> kept = new java.util.HashSet<String>();
        for (ScopingAssistantUpdate.Suggestion suggestion : suggestions) {
            kept.add(suggestion.getQuery());
        }
        java.util.List<javax.swing.AbstractButton> removed =
                new java.util.ArrayList<javax.swing.AbstractButton>();
        for (javax.swing.AbstractButton button : getSuggestionButtons()) {
            if (!kept.contains(button.getText())) {
                removed.add(button);
            }
        }
        currentSuggestions = suggestions;
        currentActions = actionTags;
        // Animate ONLY when actually on screen (skips in headless tests → deterministic instant re-render).
        if (removed.isEmpty() || !isShowing()) {
            renderTags();
        } else {
            burstThenRender(removed);
        }
    }

    /** Backwards-compatible single-argument form: suggestions only, keep the current action tags. */
    public void apply(ScopingAssistantUpdate projection) {
        apply(projection, currentActions);
    }

    /**
     * The clicked/covered tags "zerplatzen": shrink them to nothing over a few frames (the flow layout
     * re-arranges the rest live), then render the new, filtered state.
     */
    private void burstThenRender(final java.util.List<javax.swing.AbstractButton> removed) {
        final java.util.Map<javax.swing.AbstractButton, Dimension> base =
                new java.util.HashMap<javax.swing.AbstractButton, Dimension>();
        for (javax.swing.AbstractButton button : removed) {
            base.put(button, button.getPreferredSize());
            button.setEnabled(false); // no click on a bursting tag
        }
        final int steps = 7;
        final int[] step = {0};
        burstTimer = new javax.swing.Timer(28, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                step[0]++;
                float factor = Math.max(0f, 1f - (float) step[0] / steps);
                for (javax.swing.AbstractButton button : removed) {
                    Dimension d = base.get(button);
                    Dimension shrunk = new Dimension(Math.max(0, Math.round(d.width * factor)),
                            Math.max(0, Math.round(d.height * factor)));
                    button.setPreferredSize(shrunk);
                    button.setMaximumSize(shrunk);
                }
                tagsPanel.revalidate();
                tagsPanel.repaint();
                if (step[0] >= steps) {
                    burstTimer.stop();
                    renderTags();
                }
            }
        });
        burstTimer.start();
    }

    private void renderTags() {
        tagsPanel.removeAll(); // REPLACE, never accumulate old tags
        tagsPanel.add(searchTag); // the free-search tag is the constant first element
        for (final ScopingAssistantUpdate.Suggestion suggestion : currentSuggestions) {
            ResearchTagButton tag = new ResearchTagButton(suggestion.getQuery());
            tag.setToolTipText(suggestion.getPurpose().isEmpty()
                    ? "Sofort danach suchen" : suggestion.getPurpose());
            tag.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    if (searchAction != null) {
                        searchAction.accept(suggestion.getQuery());
                    }
                }
            });
            tagsPanel.add(tag);
        }
        for (final ResearchActionTag action : currentActions) {
            ResearchTagButton tag = new ResearchTagButton(action.getLabel(),
                    ResearchTagButton.ACTION_BACKGROUND, ResearchTagButton.ACTION_FOREGROUND);
            tag.putClientProperty(ACTION_COMMAND_PROPERTY, action.getCommand());
            tag.setEnabled(action.isEnabled());
            tag.setToolTipText(action.getTooltip().isEmpty() ? "/" + "do " + action.getCommand()
                    : action.getTooltip());
            tag.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    if (actionHandler != null) {
                        actionHandler.accept(action);
                    }
                }
            });
            tagsPanel.add(tag);
        }
        tagsPanel.revalidate();
        tagsPanel.repaint();
        revalidate();
        repaint();
    }

    public void dispose() {
        // Nothing host-owned to release; kept for the accessory lifecycle.
    }
}
