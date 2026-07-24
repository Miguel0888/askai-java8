package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.hf.SearchFilterState;
import com.aresstack.askai.java8.hf.SearchFilterState.Group;
import com.aresstack.askai.java8.hf.catalog.CatalogEntry;
import com.aresstack.askai.java8.hf.catalog.FilterCatalogs;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The faceted-search filter dialog: a Main quick-overview tab plus Tasks / Libraries / Languages /
 * Licenses / Other detail tabs, all reading and writing one shared {@link SearchFilterState}. Because
 * every checkbox binds to that single object, a selection in Main is reflected in the matching detail
 * tab and vice-versa; the dialog just re-syncs its checkboxes from the state whenever a tab is shown.
 *
 * <p>Parameter range, Inference Providers and Hardware are shown as disabled "Coming Soon" sections:
 * the public HuggingFace API has no working server-side parameter filter (verified), and the others
 * are future AskAI features — none are faked.</p>
 */
public final class FilterDialog extends JDialog {

    private final SearchFilterState state;
    private final FilterCatalogs catalogs;
    private final List<Runnable> syncers = new ArrayList<Runnable>();
    private boolean applied;

    public FilterDialog(Frame owner, SearchFilterState state, FilterCatalogs catalogs) {
        super(owner, "Search filters", true);
        this.state = state;
        this.catalogs = catalogs;
        buildUserInterface();
        setSize(560, 620);
        setLocationRelativeTo(owner);
    }

    /** @return true when the user pressed Apply (the shared state should be re-run/persisted). */
    public boolean isApplied() {
        return applied;
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(0, 0));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Main", buildMainTab());
        tabs.addTab("Tasks", buildCatalogTab(Group.TASKS, catalogs.getTasks(), true));
        tabs.addTab("Libraries", buildCatalogTab(Group.LIBRARIES, catalogs.getLibraries(), false));
        tabs.addTab("Languages", buildCatalogTab(Group.LANGUAGES, catalogs.getLanguages(), false));
        tabs.addTab("Licenses", buildCatalogTab(Group.LICENSES, catalogs.getLicenses(), false));
        tabs.addTab("Other", buildCatalogTab(Group.OTHER, catalogs.getOther(), true));
        // Re-sync every tab's checkboxes from the shared state when the user switches tabs, so a
        // Main-tab quick-pick and its detail-tab checkbox always agree.
        tabs.addChangeListener(event -> syncAll());
        add(tabs, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
    }

    // ------------------------------------------------------------------ footer

    private JComponent buildFooter() {
        JButton resetAll = new JButton("Reset all");
        resetAll.addActionListener(event -> {
            state.resetAll();
            syncAll();
        });
        JButton apply = new JButton("Apply");
        apply.addActionListener(event -> {
            applied = true;
            dispose();
        });
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.add(resetAll);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.add(apply);
        right.add(close);
        footer.add(left, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    // ------------------------------------------------------------------ Main tab

    private JComponent buildMainTab() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        content.add(sectionLabel("Common tasks"));
        content.add(quickPickRow(Group.TASKS, new String[]{
                "text-generation", "image-text-to-text", "audio-text-to-text",
                "automatic-speech-recognition", "text-to-image", "feature-extraction"}));
        content.add(Box.createVerticalStrut(8));

        content.add(sectionLabel("Common libraries"));
        content.add(quickPickRow(Group.LIBRARIES, new String[]{
                "gguf", "safetensors", "transformers", "pytorch", "onnx", "mlx", "diffusers"}));
        content.add(Box.createVerticalStrut(8));

        content.add(sectionLabel("Apps (HuggingFace compatibility hint, not proof of local install)"));
        content.add(quickPickRow(Group.APPS, new String[]{
                "ollama", "llama.cpp", "vllm", "lm-studio", "mlx", "jan"}));
        content.add(Box.createVerticalStrut(8));

        content.add(sectionLabel("Repository"));
        content.add(gatedRow());
        content.add(Box.createVerticalStrut(8));

        content.add(comingSoonSection("Parameters",
                "The public HuggingFace API has no working server-side parameter filter, so a range "
                        + "slider here would only sort a partial page. Deferred until AskAI can compute "
                        + "it locally from repository metadata."));
        content.add(comingSoonSection("Inference Providers",
                "Groq, Novita, Cerebras, Together AI, Fireworks, ... — filtering by hosted provider "
                        + "is planned; no active filter in this build."));
        content.add(comingSoonSection("Hardware",
                "Local GPU / VRAM-aware \"fits fully in VRAM\" marking is a planned AskAI feature; not "
                        + "available yet."));
        content.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(content);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        return scroll;
    }

    private JComponent quickPickRow(Group group, String[] ids) {
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            final JCheckBox box = new JCheckBox(displayNameFor(group, id), state.isSelected(group, id));
            box.addActionListener(event -> state.setSelected(group, id, box.isSelected()));
            syncers.add(new Runnable() {
                public void run() {
                    box.setSelected(state.isSelected(group, id));
                }
            });
            row.add(box);
        }
        return row;
    }

    private JComponent gatedRow() {
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        final JCheckBox gated = new JCheckBox("Gated only", state.isGated());
        gated.setToolTipText("Only repositories that require accepting terms / access (HuggingFace gated=true)");
        gated.addActionListener(event -> state.setGated(gated.isSelected()));
        syncers.add(new Runnable() {
            public void run() {
                gated.setSelected(state.isGated());
            }
        });
        row.add(gated);
        return row;
    }

    private JComponent comingSoonSection(String title, String explanation) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xDD, 0xDD, 0xDD)),
                BorderFactory.createEmptyBorder(6, 0, 6, 0)));
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        JLabel badge = new JLabel("Coming Soon");
        badge.setOpaque(true);
        badge.setBackground(new Color(0x90, 0x90, 0x90));
        badge.setForeground(Color.WHITE);
        badge.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
        header.add(titleLabel);
        header.add(badge);
        JLabel body = new JLabel("<html><body style='width:500px;color:#666'>" + explanation + "</body></html>");
        panel.add(header, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JComponent sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return label;
    }

    // ------------------------------------------------------------------ detail tabs

    /**
     * A detail tab: a "Filter … by name" field, a scroll pane of checkboxes (grouped by category
     * header when {@code grouped}), a live active-count label and a per-group Reset button. Every
     * checkbox and the count re-sync from the shared state.
     */
    private JComponent buildCatalogTab(final Group group, List<CatalogEntry> entries, boolean grouped) {
        final JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        final List<JCheckBox> boxes = new ArrayList<JCheckBox>();
        final List<Component> categoryHeaders = new ArrayList<Component>();
        if (grouped) {
            Map<String, List<CatalogEntry>> byGroup = groupEntries(group, entries);
            for (Map.Entry<String, List<CatalogEntry>> bucket : byGroup.entrySet()) {
                JLabel header = new JLabel(bucket.getKey());
                header.setFont(header.getFont().deriveFont(Font.BOLD));
                header.setAlignmentX(Component.LEFT_ALIGNMENT);
                header.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
                list.add(header);
                categoryHeaders.add(header);
                for (CatalogEntry entry : bucket.getValue()) {
                    JCheckBox box = catalogCheckbox(group, entry);
                    boxes.add(box);
                    list.add(box);
                }
            }
        } else {
            for (CatalogEntry entry : entries) {
                JCheckBox box = catalogCheckbox(group, entry);
                boxes.add(box);
                list.add(box);
            }
        }

        final JLabel countLabel = new JLabel();
        Runnable updateCount = new Runnable() {
            public void run() {
                countLabel.setText(state.count(group) + " selected");
            }
        };
        // Keep this tab's checkboxes and count in step with the shared state.
        syncers.add(new Runnable() {
            public void run() {
                for (int i = 0; i < boxes.size(); i++) {
                    JCheckBox box = boxes.get(i);
                    box.setSelected(state.isSelected(group, (String) box.getClientProperty("filterId")));
                }
                countLabel.setText(state.count(group) + " selected");
            }
        });
        for (int i = 0; i < boxes.size(); i++) {
            attachCountUpdate(boxes.get(i), updateCount);
        }
        updateCount.run();

        JTextField searchField = new JTextField();
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
            private void filter() {
                String needle = searchField.getText().trim().toLowerCase(Locale.ROOT);
                for (int i = 0; i < boxes.size(); i++) {
                    JCheckBox box = boxes.get(i);
                    boolean match = needle.length() == 0 || box.getText().toLowerCase(Locale.ROOT).contains(needle)
                            || String.valueOf(box.getClientProperty("filterId")).toLowerCase(Locale.ROOT).contains(needle);
                    box.setVisible(match);
                }
                // Category headers are only meaningful when unfiltered; hide them while searching.
                for (int i = 0; i < categoryHeaders.size(); i++) {
                    categoryHeaders.get(i).setVisible(needle.length() == 0);
                }
                list.revalidate();
                list.repaint();
            }
        });

        JButton reset = new JButton("Reset");
        reset.addActionListener(event -> {
            state.resetGroup(group);
            syncAll();
        });

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
        top.add(new JLabel("Filter by name:"), BorderLayout.WEST);
        top.add(searchField, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
        bottom.add(countLabel, BorderLayout.WEST);
        JPanel resetWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        resetWrap.add(reset);
        bottom.add(resetWrap, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(list);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel tab = new JPanel(new BorderLayout());
        tab.add(top, BorderLayout.NORTH);
        tab.add(scroll, BorderLayout.CENTER);
        tab.add(bottom, BorderLayout.SOUTH);
        return tab;
    }

    private JCheckBox catalogCheckbox(final Group group, CatalogEntry entry) {
        final String id = entry.getId();
        JCheckBox box = new JCheckBox(entry.getDisplayName(), state.isSelected(group, id));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.putClientProperty("filterId", id);
        box.setToolTipText(id);
        box.addActionListener(event -> state.setSelected(group, id, box.isSelected()));
        return box;
    }

    private void attachCountUpdate(JCheckBox box, final Runnable updateCount) {
        box.addActionListener(event -> updateCount.run());
    }

    private Map<String, List<CatalogEntry>> groupEntries(Group group, List<CatalogEntry> entries) {
        if (group == Group.TASKS) {
            return catalogs.getTasksByCategory();
        }
        if (group == Group.OTHER) {
            return catalogs.getOtherBySubgroup();
        }
        // Fallback: single unnamed bucket preserving order.
        java.util.LinkedHashMap<String, List<CatalogEntry>> single = new java.util.LinkedHashMap<String, List<CatalogEntry>>();
        single.put("", entries);
        return single;
    }

    private String displayNameFor(Group group, String id) {
        List<CatalogEntry> entries = catalogForGroup(group);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getId().equals(id)) {
                return entries.get(i).getDisplayName();
            }
        }
        return id;
    }

    private List<CatalogEntry> catalogForGroup(Group group) {
        switch (group) {
            case TASKS: return catalogs.getTasks();
            case LIBRARIES: return catalogs.getLibraries();
            case LANGUAGES: return catalogs.getLanguages();
            case LICENSES: return catalogs.getLicenses();
            case OTHER: return catalogs.getOther();
            case APPS: return appCatalog();
            default: return new ArrayList<CatalogEntry>();
        }
    }

    /** Apps aren't in the resource catalogs (they are a small curated Main-tab set); labels here. */
    private static List<CatalogEntry> appCatalog() {
        List<CatalogEntry> apps = new ArrayList<CatalogEntry>();
        apps.add(new CatalogEntry("ollama", "Ollama", ""));
        apps.add(new CatalogEntry("llama.cpp", "llama.cpp", ""));
        apps.add(new CatalogEntry("vllm", "vLLM", ""));
        apps.add(new CatalogEntry("lm-studio", "LM Studio", ""));
        apps.add(new CatalogEntry("mlx", "MLX LM", ""));
        apps.add(new CatalogEntry("jan", "Jan", ""));
        return apps;
    }

    private void syncAll() {
        for (int i = 0; i < syncers.size(); i++) {
            syncers.get(i).run();
        }
    }
}
