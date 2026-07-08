package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.client.OllamaModelInfo;
import com.aresstack.askai.java8.client.OllamaRunningModelInfo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;

final class OllamaModelCard extends JPanel {

    private OllamaModelCard(String title, String line1, String line2, boolean running, Runnable deleteAction) {
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

        if (deleteAction != null) {
            JButton deleteButton = new JButton("Delete");
            deleteButton.addActionListener(event -> deleteAction.run());
            add(deleteButton, BorderLayout.EAST);
        } else {
            JLabel status = new JLabel(running ? "RUNNING" : "INSTALLED");
            status.setOpaque(true);
            status.setForeground(Color.WHITE);
            status.setBackground(running ? new Color(46, 125, 50) : new Color(84, 110, 122));
            status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            add(status, BorderLayout.EAST);
        }
    }

    static OllamaModelCard installed(OllamaModelInfo model, Runnable deleteAction) {
        String details = join(model.getDetails().getFamily(), model.getDetails().getParameterSize(),
                model.getDetails().getQuantizationLevel(), model.getDetails().getFormat());
        String meta = join(formatBytes(model.getSize()), shortDate(model.getModifiedAt()), shortDigest(model.getDigest()));
        return new OllamaModelCard(model.getDisplayName(), details, meta, false, deleteAction);
    }

    static OllamaModelCard running(OllamaRunningModelInfo model) {
        String details = join(model.getDetails().getFamily(), model.getDetails().getParameterSize(),
                model.getDetails().getQuantizationLevel(), model.getDetails().getFormat());
        String meta = join("RAM " + formatBytes(model.getSize()), "VRAM " + formatBytes(model.getSizeVram()),
                "expires " + shortDate(model.getExpiresAt()));
        return new OllamaModelCard(model.getDisplayName(), details, meta, true, null);
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
