package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.audio.AudioProfileRepository;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Configure reusable audio profiles with a Java2D pipeline canvas and a type-specific inspector. */
public final class AudioProcessingPanel extends JPanel {

    private final AudioProfileRepository repository;
    private final JComboBox<AudioProcessingProfile> profileCombo = new JComboBox<AudioProcessingProfile>();
    private final JButton saveButton = new JButton("Save");
    private final JButton saveAsButton = new JButton("Save as…");
    private final JButton deleteButton = new JButton("Delete");
    private final JButton resetButton = new JButton("Reset changes");
    private final JButton addButton = new JButton("Add block");
    private final JButton removeButton = new JButton("Remove block");
    private final JLabel statusLabel = new JLabel(" ");
    private final AudioPipelineCanvas canvas = new AudioPipelineCanvas();
    private final AudioBlockInspectorPanel inspector = new AudioBlockInspectorPanel();
    private final AudioInspectorCard inspectorCard =
            new AudioInspectorCard(inspector, AudioPipelineCanvas.BLOCK_WIDTH);

    private AudioProcessingProfile selectedProfile;
    private List<AudioBlockDefinition> workingBlocks = new ArrayList<AudioBlockDefinition>();
    private boolean dirty;
    private boolean updatingProfileCombo;

    public AudioProcessingPanel(AudioProfileRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository must not be null.");
        }
        this.repository = repository;
        buildUserInterface();
        wireActions();
        reloadProfiles(null);
    }

    public void refreshProfiles() {
        String selectedId = selectedProfile == null ? null : selectedProfile.getId();
        reloadProfiles(selectedId);
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildEditor(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        JPanel profileArea = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        profileArea.add(new JLabel("Profile"));
        profileCombo.setPreferredSize(new Dimension(260, profileCombo.getPreferredSize().height));
        profileArea.add(profileCombo);
        profileArea.add(saveButton);
        profileArea.add(saveAsButton);
        profileArea.add(deleteButton);
        profileArea.add(resetButton);

        JPanel blockArea = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        blockArea.add(addButton);
        blockArea.add(removeButton);
        toolbar.add(profileArea, BorderLayout.CENTER);
        toolbar.add(blockArea, BorderLayout.EAST);
        return toolbar;
    }

    private JPanel buildEditor() {
        JPanel editor = new JPanel(new BorderLayout(8, 8));
        // The settings card lives INSIDE the canvas, directly under the selected block, so the pipeline sits
        // at the top and the card scrolls together with it — no scroll bar between the two.
        canvas.setInspectorCard(inspectorCard);
        JScrollPane canvasScroll = new JScrollPane(canvas,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        canvasScroll.setBorder(BorderFactory.createEmptyBorder());
        canvasScroll.getViewport().setBackground(canvas.getBackground());
        editor.add(canvasScroll, BorderLayout.CENTER);
        return editor;
    }

    private void wireActions() {
        profileCombo.addActionListener(event -> profileSelectionChanged());
        saveButton.addActionListener(event -> saveCurrentProfile());
        saveAsButton.addActionListener(event -> saveAsProfile());
        deleteButton.addActionListener(event -> deleteCurrentProfile());
        resetButton.addActionListener(event -> resetWorkingCopy());
        addButton.addActionListener(event -> addBlock());
        removeButton.addActionListener(event -> removeSelectedBlock());

        canvas.setListener(new AudioPipelineCanvas.Listener() {
            public void selectionChanged(int selectedIndex) {
                inspect(selectedIndex);
                updateButtons();
            }

            public void orderChanged(List<AudioBlockDefinition> blocks, int selectedIndex) {
                workingBlocks = new ArrayList<AudioBlockDefinition>(blocks);
                markDirty("Block order changed.");
                inspect(selectedIndex);
            }
        });
        inspector.setListener(new AudioBlockInspectorPanel.Listener() {
            public void blockChanged(AudioBlockDefinition block) {
                replaceSelectedBlock(block);
            }
        });
    }

    private void profileSelectionChanged() {
        if (updatingProfileCombo) {
            return;
        }
        AudioProcessingProfile requested = (AudioProcessingProfile) profileCombo.getSelectedItem();
        if (requested == null || requested == selectedProfile) {
            return;
        }
        if (dirty && !confirmDiscardChanges()) {
            selectProfileInCombo(selectedProfile == null ? null : selectedProfile.getId());
            return;
        }
        loadWorkingCopy(requested);
    }

    private void loadWorkingCopy(AudioProcessingProfile profile) {
        selectedProfile = profile;
        workingBlocks = new ArrayList<AudioBlockDefinition>(profile.getBlocks());
        dirty = false;
        canvas.setBlocks(workingBlocks);
        canvas.setSelectedIndex(workingBlocks.isEmpty() ? -1 : 0);
        inspect(canvas.getSelectedIndex());
        setStatus(profile.isBuiltIn()
                ? "The default profile is editable here but can only be stored under a new name."
                : "Profile loaded.");
        updateButtons();
    }

    private void saveCurrentProfile() {
        if (selectedProfile == null || selectedProfile.isBuiltIn()) {
            saveAsProfile();
            return;
        }
        AudioProcessingProfile changed = selectedProfile.withBlocks(workingBlocks);
        try {
            repository.save(changed);
            selectedProfile = changed;
            dirty = false;
            reloadProfiles(changed.getId());
            setStatus("Profile saved.");
        } catch (IOException ex) {
            showError("Could not save the profile.", ex);
        }
    }

    private void saveAsProfile() {
        if (selectedProfile == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Name for the new audio profile:",
                selectedProfile.getName() + " copy");
        if (name == null) {
            return;
        }
        try {
            AudioProcessingProfile working = selectedProfile.withBlocks(workingBlocks);
            AudioProcessingProfile saved = repository.saveAs(working, name);
            dirty = false;
            reloadProfiles(saved.getId());
            setStatus("Profile saved as “" + saved.getName() + "”.");
        } catch (Exception ex) {
            showError("Could not save the new profile.", ex);
        }
    }

    private void deleteCurrentProfile() {
        if (selectedProfile == null || selectedProfile.isBuiltIn()) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete the audio profile “" + selectedProfile.getName() + "”?",
                "Delete audio profile", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            repository.delete(selectedProfile.getId());
            dirty = false;
            reloadProfiles(null);
            setStatus("Profile deleted.");
        } catch (IOException ex) {
            showError("Could not delete the profile.", ex);
        }
    }

    private void resetWorkingCopy() {
        if (selectedProfile == null) {
            return;
        }
        loadWorkingCopy(repository.findById(selectedProfile.getId()));
        setStatus("Unsaved changes discarded.");
    }

    private void addBlock() {
        AudioBlockDefinition block = com.aresstack.audio.pipeline.AudioBlockRegistry.getInstance()
                .defaultDefinition(AudioBlockType.LOW_PASS, "block-" + UUID.randomUUID().toString());
        workingBlocks.add(block);
        canvas.setBlocks(workingBlocks);
        canvas.setSelectedIndex(workingBlocks.size() - 1);
        inspect(workingBlocks.size() - 1);
        markDirty("Block added. Choose its function in the inspector.");
    }

    private void removeSelectedBlock() {
        int index = canvas.getSelectedIndex();
        if (index < 0 || index >= workingBlocks.size()) {
            return;
        }
        workingBlocks.remove(index);
        canvas.setBlocks(workingBlocks);
        int next = workingBlocks.isEmpty() ? -1 : Math.min(index, workingBlocks.size() - 1);
        canvas.setSelectedIndex(next);
        inspect(next);
        markDirty("Block removed.");
    }

    private void replaceSelectedBlock(AudioBlockDefinition block) {
        int index = canvas.getSelectedIndex();
        if (index < 0 || index >= workingBlocks.size()) {
            return;
        }
        workingBlocks.set(index, block);
        canvas.setBlocks(workingBlocks);
        canvas.setSelectedIndex(index);
        inspect(index);
        markDirty("Block settings changed.");
    }

    private void inspect(int index) {
        AudioBlockDefinition block = index >= 0 && index < workingBlocks.size()
                ? workingBlocks.get(index) : null;
        inspector.setBlock(block);
        // Roll the settings card down under the selected block, or up again when nothing is selected.
        inspectorCard.setExpanded(block != null);
        canvas.revalidate();
    }

    private void reloadProfiles(String selectedId) {
        List<AudioProcessingProfile> profiles = repository.findAll();
        updatingProfileCombo = true;
        try {
            profileCombo.removeAllItems();
            for (int i = 0; i < profiles.size(); i++) {
                profileCombo.addItem(profiles.get(i));
            }
            AudioProcessingProfile target = findProfile(profiles, selectedId);
            profileCombo.setSelectedItem(target);
            loadWorkingCopy(target);
        } finally {
            updatingProfileCombo = false;
        }
    }

    private static AudioProcessingProfile findProfile(List<AudioProcessingProfile> profiles, String id) {
        if (id != null) {
            for (int i = 0; i < profiles.size(); i++) {
                if (id.equals(profiles.get(i).getId())) {
                    return profiles.get(i);
                }
            }
        }
        return profiles.get(0);
    }

    private void selectProfileInCombo(String id) {
        updatingProfileCombo = true;
        try {
            for (int i = 0; i < profileCombo.getItemCount(); i++) {
                AudioProcessingProfile profile = profileCombo.getItemAt(i);
                if (profile != null && profile.getId().equals(id)) {
                    profileCombo.setSelectedIndex(i);
                    break;
                }
            }
        } finally {
            updatingProfileCombo = false;
        }
    }

    private boolean confirmDiscardChanges() {
        return JOptionPane.showConfirmDialog(this,
                "Discard the unsaved changes to this profile?",
                "Unsaved audio profile", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void markDirty(String message) {
        dirty = true;
        setStatus(message);
        updateButtons();
    }

    private void updateButtons() {
        boolean hasProfile = selectedProfile != null;
        saveButton.setEnabled(hasProfile && dirty && !selectedProfile.isBuiltIn());
        saveAsButton.setEnabled(hasProfile);
        deleteButton.setEnabled(hasProfile && !selectedProfile.isBuiltIn());
        resetButton.setEnabled(hasProfile && dirty);
        removeButton.setEnabled(canvas.getSelectedIndex() >= 0);
    }

    private void setStatus(String text) {
        statusLabel.setText(text == null || text.isEmpty() ? " " : text);
    }

    private void showError(String message, Exception ex) {
        JOptionPane.showMessageDialog(this, message + "\n" + ex.getMessage(),
                "Audio profile", JOptionPane.ERROR_MESSAGE);
    }
}
