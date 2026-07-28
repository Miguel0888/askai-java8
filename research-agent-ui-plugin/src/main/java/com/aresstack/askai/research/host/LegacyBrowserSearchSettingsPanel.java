package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.search.DefaultLegacyBrowserSearchSettingsValidator;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCatalog;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec;
import com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot;
import com.aresstack.askai.browser.search.SettingsValidationResult;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Legacy-Browser-Search settings surface (A2d), generated from the
 * {@link LegacyBrowserSearchSettingsCatalog} so every field automatically shows its NAME,
 * description, EFFECTIVE value, DEFAULT value, allowed range and a reset-to-default. Prompts get a
 * multi-line editor whose value IS the productive default text (never an empty editor with a
 * placeholder) plus the available template variables. Saving validates hard and shows the concrete
 * violations; changes apply to NEW research sessions only — the running session keeps its immutable
 * {@link SearchProcessingProfileSnapshot}, which is displayed here.
 */
final class LegacyBrowserSearchSettingsPanel extends JPanel {

    private final WorkspaceStateStore store;
    private final Map<String, String> defaults;
    private final Map<String, FieldEditor> editors = new LinkedHashMap<String, FieldEditor>();
    private final JTextArea problems = new JTextArea(4, 60);
    private final JLabel revisionLabel = new JLabel();

    LegacyBrowserSearchSettingsPanel(WorkspaceStateStore store,
                                     SearchProcessingProfileSnapshot activeProfile) {
        super(new BorderLayout(8, 8));
        this.store = store;
        this.defaults = LegacyBrowserSearchSettingsCodec
                .toValues(LegacyBrowserSearchDefaults.create());

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(bold(new JLabel("Legacy Browser Search")));
        header.add(gray(new JLabel(
                "Changes apply to NEW research sessions only — a running session keeps its own "
                        + "immutable settings snapshot.")));
        if (activeProfile != null) {
            header.add(gray(new JLabel("This session uses profile '" + activeProfile.profileId
                    + "', revision " + activeProfile.profileRevision
                    + " (digest " + shortDigest(activeProfile.settingsDigest) + ").")));
        }
        header.add(revisionLabel);
        refreshRevisionLabel();
        add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        Map<String, JPanel> sectionPanels = new LinkedHashMap<String, JPanel>();
        for (String section : LegacyBrowserSearchSettingsCatalog.sections()) {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            sectionPanels.put(section, panel);
            JScrollPane scroll = new JScrollPane(panel);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            tabs.addTab(section, scroll);
        }
        Map<String, String> effective = effectiveValues();
        for (LegacyBrowserSearchSettingsCatalog.Field field
                : LegacyBrowserSearchSettingsCatalog.fields()) {
            sectionPanels.get(field.section).add(fieldRow(field, effective.get(field.key)));
            sectionPanels.get(field.section).add(Box.createVerticalStrut(10));
        }
        add(tabs, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        problems.setEditable(false);
        problems.setForeground(new Color(150, 30, 30));
        problems.setLineWrap(true);
        problems.setWrapStyleWord(true);
        south.add(new JScrollPane(problems), BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        JButton save = new JButton("Save");
        save.addActionListener(e -> save());
        JButton resetAll = new JButton("Reset all to defaults");
        resetAll.addActionListener(e -> {
            for (Map.Entry<String, FieldEditor> entry : editors.entrySet()) {
                entry.getValue().set(defaults.get(entry.getKey()));
            }
        });
        buttons.add(save);
        buttons.add(resetAll);
        south.add(buttons, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
    }

    private Map<String, String> effectiveValues() {
        Map<String, String> effective = new LinkedHashMap<String, String>(defaults);
        effective.putAll(LegacyBrowserSearchSettingsStore.loadValues(store));
        return effective;
    }

    private JComponent fieldRow(final LegacyBrowserSearchSettingsCatalog.Field field, String value) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 220, 220)));
        row.add(bold(new JLabel(field.label)));
        row.add(gray(new JLabel("<html>" + escape(field.description) + "</html>")));

        final FieldEditor editor = createEditor(field, value);
        editors.put(field.key, editor);
        JComponent component = editor.component();
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(component);

        StringBuilder meta = new StringBuilder("Default: " + display(defaults.get(field.key)));
        String range = rangeText(field);
        if (!range.isEmpty()) {
            meta.append("   Range: ").append(range);
        }
        if (!field.templateVariables.isEmpty()) {
            meta.append("   Variables: ").append(field.templateVariables);
        }
        JPanel metaRow = new JPanel();
        metaRow.setLayout(new BoxLayout(metaRow, BoxLayout.X_AXIS));
        metaRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        metaRow.add(gray(new JLabel(meta.toString())));
        metaRow.add(Box.createHorizontalStrut(12));
        JButton reset = new JButton("Reset");
        reset.setFont(reset.getFont().deriveFont(11f));
        reset.addActionListener(e -> editor.set(defaults.get(field.key)));
        metaRow.add(reset);
        metaRow.add(Box.createHorizontalGlue());
        row.add(metaRow);
        return row;
    }

    private FieldEditor createEditor(LegacyBrowserSearchSettingsCatalog.Field field, String value) {
        switch (field.kind) {
            case BOOLEAN:
                final JCheckBox box = new JCheckBox("enabled", "true".equals(value));
                return new FieldEditor(box) {
                    String get() { return String.valueOf(box.isSelected()); }
                    void set(String v) { box.setSelected("true".equals(v)); }
                };
            case CHOICE:
                final JComboBox<String> combo =
                        new JComboBox<String>(field.choices.toArray(new String[0]));
                combo.setSelectedItem(value);
                combo.setMaximumSize(combo.getPreferredSize());
                return new FieldEditor(combo) {
                    String get() { return String.valueOf(combo.getSelectedItem()); }
                    void set(String v) { combo.setSelectedItem(v); }
                };
            case TEXT_LIST:
            case PROMPT:
                final JTextArea area = new JTextArea(value,
                        field.kind == LegacyBrowserSearchSettingsCatalog.Kind.PROMPT ? 8 : 5, 60);
                area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                final JScrollPane scroll = new JScrollPane(area);
                scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
                return new FieldEditor(scroll) {
                    String get() { return area.getText(); }
                    void set(String v) { area.setText(v); }
                };
            default:
                final JTextField text = new JTextField(value, 24);
                text.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
                        text.getPreferredSize().height));
                return new FieldEditor(text) {
                    String get() { return text.getText(); }
                    void set(String v) { text.setText(v); }
                };
        }
    }

    private void save() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Map.Entry<String, FieldEditor> entry : editors.entrySet()) {
            values.put(entry.getKey(), entry.getValue().get());
        }
        LegacyBrowserSearchSettingsCodec.Decoded decoded =
                LegacyBrowserSearchSettingsCodec.fromValues(values);
        java.util.List<SettingsValidationResult.Violation> all =
                new java.util.ArrayList<SettingsValidationResult.Violation>(decoded.violations);
        all.addAll(new DefaultLegacyBrowserSearchSettingsValidator()
                .validate(decoded.settings).violations);
        if (!all.isEmpty()) {
            // Invalid settings are NEVER silently corrected or saved — the user sees each violation.
            problems.setForeground(new Color(150, 30, 30));
            problems.setText("Not saved — please fix:\n"
                    + new SettingsValidationResult(all).describe());
            return;
        }
        LegacyBrowserSearchSettingsStore.saveValues(store, values);
        refreshRevisionLabel();
        problems.setText("Saved. New research sessions will use revision "
                + LegacyBrowserSearchSettingsStore.revision(store) + ".");
        problems.setForeground(new Color(30, 110, 30));
    }

    private void refreshRevisionLabel() {
        gray(revisionLabel).setText("Stored global settings revision: "
                + LegacyBrowserSearchSettingsStore.revision(store) + ".");
    }

    private String rangeText(LegacyBrowserSearchSettingsCatalog.Field field) {
        boolean hasMin = !Double.isNaN(field.min);
        boolean hasMax = !Double.isNaN(field.max);
        if (!hasMin && !hasMax) {
            return "";
        }
        String min = hasMin ? trimNumber(field.min) : "";
        String max = hasMax ? trimNumber(field.max) : "unbounded";
        return min + " .. " + max;
    }

    private static String trimNumber(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String display(String defaultValue) {
        if (defaultValue == null || defaultValue.isEmpty()) {
            return "(empty)";
        }
        String flat = defaultValue.replace("\n", " | ");
        return flat.length() > 80 ? flat.substring(0, 77) + "..." : flat;
    }

    private static String shortDigest(String digest) {
        return digest.length() > 12 ? digest.substring(0, 12) : digest;
    }

    private static JLabel bold(JLabel label) {
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static JLabel gray(JLabel label) {
        label.setForeground(new Color(110, 110, 110));
        label.setFont(label.getFont().deriveFont(11f));
        return label;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** One editor per field: a component plus canonical get/set in codec string form. */
    private abstract static class FieldEditor {
        private final JComponent component;

        FieldEditor(JComponent component) {
            this.component = component;
        }

        JComponent component() {
            return component;
        }

        abstract String get();

        abstract void set(String value);
    }
}
