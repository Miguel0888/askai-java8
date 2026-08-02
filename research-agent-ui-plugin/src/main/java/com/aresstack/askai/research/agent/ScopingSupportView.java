package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.backend.ScopingAssistantUpdate;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.function.Consumer;

/**
 * The scoping composer accessory: ACTIONS of the active phase only. The agent's search suggestions
 * render as a JUSTIFIED flow of yellow TAGS (the MCP tool-bubble yellow) — no heading, no query
 * field, no visualization (a separate visualizer owns that; the brief is the phase artifact in its
 * own tab). Clicking a tag runs the search IMMEDIATELY via the action the accessory wires in (result
 * depth from the configured search settings, default 10). A later projection replaces the tags, so
 * the user can keep broadening the field click by click before the deep, structured phase. The
 * proposed query is surfaced ONLY as the chat composer's placeholder (wired by the accessory),
 * nowhere else. All methods run on the EDT.
 */
public final class ScopingSupportView extends JPanel {

    private final JPanel tagsPanel;
    /** The explicit user-owned phase transition: approve the brief and leave SCOPING. */
    private final JButton continueButton;

    private Consumer<String> searchAction;
    private Runnable continueAction;

    public ScopingSupportView() {
        super(new BorderLayout());

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

        // The single productive action of scoping: freigeben & weiter (the search button that would sit to
        // its left is a later slice). Disabled until the accessory reports the transition is legal; a click
        // is the ONLY thing that advances the phase. The whole view is hidden once the phase leaves scoping.
        continueButton = new JButton("Fragestellung freigeben & weiter");
        continueButton.setEnabled(false);
        continueButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (continueAction != null) {
                    continueAction.run();
                }
            }
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        actions.setOpaque(false);
        actions.add(continueButton);
        add(actions, BorderLayout.SOUTH);
    }

    /** The accessory wires what a tag click runs (an immediate search with the query). */
    public void setSearchAction(Consumer<String> searchAction) {
        this.searchAction = searchAction;
    }

    /** The accessory wires what the "freigeben & weiter" click runs (approve brief → advance the phase). */
    public void setContinueAction(Runnable continueAction) {
        this.continueAction = continueAction;
    }

    /** Enable the "freigeben & weiter" action only when the accessory reports the transition is legal. */
    public void setContinueEnabled(boolean enabled) {
        continueButton.setEnabled(enabled);
    }

    /**
     * The button's tooltip — when disabled it carries the concrete reason (e.g. "no brief yet", "a turn is
     * running"), so a greyed button is never an unexplained dead end.
     */
    public void setContinueTooltip(String tooltip) {
        continueButton.setToolTipText(tooltip == null || tooltip.isEmpty() ? null : tooltip);
    }

    /** The real approve button — so a UI/integration test can {@code doClick()} the genuine wired action. */
    public JButton getApproveButton() {
        return continueButton;
    }

    /** The rendered suggestion tags — so a UI/integration test can {@code doClick()} a genuine yellow tag. */
    public java.util.List<javax.swing.AbstractButton> getSuggestionButtons() {
        java.util.List<javax.swing.AbstractButton> buttons =
                new java.util.ArrayList<javax.swing.AbstractButton>();
        for (Component component : tagsPanel.getComponents()) {
            if (component instanceof javax.swing.AbstractButton) {
                buttons.add((javax.swing.AbstractButton) component);
            }
        }
        return buttons;
    }

    /** Apply the latest projection: REPLACE the tags with the agent's newest knowledge. */
    public void apply(ScopingAssistantUpdate projection) {
        if (projection == null) {
            return;
        }
        renderTags(projection.getSearchSuggestions());
    }

    private void renderTags(java.util.List<ScopingAssistantUpdate.Suggestion> suggestions) {
        tagsPanel.removeAll(); // REPLACE, never accumulate old tags
        for (final ScopingAssistantUpdate.Suggestion suggestion : suggestions) {
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
        tagsPanel.revalidate();
        tagsPanel.repaint();
        revalidate();
        repaint();
    }

    public void dispose() {
        // Nothing host-owned to release now that the map view is gone; kept for the accessory lifecycle.
    }
}
