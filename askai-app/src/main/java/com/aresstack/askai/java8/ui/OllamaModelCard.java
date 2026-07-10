package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.config.HuggingFaceSearchSuggestion.Modality;
import com.aresstack.askai.java8.client.OllamaModelInfo;
import com.aresstack.askai.java8.client.OllamaRunningModelInfo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class OllamaModelCard extends JPanel {

    /** Receive add-on install requests from a card ("+ Audio" / "+ Vision"). */
    interface AddOnHandler {
        /**
         * @param model            the installed model the add-on belongs to
         * @param modality         which encoder to install (AUDIO or VISION)
         * @param alreadyInstalled whether the model already reports that capability
         */
        void installAddOn(OllamaModelInfo model, Modality modality, boolean alreadyInstalled);
    }

    private final JLabel capabilityIconLabel = new JLabel();
    private final JButton audioAddOnButton = new JButton("+ Audio");
    private final JButton visionAddOnButton = new JButton("+ Vision");
    private Set<Modality> capabilities = EnumSet.noneOf(Modality.class);
    private boolean capabilitiesKnown;

    private OllamaModelCard(String title, String line1, String line2, boolean running,
                            final OllamaModelInfo installedModel, final AddOnHandler addOnHandler,
                            Runnable deleteAction) {
        setLayout(new BorderLayout(12, 0));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 215, 224)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        setBackground(new Color(250, 251, 253));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));

        JPanel text = new JPanel(new GridLayout(0, 1, 0, 3));
        text.setOpaque(false);
        text.add(new JLabel(title));
        if (!line1.isEmpty()) {
            text.add(new JLabel(line1));
        }
        if (!line2.isEmpty()) {
            text.add(new JLabel(line2));
        }
        add(text, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        capabilityIconLabel.setToolTipText("Model input capabilities (text / audio / vision)");
        right.add(capabilityIconLabel);
        if (deleteAction != null) {
            if (addOnHandler != null && installedModel != null) {
                configureAddOnButton(audioAddOnButton, Modality.AUDIO,
                        "Install the audio encoder (mmproj) so this model accepts audio input",
                        installedModel, addOnHandler);
                configureAddOnButton(visionAddOnButton, Modality.VISION,
                        "Install the vision encoder (mmproj) so this model accepts image input",
                        installedModel, addOnHandler);
                right.add(audioAddOnButton);
                right.add(visionAddOnButton);
            }
            JButton deleteButton = new JButton("Delete");
            deleteButton.addActionListener(event -> deleteAction.run());
            right.add(deleteButton);
        } else {
            JLabel status = new JLabel(running ? "RUNNING" : "INSTALLED");
            status.setOpaque(true);
            status.setForeground(Color.WHITE);
            status.setBackground(running ? new Color(46, 125, 50) : new Color(84, 110, 122));
            status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            right.add(status);
        }
        add(right, BorderLayout.EAST);
    }

    private void configureAddOnButton(JButton button, final Modality modality, String tooltip,
                                      final OllamaModelInfo installedModel, final AddOnHandler handler) {
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        // Disabled until the capabilities have been queried, so "already installed?" is answerable.
        button.setEnabled(false);
        button.addActionListener(event ->
                handler.installAddOn(installedModel, modality, capabilities.contains(modality)));
    }

    /**
     * Apply the capability tags reported by {@code /api/show}: render the modality icons and enable
     * the add-on buttons. Pass an empty list when the server does not report capabilities — the
     * buttons still enable (the user may know better), but no icons are claimed.
     */
    void setCapabilities(List<String> capabilityTags) {
        capabilities = toModalities(capabilityTags);
        capabilitiesKnown = true;
        capabilityIconLabel.setIcon(capabilities.isEmpty()
                ? null : ModalityIcons.forModalities(capabilities));
        audioAddOnButton.setEnabled(true);
        visionAddOnButton.setEnabled(true);
        revalidate();
        repaint();
    }

    /** Map Ollama capability tags onto the modality icon set. */
    private static Set<Modality> toModalities(List<String> tags) {
        Set<Modality> modalities = EnumSet.noneOf(Modality.class);
        if (tags == null) {
            return modalities;
        }
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i) == null ? "" : tags.get(i).toLowerCase();
            if ("completion".equals(tag)) {
                modalities.add(Modality.TEXT);
            } else if ("vision".equals(tag)) {
                modalities.add(Modality.VISION);
            } else if ("audio".equals(tag)) {
                modalities.add(Modality.AUDIO);
            }
        }
        return modalities;
    }

    static OllamaModelCard installed(OllamaModelInfo model, AddOnHandler addOnHandler, Runnable deleteAction) {
        String details = join(model.getDetails().getFamily(), model.getDetails().getParameterSize(),
                model.getDetails().getQuantizationLevel(), model.getDetails().getFormat());
        String meta = join(formatBytes(model.getSize()), shortDate(model.getModifiedAt()), shortDigest(model.getDigest()));
        return new OllamaModelCard(model.getDisplayName(), details, meta, false, model, addOnHandler, deleteAction);
    }

    static OllamaModelCard running(OllamaRunningModelInfo model) {
        String details = join(model.getDetails().getFamily(), model.getDetails().getParameterSize(),
                model.getDetails().getQuantizationLevel(), model.getDetails().getFormat());
        String meta = join("RAM " + formatBytes(model.getSize()), "VRAM " + formatBytes(model.getSizeVram()),
                "expires " + shortDate(model.getExpiresAt()));
        return new OllamaModelCard(model.getDisplayName(), details, meta, true, null, null, null);
    }

    private static String join(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }

    private static String shortDate(String value) {
        return value == null || value.isEmpty() ? "" : value.substring(0, Math.min(19, value.length())).replace('T', ' ');
    }

    private static String shortDigest(String digest) {
        return digest == null || digest.isEmpty() ? "" : "digest " + (digest.length() <= 18 ? digest : digest.substring(0, 18) + "...");
    }

    private static String formatBytes(long value) {
        if (value <= 0L) {
            return "size unknown";
        }
        double size = value;
        String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (size >= 1024.0d && unit < units.length - 1) {
            size = size / 1024.0d;
            unit++;
        }
        return String.format("%.1f %s", size, units[unit]);
    }
}
