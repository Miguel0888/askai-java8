package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContext;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;

import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * The live SESSION language switch in the workspace top bar: {@code [flag Language ▾]} left of the gear.
 * Selecting a language calls {@link ResearchAgentSession#changeLanguage} — host state first, then the
 * best-effort {@code set_language} service command. It is NEVER a chat turn, never a state-machine command
 * and never touches the persisted default in the gear settings. Flags are painted in code on purpose:
 * deterministic on every JRE/font, unlike emoji flag glyphs on Windows.
 */
public final class ResearchLanguageToolbarContribution implements AgentToolbarContribution {

    @Override
    public String getId() {
        return "research-session-language";
    }

    @Override
    public boolean supports(AgentSession session) {
        return session instanceof ResearchAgentSession;
    }

    @Override
    public JComponent createComponent(AgentToolbarContext context) {
        final ResearchAgentSession session = (ResearchAgentSession) context.getSession();
        final JComboBox<ResearchLanguage> combo =
                new JComboBox<ResearchLanguage>(ResearchLanguage.values());
        combo.setFocusable(false);
        combo.setToolTipText("Language of this research session (new content only)");
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                ResearchLanguage language = (ResearchLanguage) value;
                label.setText(language == ResearchLanguage.GERMAN ? "Deutsch" : "English");
                label.setIcon(new FlagIcon(language));
                return label;
            }
        });
        combo.setSelectedItem(session.getSessionLanguage().currentLanguage());
        combo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                ResearchLanguage selected = (ResearchLanguage) combo.getSelectedItem();
                if (selected != null
                        && selected != session.getSessionLanguage().currentLanguage()) {
                    session.changeLanguage(selected);
                }
            }
        });
        return combo;
    }

    /** 16×11 flag glyphs, painted (no fonts, no image resources): DE tricolour, simplified Union Jack. */
    static final class FlagIcon implements Icon {

        private static final int W = 16;
        private static final int H = 11;

        private final ResearchLanguage language;

        FlagIcon(ResearchLanguage language) {
            this.language = language;
        }

        public int getIconWidth() {
            return W;
        }

        public int getIconHeight() {
            return H;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (language == ResearchLanguage.GERMAN) {
                g.setColor(Color.BLACK);
                g.fillRect(x, y, W, 4);
                g.setColor(new Color(0xDD0000));
                g.fillRect(x, y + 4, W, 4);
                g.setColor(new Color(0xFFCE00));
                g.fillRect(x, y + 8, W, 3);
            } else {
                g.setColor(new Color(0x012169));
                g.fillRect(x, y, W, H);
                g.setColor(Color.WHITE);
                g.drawLine(x, y, x + W - 1, y + H - 1);
                g.drawLine(x, y + 1, x + W - 2, y + H - 1);
                g.drawLine(x + W - 1, y, x, y + H - 1);
                g.drawLine(x + W - 2, y, x, y + H - 2);
                g.fillRect(x + 6, y, 4, H);
                g.fillRect(x, y + 4, W, 3);
                g.setColor(new Color(0xC8102E));
                g.fillRect(x + 7, y, 2, H);
                g.fillRect(x, y + 5, W, 1);
            }
            g.setColor(new Color(0, 0, 0, 60));
            g.drawRect(x, y, W - 1, H - 1);
        }
    }
}
