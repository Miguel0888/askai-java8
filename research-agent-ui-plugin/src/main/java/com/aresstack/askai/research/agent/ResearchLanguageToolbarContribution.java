package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContext;
import com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution;
import com.aresstack.comiccontrols.control.ResearchPillDropdown;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;

/**
 * The live SESSION language switch — {@code [flag Language ▾]} — in the CHATS FOOTER of the drawer
 * (design study), no longer in the top bar. Selecting a language calls
 * {@link ResearchAgentSession#changeLanguage} — host state first, then the best-effort
 * {@code set_language} service command. It is NEVER a chat turn, never a state-machine command and
 * never touches the persisted default in the gear settings. Flags are painted in code on purpose:
 * deterministic on every JRE/font, unlike emoji flag glyphs on Windows.
 */
public final class ResearchLanguageToolbarContribution implements AgentToolbarContribution {

    @Override
    public String getId() {
        return "research-session-language";
    }

    @Override
    public Placement getPlacement() {
        return Placement.SIDEBAR_FOOTER;
    }

    @Override
    public boolean supports(AgentSession session) {
        return session instanceof ResearchAgentSession;
    }

    @Override
    public JComponent createComponent(AgentToolbarContext context) {
        final ResearchAgentSession session = (ResearchAgentSession) context.getSession();
        final ResearchLanguage[] languages = ResearchLanguage.values();
        final ResearchPillDropdown pill = new ResearchPillDropdown(
                ResearchUiMetrics.FOOTER_CONTROL_HEIGHT, ResearchUiMetrics.RADIUS_CONTROL,
                0, ResearchUiMetrics.FOOTER_PILL_PADDING_H,
                ResearchUiMetrics.FOOTER_PILL_PADDING_H);
        pill.setFont(ResearchUiTypography.regular(13f));
        pill.setToolTipText("Language of this research session (new content only)");
        List<ResearchPillDropdown.Item> items = new ArrayList<ResearchPillDropdown.Item>();
        for (ResearchLanguage language : languages) {
            items.add(new ResearchPillDropdown.Item(
                    language == ResearchLanguage.GERMAN ? "Deutsch" : "English",
                    new FlagIcon(language), true, null));
        }
        pill.setItems(items);
        pill.setSelectedIndex(indexOf(languages, session.getSessionLanguage().currentLanguage()));
        pill.setSelectionListener(new ResearchPillDropdown.SelectionListener() {
            public void itemSelected(int index) {
                ResearchLanguage selected = languages[index];
                if (selected != session.getSessionLanguage().currentLanguage()) {
                    session.changeLanguage(selected);
                }
                pill.setSelectedIndex(index);
            }
        });
        return pill;
    }

    private static int indexOf(ResearchLanguage[] languages, ResearchLanguage current) {
        for (int index = 0; index < languages.length; index++) {
            if (languages[index] == current) {
                return index;
            }
        }
        return 0;
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
