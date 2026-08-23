package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.search.engine.BrowserSearchEngine;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The search-engine list: which engines take part, and in which order they are tried. The order in this
 * list IS the execution order — moving DuckDuckGo above Bing means DuckDuckGo is asked first, not that a
 * hint was recorded somewhere.
 * <p>
 * Engines the catalog knows but the stored configuration does not mention (a newly shipped one) appear at
 * the end, switched OFF: gaining a search engine should be the user's decision, not a side effect of an
 * update.
 */
final class SearchEngineOrderEditor extends JPanel {

    private static final class Row {
        final String engineId;
        final String displayName;
        boolean enabled;

        Row(String engineId, String displayName, boolean enabled) {
            this.engineId = engineId;
            this.displayName = displayName;
            this.enabled = enabled;
        }
    }

    private final DefaultListModel<Row> model = new DefaultListModel<Row>();
    private final JList<Row> list = new JList<Row>(model);

    SearchEngineOrderEditor(String encodedValue) {
        super(new BorderLayout(6, 0));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(4);
        list.setCellRenderer(new ListCellRenderer<Row>() {
            private final JCheckBox box = new JCheckBox();

            public Component getListCellRendererComponent(JList<? extends Row> l, Row value, int index,
                                                          boolean selected, boolean focused) {
                box.setText(value.displayName);
                box.setSelected(value.enabled);
                box.setOpaque(true);
                box.setBackground(selected ? l.getSelectionBackground() : l.getBackground());
                box.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
                return box;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (index < 0 || !list.getCellBounds(index, index).contains(event.getPoint())) {
                    return;
                }
                Row row = model.get(index);
                row.enabled = !row.enabled;
                list.repaint();
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(320, 90));
        scroll.setMaximumSize(new Dimension(420, 120));
        add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        buttons.add(moveButton("↑", -1));
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(moveButton("↓", 1));
        buttons.add(Box.createVerticalGlue());
        add(buttons, BorderLayout.EAST);

        set(encodedValue);
    }

    private JButton moveButton(String label, final int delta) {
        JButton button = new JButton(label);
        button.setMargin(new java.awt.Insets(1, 6, 1, 6));
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                move(delta);
            }
        });
        return button;
    }

    private void move(int delta) {
        int index = list.getSelectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= model.size()) {
            return;
        }
        Row row = model.remove(index);
        model.add(target, row);
        list.setSelectedIndex(target);
    }

    /** The flat form the settings codec stores: {@code "duckduckgo:on,bing:off"}, order significant. */
    String get() {
        List<BrowserSearchEngineSelection.Entry> entries =
                new ArrayList<BrowserSearchEngineSelection.Entry>();
        for (int i = 0; i < model.size(); i++) {
            Row row = model.get(i);
            entries.add(new BrowserSearchEngineSelection.Entry(row.engineId, row.enabled));
        }
        return new BrowserSearchEngineSelection(entries, null).encodeEntries();
    }

    void set(String encodedValue) {
        model.clear();
        List<String> placed = new ArrayList<String>();
        for (BrowserSearchEngineSelection.Entry entry
                : BrowserSearchEngineSelection.parseEntries(encodedValue)) {
            BrowserSearchEngine engine = BrowserSearchEngineCatalog.byId(entry.getEngineId());
            if (engine == null) {
                continue; // an engine this build does not know: keep it out of the user's way
            }
            model.addElement(new Row(engine.getId(), engine.getDisplayName(), entry.isEnabled()));
            placed.add(engine.getId());
        }
        for (BrowserSearchEngine engine : BrowserSearchEngineCatalog.engines()) {
            if (!placed.contains(engine.getId())) {
                model.addElement(new Row(engine.getId(), engine.getDisplayName(), false));
            }
        }
    }
}
