package com.aresstack.askai.java8.ui;

import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Edit the selected block with type-specific controls and emit one immutable replacement.
 *
 * <p>Laid out for a WIDE, short card below the pipeline: the function/enabled/apply controls sit on one
 * header row, and the type-specific parameters flow left-to-right as small labelled groups (wrapping only
 * when the card is too narrow), instead of a tall right-hand column.</p>
 */
public final class AudioBlockInspectorPanel extends JPanel {

    public interface Listener {
        void blockChanged(AudioBlockDefinition block);
    }

    private final JComboBox<AudioBlockType> functionCombo = new JComboBox<AudioBlockType>(AudioBlockType.values());
    private final JCheckBox enabledCheck = new JCheckBox("Enabled");
    private final JPanel parametersPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 16, 6));
    private final Map<String, JComponent> parameterEditors = new LinkedHashMap<String, JComponent>();
    private final JButton applyButton = new JButton("Apply block");

    private AudioBlockDefinition block;
    private Listener listener;
    private boolean updating;

    public AudioBlockInspectorPanel() {
        setLayout(new BorderLayout(6, 6));
        setOpaque(false);
        parametersPanel.setOpaque(false);
        add(buildHeader(), BorderLayout.NORTH);
        add(parametersPanel, BorderLayout.CENTER);
        functionCombo.addActionListener(event -> functionChanged());
        applyButton.addActionListener(event -> applyChanges());
        showEmptyState();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setBlock(AudioBlockDefinition block) {
        this.block = block;
        updating = true;
        try {
            if (block == null) {
                showEmptyState();
                return;
            }
            setControlsEnabled(true);
            functionCombo.setSelectedItem(block.getType());
            enabledCheck.setSelected(block.isEnabled());
            rebuildParameterEditors(block);
        } finally {
            updating = false;
        }
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        header.setOpaque(false);
        header.add(new JLabel("Selected block"));
        header.add(new JLabel("·"));
        header.add(new JLabel("Function"));
        functionCombo.setPreferredSize(new Dimension(190, functionCombo.getPreferredSize().height));
        header.add(functionCombo);
        header.add(enabledCheck);
        header.add(Box.createHorizontalStrut(12));
        header.add(applyButton);
        return header;
    }

    private void functionChanged() {
        if (updating || block == null) {
            return;
        }
        AudioBlockType type = (AudioBlockType) functionCombo.getSelectedItem();
        if (type != null && type != block.getType()) {
            block = block.withType(type);
            rebuildParameterEditors(block);
        }
    }

    private void applyChanges() {
        if (block == null) {
            return;
        }
        AudioBlockType type = (AudioBlockType) functionCombo.getSelectedItem();
        AudioBlockDefinition changed = type == block.getType() ? block : block.withType(type);
        changed = changed.withEnabled(enabledCheck.isSelected());
        for (Map.Entry<String, JComponent> entry : parameterEditors.entrySet()) {
            changed = changed.withParameter(entry.getKey(), readValue(entry.getValue()));
        }
        block = changed;
        if (listener != null) {
            listener.blockChanged(changed);
        }
    }

    private void rebuildParameterEditors(AudioBlockDefinition selected) {
        parametersPanel.removeAll();
        parameterEditors.clear();
        switch (selected.getType()) {
            case CHANNEL_MIXER:
                addInteger("channels", "Output channels", selected, 1, 1, 1);
                break;
            case LOW_PASS:
                addChoice("implementation", "Filter design", selected, new ParameterChoice[]{
                        new ParameterChoice("FIR_65", "65-tap FIR (existing)"),
                        new ParameterChoice("BUTTERWORTH", "Butterworth (iirj)")});
                addDouble("cutoffHz", "Cutoff (Hz)", selected, 7200.0d, 1.0d, 96000.0d, 10.0d);
                addInteger("order", "Butterworth order", selected, 4, 1, 12);
                break;
            case HIGH_PASS:
                addChoice("implementation", "Filter design", selected, new ParameterChoice[]{
                        new ParameterChoice("LEGACY_IIR", "First-order IIR (existing)"),
                        new ParameterChoice("BUTTERWORTH", "Butterworth (iirj)")});
                addDouble("cutoffHz", "Cutoff (Hz)", selected, 80.0d, 1.0d, 96000.0d, 10.0d);
                addInteger("order", "Butterworth order", selected, 2, 1, 12);
                break;
            case BAND_PASS:
            case BAND_STOP:
                addDouble("centerHz", "Center (Hz)", selected, 1000.0d, 1.0d, 96000.0d, 10.0d);
                addDouble("widthHz", "Width (Hz)", selected, 500.0d, 1.0d, 96000.0d, 10.0d);
                addInteger("order", "Filter order", selected, 2, 1, 12);
                break;
            case RESAMPLER:
                addInteger("targetRateHz", "Target rate (Hz)", selected, 16000, 4000, 192000);
                addChoice("quality", "Quality", selected, new String[]{"FAST", "BALANCED", "HIGH"});
                addBoolean("hiddenAntiAliasing", "Hidden anti-alias filter", selected, false);
                break;
            case NOISE_GATE:
                addDouble("threshold", "Threshold", selected, 300.0d, 0.0d, 32767.0d, 10.0d);
                addDouble("closedGain", "Closed gain", selected, 0.3d, 0.0d, 1.0d, 0.05d);
                addDouble("attackMillis", "Attack (ms)", selected, 5.0d, 0.0d, 5000.0d, 1.0d);
                addDouble("releaseMillis", "Release (ms)", selected, 150.0d, 0.0d, 10000.0d, 5.0d);
                break;
            case COMPRESSOR:
                addDouble("threshold", "Threshold", selected, 12000.0d, 0.0d, 32767.0d, 100.0d);
                addDouble("ratio", "Ratio", selected, 3.0d, 1.0d, 30.0d, 0.5d);
                addDouble("attackMillis", "Attack (ms)", selected, 5.0d, 0.0d, 5000.0d, 1.0d);
                addDouble("releaseMillis", "Release (ms)", selected, 100.0d, 0.0d, 10000.0d, 5.0d);
                break;
            case LIMITER:
                addInteger("ceiling", "Ceiling", selected, 30000, 1, 32767);
                break;
            case DC_OFFSET_REMOVAL:
                addDescription("This block adapts automatically and has no exposed parameter.");
                break;
            default:
                break;
        }
        parametersPanel.revalidate();
        parametersPanel.repaint();
    }

    private void addInteger(String key, String label, AudioBlockDefinition selected,
                            int fallback, int minimum, int maximum) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(
                selected.getIntParameter(key, fallback), minimum, maximum, 1));
        addEditor(key, label, spinner);
    }

    private void addDouble(String key, String label, AudioBlockDefinition selected,
                           double fallback, double minimum, double maximum, double step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(
                selected.getDoubleParameter(key, fallback), minimum, maximum, step));
        addEditor(key, label, spinner);
    }

    private void addChoice(String key, String label, AudioBlockDefinition selected, String[] values) {
        JComboBox<String> combo = new JComboBox<String>(values);
        combo.setSelectedItem(selected.getParameter(key, values[0]));
        addEditor(key, label, combo);
    }

    private void addChoice(String key, String label, AudioBlockDefinition selected, ParameterChoice[] values) {
        JComboBox<ParameterChoice> combo = new JComboBox<ParameterChoice>(values);
        String selectedValue = selected.getParameter(key, values[0].getValue());
        for (int i = 0; i < values.length; i++) {
            if (values[i].getValue().equals(selectedValue)) {
                combo.setSelectedIndex(i);
                break;
            }
        }
        addEditor(key, label, combo);
    }

    private void addBoolean(String key, String label, AudioBlockDefinition selected, boolean fallback) {
        JCheckBox checkBox = new JCheckBox();
        checkBox.setSelected(selected.getBooleanParameter(key, fallback));
        addEditor(key, label, checkBox);
    }

    private void addDescription(String text) {
        JLabel label = new JLabel("<html>" + text + "</html>");
        label.setPreferredSize(new Dimension(320, label.getPreferredSize().height));
        parametersPanel.add(label);
    }

    /** Add one parameter as a small vertical group (caption over editor) that flows across the width. */
    private void addEditor(String key, String label, JComponent editor) {
        parameterEditors.put(key, editor);
        Dimension pref = editor.getPreferredSize();
        editor.setPreferredSize(new Dimension(Math.max(150, pref.width), pref.height));

        JPanel group = new JPanel();
        group.setOpaque(false);
        group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
        JLabel caption = new JLabel(label);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        editor.setAlignmentX(Component.LEFT_ALIGNMENT);
        group.add(caption);
        group.add(Box.createVerticalStrut(2));
        group.add(editor);
        parametersPanel.add(group);
    }

    private void showEmptyState() {
        parametersPanel.removeAll();
        parameterEditors.clear();
        parametersPanel.add(new JLabel("Select a block in the pipeline."));
        setControlsEnabled(false);
        parametersPanel.revalidate();
        parametersPanel.repaint();
    }

    private void setControlsEnabled(boolean enabled) {
        functionCombo.setEnabled(enabled);
        enabledCheck.setEnabled(enabled);
        applyButton.setEnabled(enabled);
    }

    private static String readValue(JComponent component) {
        if (component instanceof JSpinner) {
            return String.valueOf(((JSpinner) component).getValue());
        }
        if (component instanceof JComboBox) {
            Object value = ((JComboBox<?>) component).getSelectedItem();
            if (value instanceof ParameterChoice) {
                return ((ParameterChoice) value).getValue();
            }
            return value == null ? "" : String.valueOf(value);
        }
        if (component instanceof JCheckBox) {
            return String.valueOf(((JCheckBox) component).isSelected());
        }
        return "";
    }

    private static final class ParameterChoice {
        private final String value;
        private final String label;

        private ParameterChoice(String value, String label) {
            this.value = value;
            this.label = label;
        }

        private String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
