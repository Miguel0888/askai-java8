package com.aresstack.askai.plugin.api.agent.toolbar;

import com.aresstack.askai.plugin.api.agent.AgentSession;

import javax.swing.JComponent;

/**
 * Contributes a small session-scoped control to the workspace TOP BAR (left of the gear) while this agent's
 * session is active — e.g. a research language switch, a coding agent's branch selector. Generic host SPI
 * like {@link com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution}: the host asks the
 * active agent extension, keeps only contributions whose {@link #supports(AgentSession)} accepts the live
 * session, builds them on the EDT and discards them on session/agent/tab change. Session-based by contract:
 * the component works on the session's values, never on agent-global state.
 */
public interface AgentToolbarContribution {

    /**
     * Client-property key the HOST sets (a {@code Boolean}) on a LEADING toolbar component and its
     * direct children while the sidebar tab menu is unfolded: {@code true} asks the control for its
     * REDUCED view (e.g. the phase pill shrinking to its ≤8-character short label) so it never
     * dominates the tab entries; {@code false}/absent restores the full view.
     */
    String COMPACT_MODE_PROPERTY = "askai.toolbarCompact";

    /** Where in the workspace top bar this control lives. */
    enum Placement {
        /**
         * Centered between the hamburger/ribbon (left) and the trailing controls (right) — e.g. a
         * session-wide search field. An unfolding ribbon pushes the centered control to the right
         * and may squeeze it.
         */
        CENTER,
        /**
         * LEFT-aligned right after the hamburger, BEFORE the unfolding tab ribbon — e.g. the
         * research phase selector. Stays visible while the menu unfolds; see
         * {@link #COMPACT_MODE_PROPERTY} for the reduced view it must offer then.
         */
        LEADING,
        /** Left of the gear in the trailing group (the default) — e.g. a language switch. */
        TRAILING,
        /**
         * The footer strip at the bottom of the drawer's "Chats" pane (next to the host's gear) —
         * e.g. the session language switch. Not part of the top bar at all.
         */
        SIDEBAR_FOOTER
    }

    /** A stable id for this control (diagnostics / de-duplication). */
    String getId();

    /** The control's top-bar placement; defaults to {@link Placement#TRAILING}. */
    default Placement getPlacement() {
        return Placement.TRAILING;
    }

    /** Whether this control applies to the given live session (e.g. the right agent type). */
    boolean supports(AgentSession session);

    /** Build the control for the active session; called on the EDT. */
    JComponent createComponent(AgentToolbarContext context);
}
