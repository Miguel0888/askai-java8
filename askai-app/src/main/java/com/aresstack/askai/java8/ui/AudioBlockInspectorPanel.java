package com.aresstack.askai.java8.ui;

import com.aresstack.audio.pipeline.AudioBlockRegistry;
import com.aresstack.audio.pipeline.AudioParameterChoice;
import com.aresstack.audio.pipeline.AudioParameterDescriptor;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;

import javax.swing.BorderFactory;
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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edit the selected block with type-specific controls and emit one immutable replacement.
 *
 * <p>The function selector is the card title (a borderless dropdown of the block name); the enabled toggle
 * and the type-specific parameters follow, stacked vertically for the narrow block-width card. All field
 * definitions come from the block's descriptor in {@link AudioBlockRegistry} — there is no per-type switch
 * here, so a new block type gets its inspector fields automatically from its descriptor.</p>
 */
public final class AudioBlockInspectorPanel extends JPanel {

    /** Content width the stacked controls target (block-width card minus its horizontal padding). */
    private static final int CONTENT_WIDTH = 132;

    public interface Listener {
        void blockChanged(AudioBlockDefinition block);
    }

    private final AudioBlockRegistry registry = AudioBlockRegistry.getInstance();
    private final JComboBox<AudioBlockType> functionCombo = new JComboBox<AudioBlockType>(AudioBlockType.values());
    private final JCheckBox enabledCheck = new JCheckBox("Enabled");
    private final JPanel parametersPanel = new JPanel();
    private final Map<String, JComponent> parameterEditors = new LinkedHashMap<String, JComponent>();
    private final JButton applyButton = new JButton("Apply block");

    private AudioBlockDefinition block;
    private Listener listener;
    private boolean updating;

    public AudioBlockInspectorPanel() {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        styleFunctionTitle();
        enabledCheck.setOpaque(false);
        enabledCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        parametersPanel.setOpaque(false);
        parametersPanel.setLayout(new BoxLayout(parametersPanel, BoxLayout.Y_AXIS));
        parametersPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        applyButton.setAlignmentX(Component.LEFT_ALIGNMENT);

        add(functionCombo);
        add(Box.createVerticalStrut(4));
        add(enabledCheck);
        add(Box.createVerticalStrut(6));
        add(parametersPanel);
        add(Box.createVerticalStrut(8));
        add(applyButton);

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

    /** Render the function selector as the card title: bold, borderless, just the name plus a dropdown arrow. */
    private void styleFunctionTitle() {
        functionCombo.setOpaque(false);
        functionCombo.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        Font base = functionCombo.getFont();
        functionCombo.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1f));
        functionCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        functionCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, functionCombo.getPreferredSize().height));
        functionCombo.setToolTipText("Change this block's function");
    }

    private void functionChanged() {
        if (updating || block == null) {
            return;
        }
        AudioBlockType type = (AudioBlockType) functionCombo.getSelectedItem();
        if (type != null && type != block.getType()) {
            // Keep the stable block id, take the new type's default parameters (from its descriptor).
            block = registry.defaultDefinition(type, block.getId()).withEnabled(enabledCheck.isSelected());
            rebuildParameterEditors(block);
        }
    }

    private void applyChanges() {
        if (block == null) {
            return;
        }
        AudioBlockDefinition changed = block.withEnabled(enabledCheck.isSelected());
        for (Map.Entry<String, JComponent> entry : parameterEditors.entrySet()) {
            changed = changed.withParameter(entry.getKey(), readValue(entry.getValue()));
        }
        block = changed;
        if (listener != null) {
            listener.blockChanged(changed);
        }
    }

    /** Build the parameter editors from the block descriptor, not a per-type switch. */
    private void rebuildParameterEditors(AudioBlockDefinition selected) {
        parametersPanel.removeAll();
        parameterEditors.clear();
        List<AudioParameterDescriptor> parameters = registry.descriptor(selected.getType()).getParameters();
        if (parameters.isEmpty()) {
            addDescription("This block adapts automatically and has no settings.");
        } else {
            for (int i = 0; i < parameters.size(); i++) {
                addEditor(parameters.get(i), selected);
            }
        }
        parametersPanel.revalidate();
        parametersPanel.repaint();
    }

    /** Create the editor for one parameter from its descriptor and the block's current value. */
    private void addEditor(AudioParameterDescriptor parameter, AudioBlockDefinition selected) {
        JComponent editor;
        switch (parameter.getType()) {
            case INTEGER:
                editor = new JSpinner(new SpinnerNumberModel(
                        selected.getIntParameter(parameter.getKey(), (int) parseDefault(parameter)),
                        (int) parameter.getMinimum(), (int) parameter.getMaximum(), 1));
                break;
            case DECIMAL:
                editor = new JSpinner(new SpinnerNumberModel(
                        selected.getDoubleParameter(parameter.getKey(), parseDefault(parameter)),
                        parameter.getMinimum(), parameter.getMaximum(), parameter.getStep()));
                break;
            case BOOLEAN:
                JCheckBox checkBox = new JCheckBox();
                checkBox.setOpaque(false);
                checkBox.setSelected(selected.getBooleanParameter(parameter.getKey(),
                        Boolean.parseBoolean(parameter.getDefaultValue())));
                editor = checkBox;
                break;
            case CHOICE:
            default:
                editor = buildChoice(parameter, selected);
                break;
        }
        addField(parameter.getKey(), parameter.getLabel(), editor);
    }

    private JComboBox<AudioParameterChoice> buildChoice(AudioParameterDescriptor parameter,
                                                        AudioBlockDefinition selected) {
        List<AudioParameterChoice> choices = parameter.getChoices();
        JComboBox<AudioParameterChoice> combo = new JComboBox<AudioParameterChoice>();
        String current = selected.getParameter(parameter.getKey(), parameter.getDefaultValue());
        for (int i = 0; i < choices.size(); i++) {
            combo.addItem(choices.get(i));
            if (choices.get(i).getValue().equals(current)) {
                combo.setSelectedIndex(i);
            }
        }
        return combo;
    }

    private static double parseDefault(AudioParameterDescriptor parameter) {
        try {
            return Double.parseDouble(parameter.getDefaultValue());
        } catch (NumberFormatException ex) {
            return 0.0d;
        }
    }

    private void addDescription(String text) {
        JLabel label = new JLabel("<html><body style='width:" + CONTENT_WIDTH + "px'>" + text + "</body></html>");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        parametersPanel.add(label);
    }

    /** Add one parameter as a small vertical group (caption over full-width editor). */
    private void addField(String key, String label, JComponent editor) {
        parameterEditors.put(key, editor);

        JPanel group = new JPanel();
        group.setOpaque(false);
        group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
        group.setAlignmentX(Component.LEFT_ALIGNMENT);
        group.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        JLabel caption = new JLabel(label);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        editor.setAlignmentX(Component.LEFT_ALIGNMENT);
        int editorHeight = editor.getPreferredSize().height;
        editor.setPreferredSize(new Dimension(CONTENT_WIDTH, editorHeight));
        editor.setMaximumSize(new Dimension(CONTENT_WIDTH, editorHeight));

        group.add(caption);
        group.add(Box.createVerticalStrut(2));
        group.add(editor);
        parametersPanel.add(group);
    }

    private void showEmptyState() {
        parametersPanel.removeAll();
        parameterEditors.clear();
        addDescription("Select a block in the pipeline.");
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
            if (value instanceof AudioParameterChoice) {
                return ((AudioParameterChoice) value).getValue();
            }
            return value == null ? "" : String.valueOf(value);
        }
        if (component instanceof JCheckBox) {
            return String.valueOf(((JCheckBox) component).isSelected());
        }
        return "";
    }
}
