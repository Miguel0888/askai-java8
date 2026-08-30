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

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The research PHASE SELECTOR in the workspace top bar (the old Websuche spot): a painted lab-flask
 * glyph plus the purple pill dropdown of the design study. It is deliberately NOT a second state
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
        pill.setFills(ResearchUiPalette.PURPLE_PRIMARY, ResearchUiPalette.PURPLE_HOVER,
                ResearchUiPalette.PURPLE_ACTION);
        pill.setPillForeground(ResearchUiPalette.TEXT_PRIMARY);
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

        JPanel group = new JPanel();
        group.setLayout(new BoxLayout(group, BoxLayout.X_AXIS));
        group.setOpaque(false);
        group.add(new FlaskGlyph());
        group.add(Box.createHorizontalStrut(ResearchUiMetrics.PHASE_ICON_GAP));
        group.add(pill);
        return group;
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
     * The lab-flask (Erlenmeyer) glyph: 24×24 box, ~19px visible, 1.9px stroke, painted — no emoji.
     */
    static final class FlaskGlyph extends JComponent {

        FlaskGlyph() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                g2.setColor(ResearchUiPalette.TEXT_PRIMARY);
                g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // Neck (two verticals), shoulders flaring out, flat bottom — the classic flask.
                Path2D.Float flask = new Path2D.Float();
                flask.moveTo(10f, 3f);
                flask.lineTo(10f, 9.5f);
                flask.lineTo(5f, 20.5f);
                flask.lineTo(19f, 20.5f);
                flask.lineTo(14f, 9.5f);
                flask.lineTo(14f, 3f);
                g2.draw(flask);
                g2.drawLine(8, 3, 16, 3); // the rim
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(ResearchUiMetrics.PHASE_ICON_BOX,
                    ResearchUiMetrics.PHASE_ICON_BOX);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    }
}
