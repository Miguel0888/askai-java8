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
        /** Result pages one search fetches from this engine (the user's per-engine setting). */
        int resultPages;
        /** Pause before every further request to this engine, in milliseconds (0 = off). */
        int delayMillis;

        Row(String engineId, String displayName, boolean enabled, int resultPages, int delayMillis) {
            this.engineId = engineId;
            this.displayName = displayName;
            this.enabled = enabled;
            this.resultPages = resultPages;
            this.delayMillis = delayMillis;
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
                String delay = value.delayMillis > 0
                        ? ", Pause " + BrowserSearchEngineSelection
                                .formatDelaySeconds(value.delayMillis).replace('.', ',') + " s"
                        : "";
                box.setText(value.displayName + "  — " + value.resultPages
                        + (value.resultPages == 1 ? " Seite" : " Seiten") + delay);
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
        buttons.add(Box.createVerticalStrut(8));
        pagesSpinner.setToolTipText("Wie viele Ergebnisseiten dieser Suchmaschine eine Suche abruft "
                + "(sequenziell, mit Auswertung zwischen den Abrufen)");
        pagesSpinner.setMaximumSize(new Dimension(64, 26));
        pagesSpinner.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent event) {
                int index = list.getSelectedIndex();
                if (index >= 0) {
                    model.get(index).resultPages = (Integer) pagesSpinner.getValue();
                    list.repaint();
                }
            }
        });
        delaySpinner.setToolTipText("Pause in Sekunden vor jedem weiteren Abruf dieser Suchmaschine "
                + "(0 = aus; bis zu drei Nachkommastellen)");
        delaySpinner.setEditor(new javax.swing.JSpinner.NumberEditor(delaySpinner, "0.###"));
        delaySpinner.setMaximumSize(new Dimension(64, 26));
        delaySpinner.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent event) {
                int index = list.getSelectedIndex();
                if (index >= 0) {
                    model.get(index).delayMillis = (int) Math.round(
                            ((Number) delaySpinner.getValue()).doubleValue() * 1000.0);
                    list.repaint();
                }
            }
        });
        list.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            public void valueChanged(javax.swing.event.ListSelectionEvent event) {
                int index = list.getSelectedIndex();
                if (index >= 0) {
                    pagesSpinner.setValue(model.get(index).resultPages);
                    delaySpinner.setValue(model.get(index).delayMillis / 1000.0);
                }
            }
        });
        buttons.add(pagesSpinner);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(delaySpinner);
        buttons.add(Box.createVerticalGlue());
        add(buttons, BorderLayout.EAST);

        set(encodedValue);
    }

    /** Result pages of the SELECTED engine — shown/edited next to the ordering buttons. */
    private final javax.swing.JSpinner pagesSpinner = new javax.swing.JSpinner(
            new javax.swing.SpinnerNumberModel(
                    BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES, 1, 10, 1));

    /** Request delay of the SELECTED engine, in seconds (0 = off, no upper bound imposed here). */
    private final javax.swing.JSpinner delaySpinner = new javax.swing.JSpinner(
            new javax.swing.SpinnerNumberModel(Double.valueOf(0), Double.valueOf(0), null,
                    Double.valueOf(0.1)));

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
            entries.add(new BrowserSearchEngineSelection.Entry(row.engineId, row.enabled,
                    row.resultPages, row.delayMillis));
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
            model.addElement(new Row(engine.getId(), engine.getDisplayName(), entry.isEnabled(),
                    entry.getResultPages(), entry.getDelayMillis()));
            placed.add(engine.getId());
        }
        for (BrowserSearchEngine engine : BrowserSearchEngineCatalog.engines()) {
            if (!placed.contains(engine.getId())) {
                model.addElement(new Row(engine.getId(), engine.getDisplayName(), false,
                        BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                        BrowserSearchEngineSelection.Entry.DEFAULT_DELAY_MILLIS));
            }
        }
    }
}
