package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.hf.HuggingFaceModel;
import com.aresstack.askai.java8.ui.HuggingFaceModelClassifier.Provenance;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.Locale;

/**
 * A richer results list for HuggingFace model search: two lines per entry with the owner
 * highlighted by provenance, download/like counts, the base-model origin for community quants,
 * and the modality icons derived from the repo's pipeline tag. Provenance and variant detection
 * live in {@link HuggingFaceModelClassifier}; this class only renders them.
 */
public final class HuggingFaceResultsList extends JList<HuggingFaceModel> {

    private static final Color OFFICIAL_COLOR = new Color(0x1B, 0x5E, 0x20);
    private static final Color KNOWN_COLOR = new Color(0x0D, 0x47, 0xA1);
    private static final Color COMMUNITY_COLOR = new Color(0x61, 0x61, 0x61);

    public HuggingFaceResultsList(DefaultListModel<HuggingFaceModel> model) {
        super(model);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setCellRenderer(new ResultRenderer());
        setVisibleRowCount(8);
    }

    private static String formatCount(long value) {
        if (value >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0d);
        }
        if (value >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fK", value / 1_000.0d);
        }
        return String.valueOf(value);
    }

    /** Two-line card: owner/name + icons on top, stats and provenance below. */
    private static final class ResultRenderer extends JPanel implements ListCellRenderer<HuggingFaceModel> {

        private final JLabel ownerLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();
        private final JLabel iconLabel = new JLabel();
        private final JLabel statsLabel = new JLabel();
        private final JLabel badgeLabel = new JLabel();

        ResultRenderer() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xE3, 0xE6, 0xEB)),
                    BorderFactory.createEmptyBorder(5, 8, 5, 8)));
            setOpaque(true);

            JPanel firstLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            firstLine.setOpaque(false);
            firstLine.add(ownerLabel);
            firstLine.add(nameLabel);

            JPanel secondLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            secondLine.setOpaque(false);
            secondLine.add(statsLabel);
            secondLine.add(badgeLabel);

            JPanel lines = new JPanel(new BorderLayout(0, 2));
            lines.setOpaque(false);
            lines.add(firstLine, BorderLayout.NORTH);
            lines.add(secondLine, BorderLayout.SOUTH);

            add(lines, BorderLayout.CENTER);
            add(iconLabel, BorderLayout.EAST);

            badgeLabel.setOpaque(true);
            badgeLabel.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            iconLabel.setToolTipText("Model input modalities from the HuggingFace pipeline tag");
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends HuggingFaceModel> list,
                                                      HuggingFaceModel model, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            Provenance provenance = HuggingFaceModelClassifier.provenanceOf(model);
            Font base = list.getFont();

            ownerLabel.setText(model.getOwner() + " / ");
            ownerLabel.setFont(base);
            nameLabel.setText(model.getRepoName());
            nameLabel.setFont(base.deriveFont(Font.BOLD));
            iconLabel.setIcon(ModalityIcons.forModalities(HuggingFaceModelClassifier.modalitiesOf(model)));

            statsLabel.setText("↓ " + formatCount(model.getDownloads())
                    + "   ♥ " + formatCount(model.getLikes()));
            statsLabel.setFont(base.deriveFont(base.getSize2D() - 1f));

            configureBadge(provenance, model, base);

            Color foreground = isSelected ? list.getSelectionForeground() : list.getForeground();
            Color ownerColor = isSelected ? list.getSelectionForeground() : ownerColorFor(provenance);
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            ownerLabel.setForeground(ownerColor);
            nameLabel.setForeground(foreground);
            statsLabel.setForeground(isSelected ? list.getSelectionForeground() : new Color(0x75, 0x75, 0x75));
            return this;
        }

        private void configureBadge(Provenance provenance, HuggingFaceModel model, Font base) {
            badgeLabel.setFont(base.deriveFont(Font.BOLD, base.getSize2D() - 2f));
            badgeLabel.setForeground(Color.WHITE);
            if (provenance == Provenance.OFFICIAL) {
                badgeLabel.setText("OFFICIAL");
                badgeLabel.setBackground(OFFICIAL_COLOR);
                badgeLabel.setToolTipText("Published by the original model vendor");
                return;
            }
            if (provenance == Provenance.KNOWN_QUANTIZER) {
                badgeLabel.setBackground(KNOWN_COLOR);
                String baseOwner = model.getBaseModelOwner();
                badgeLabel.setText(baseOwner.length() > 0 ? "QUANT OF " + baseOwner.toUpperCase(Locale.ROOT) : "KNOWN");
                badgeLabel.setToolTipText("Well-known quantizer organization"
                        + (baseOwner.length() > 0 ? "; original model by " + baseOwner : ""));
                return;
            }
            String baseOwner = model.getBaseModelOwner();
            badgeLabel.setBackground(COMMUNITY_COLOR);
            badgeLabel.setText(baseOwner.length() > 0
                    ? "community — quant of " + baseOwner : "community");
            badgeLabel.setToolTipText("Community repository"
                    + (baseOwner.length() > 0 ? "; original model by " + baseOwner : ""));
        }

        private static Color ownerColorFor(Provenance provenance) {
            if (provenance == Provenance.OFFICIAL) {
                return OFFICIAL_COLOR;
            }
            if (provenance == Provenance.KNOWN_QUANTIZER) {
                return KNOWN_COLOR;
            }
            return COMMUNITY_COLOR;
        }
    }
}
