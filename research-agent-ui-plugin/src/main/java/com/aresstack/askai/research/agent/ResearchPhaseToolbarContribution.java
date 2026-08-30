package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContext;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.comiccontrols.control.ResearchPillDropdown;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiPainter;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import java.awt.BasicStroke;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The research PHASE SELECTOR in the workspace top bar (the old Websuche spot): ONE pill control —
 * painted lab-flask glyph, phase text and chevron together — in the DARK "New Chat" accent
 * ({@link ResearchUiPalette#SECONDARY_SURFACE}), shared with the hamburger and the active ribbon
 * entry. It is deliberately NOT a second state
 * mechanism: the dropdown renders {@link ResearchStateSnapshot#getPhaseOrder()}, a selectable entry
 * exists exactly where {@link ResearchStateSnapshot#advanceCommandFor} names an allowed USER
 * command, and a click runs that command through {@link ResearchAgentSession#executeCommand} — the
 * same processor as the red tags, {@code /do}, the MCP {@code run_command} and the State tab's
 * phase clicks. Invalid switches stay impossible; the State artifact remains the detailed view.
 */
public final class ResearchPhaseToolbarContribution implements AgentToolbarContribution {

    /** Display names per phase id, in snapshot order ("Phase N: …" is prefixed at render time). */
    private static final Map<String, String> PHASE_TITLES;

    static {
        Map<String, String> titles = new LinkedHashMap<String, String>();
        titles.put(com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING,
                "Scoping / Konzeptphase");
        titles.put(com.aresstack.askai.research.state.oo.ResearchStateIds.OUTLINE,
                "Gliederung");
        titles.put(com.aresstack.askai.research.state.oo.ResearchStateIds.RESEARCH,
                "Research / Recherche");
        titles.put(com.aresstack.askai.research.state.oo.ResearchStateIds.EVIDENCE,
                "Evidenz / Belege");
        titles.put(com.aresstack.askai.research.state.oo.ResearchStateIds.DRAFT,
                "Entwurf");
        titles.put(com.aresstack.askai.research.state.oo.ResearchStateIds.REVIEW,
                "Review");
        titles.put(com.aresstack.askai.research.state.oo.ResearchStateIds.FINALIZATION,
                "Finalisierung");
        PHASE_TITLES = titles;
    }

    @Override
    public String getId() {
        return "research-phase-selector";
    }

    @Override
    public Placement getPlacement() {
        return Placement.CENTER;
    }

    @Override
    public boolean supports(AgentSession session) {
        return session instanceof ResearchAgentSession;
    }

    @Override
    public JComponent createComponent(final AgentToolbarContext context) {
        final ResearchAgentSession session = (ResearchAgentSession) context.getSession();
        final UiExecutor uiExecutor = context.getUiExecutor();

        final ResearchPillDropdown pill = new ResearchPillDropdown(
                ResearchUiMetrics.PHASE_PILL_HEIGHT, ResearchUiMetrics.RADIUS_CONTROL,
                ResearchUiMetrics.PHASE_PILL_MIN_WIDTH,
                ResearchUiMetrics.PHASE_PILL_PADDING_LEFT,
                ResearchUiMetrics.PHASE_PILL_PADDING_RIGHT);
        pill.setFont(ResearchUiTypography.regular(14f));
        // The DARK "New Chat" family (SECONDARY_SURFACE) instead of blue — one near-black accent
        // shared with the hamburger and the active ribbon entry; hover/open lift it slightly.
        pill.setFills(ResearchUiPalette.SECONDARY_SURFACE, ResearchUiPalette.SECONDARY_HOVER,
                ResearchUiPainter.mix(ResearchUiPalette.SECONDARY_HOVER, java.awt.Color.WHITE,
                        0.06f));
        pill.setPillForeground(ResearchUiPalette.TEXT_PRIMARY);
        pill.setLeadingIcon(new FlaskIcon());
        pill.setToolTipText("Research-Phase (Wechsel läuft über den regulären Workflow)");

        pill.setSelectionListener(new ResearchPillDropdown.SelectionListener() {
            public void itemSelected(int index) {
                dispatchPhaseClick(session, pill, index);
            }
        });

        final Runnable refresh = new Runnable() {
            public void run() {
                final ResearchStateSnapshot snapshot = session.currentResearchSnapshot();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        applySnapshot(pill, snapshot);
                    }
                });
            }
        };
        pill.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                session.addStateListener(refresh); // addIfAbsent — re-showing never doubles it
                refresh.run();
            }

            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }

            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                session.removeStateListener(refresh);
            }
        });
        session.addStateListener(refresh);
        refresh.run(); // initial paint
        return pill; // glyph, text and chevron are ONE control now — no separate icon beside it
    }

    /** Rebuild the dropdown rows from the snapshot: reachable phases enabled, everything else quiet. */
    private static void applySnapshot(ResearchPillDropdown pill, ResearchStateSnapshot snapshot) {
        List<String> order = snapshot.getPhaseOrder();
        List<ResearchPillDropdown.Item> items = new ArrayList<ResearchPillDropdown.Item>();
        int selected = 0;
        for (int index = 0; index < order.size(); index++) {
            String phaseId = order.get(index);
            if (phaseId.equals(snapshot.getCurrentPhaseId())) {
                selected = index;
            }
            ResearchCommandType command = snapshot.advanceCommandFor(phaseId);
            boolean reachable = command != null
                    && ResearchSemanticCommands.semanticNameFor(command) != null;
            items.add(new ResearchPillDropdown.Item(phaseLabel(index, phaseId), null, reachable,
                    reachable ? ResearchStateView.label(command) : null));
        }
        pill.setItems(items);
        pill.setSelectedIndex(selected);
    }

    /** Run the phase click through the ONE semantic command processor; rejections stay visible. */
    private static void dispatchPhaseClick(ResearchAgentSession session, ResearchPillDropdown pill,
                                           int index) {
        ResearchStateSnapshot snapshot = session.currentResearchSnapshot();
        List<String> order = snapshot.getPhaseOrder();
        if (index < 0 || index >= order.size()) {
            return;
        }
        ResearchCommandType command = snapshot.advanceCommandFor(order.get(index));
        String semantic = command == null ? null : ResearchSemanticCommands.semanticNameFor(command);
        if (semantic == null) {
            return; // the domain withdrew the transition since the popup opened
        }
        String outcome = session.executeCommand(semantic, "");
        if (outcome != null && !outcome.startsWith("handled")) {
            JOptionPane.showMessageDialog(pill,
                    outcome.replaceFirst("^rejected:\\s*", ""),
                    "Phasenwechsel abgelehnt", JOptionPane.WARNING_MESSAGE);
        }
        // Accepted commands need no handling: the session fires its state change and the ordinary
        // listener re-renders the pill from the new domain state.
    }

    private static String phaseLabel(int index, String phaseId) {
        String title = PHASE_TITLES.get(phaseId);
        return "Phase " + (index + 1) + ": " + (title == null ? phaseId : title);
    }

    /**
     * The lab-flask (Erlenmeyer) glyph, painted INSIDE the pill in white — a monochrome icon, not
     * an emoji. 16×18 so it sits comfortably in the 34px pill.
     */
    static final class FlaskIcon implements javax.swing.Icon {

        public int getIconWidth() {
            return 16;
        }

        public int getIconHeight() {
            return 18;
        }

        public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                g2.setColor(ResearchUiPalette.TEXT_PRIMARY);
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // Neck (two verticals), shoulders flaring out, flat bottom — the classic flask.
                Path2D.Float flask = new Path2D.Float();
                flask.moveTo(x + 6f, y + 1.5f);
                flask.lineTo(x + 6f, y + 6.5f);
                flask.lineTo(x + 2f, y + 15.5f);
                flask.lineTo(x + 14f, y + 15.5f);
                flask.lineTo(x + 10f, y + 6.5f);
                flask.lineTo(x + 10f, y + 1.5f);
                g2.draw(flask);
                g2.drawLine(x + 4, y + 1, x + 12, y + 1); // the rim
            } finally {
                g2.dispose();
            }
        }
    }
}
