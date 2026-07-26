package com.aresstack.askai.research.ui;

import com.aresstack.askai.research.domain.ResearchOutline;
import com.aresstack.askai.research.domain.ResearchSection;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.UUID;

/** Table-of-contents tree: per-section title, counts and warnings, with phase-gated edit actions. */
final class ResearchOutlineView extends JScrollPane {

    private final ResearchWorkspaceController controller;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private boolean updating;

    ResearchOutlineView(ResearchWorkspaceController controller) {
        this.controller = controller;
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new SectionRenderer());
        tree.addTreeSelectionListener(e -> {
            if (!updating) {
                controller.setActiveSection(selectedSectionId());
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                maybePopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybePopup(event);
            }
        });
        setViewportView(tree);
        refresh();
    }

    void refresh() {
        updating = true;
        try {
            String keep = controller.getActiveSectionId();
            root.removeAllChildren();
            ResearchOutline outline = controller.getOutline();
            addChildren(root, outline, "");
            model.reload();
            expandAll();
            reselect(keep);
        } finally {
            updating = false;
        }
    }

    private void addChildren(DefaultMutableTreeNode parentNode, ResearchOutline outline, String parentId) {
        for (ResearchSection section : outline.childrenOf(parentId)) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(section);
            parentNode.add(node);
            addChildren(node, outline, section.getId());
        }
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private void reselect(String sectionId) {
        if (sectionId == null || sectionId.isEmpty()) {
            return;
        }
        DefaultMutableTreeNode node = find(root, sectionId);
        if (node != null) {
            tree.setSelectionPath(new TreePath(node.getPath()));
        }
    }

    private DefaultMutableTreeNode find(DefaultMutableTreeNode from, String sectionId) {
        for (int i = 0; i < from.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) from.getChildAt(i);
            if (child.getUserObject() instanceof ResearchSection
                    && ((ResearchSection) child.getUserObject()).getId().equals(sectionId)) {
                return child;
            }
            DefaultMutableTreeNode deeper = find(child, sectionId);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private String selectedSectionId() {
        ResearchSection section = selectedSection();
        return section == null ? "" : section.getId();
    }

    private ResearchSection selectedSection() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object node = path.getLastPathComponent();
        Object value = node instanceof DefaultMutableTreeNode ? ((DefaultMutableTreeNode) node).getUserObject() : null;
        return value instanceof ResearchSection ? (ResearchSection) value : null;
    }

    private void maybePopup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int row = tree.getClosestRowForLocation(event.getX(), event.getY());
        if (row >= 0) {
            tree.setSelectionRow(row);
        }
        boolean editable = controller.canEditOutline();
        ResearchSection selected = selectedSection();

        JPopupMenu menu = new JPopupMenu();
        menu.add(action("Add section", editable, () -> {
            String title = prompt("New section title");
            if (title != null) {
                controller.addSection("", newId(), title);
            }
        }));
        menu.add(action("Add subsection", editable && selected != null, () -> {
            String title = prompt("New subsection title");
            if (title != null) {
                controller.addSection(selected.getId(), newId(), title);
            }
        }));
        menu.add(action("Rename", editable && selected != null, () -> {
            String title = prompt("Rename section");
            if (title != null) {
                controller.renameSection(selected.getId(), title);
            }
        }));
        menu.add(action("Move up", editable && selected != null, () ->
                controller.moveSection(selected.getId(), -1)));
        menu.add(action("Move down", editable && selected != null, () ->
                controller.moveSection(selected.getId(), 1)));
        menu.add(action("Delete", editable && selected != null, () ->
                controller.removeSection(selected.getId(), ResearchOutline.ChildStrategy.CASCADE)));
        menu.show(tree, event.getX(), event.getY());
    }

    private JMenuItem action(String label, boolean enabled, Runnable run) {
        JMenuItem item = new JMenuItem(label);
        item.setEnabled(enabled);
        item.addActionListener(e -> run.run());
        return item;
    }

    private String prompt(String message) {
        String value = JOptionPane.showInputDialog(this, message);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String newId() {
        return "sec-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** @return the tree component (visible for tests). */
    JComponent getTree() {
        return tree;
    }

    private static final class SectionRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            Object user = value instanceof DefaultMutableTreeNode
                    ? ((DefaultMutableTreeNode) value).getUserObject() : null;
            if (user instanceof ResearchSection) {
                ResearchSection s = (ResearchSection) user;
                String warn = s.getWarningCount() > 0 ? "  ⚠" + s.getWarningCount() : "";
                setText(s.getTitle() + "   [" + s.getStatus() + "  src " + s.getSourceCount()
                        + "  find " + s.getFindingCount() + warn + "]");
            }
            return this;
        }
    }
}
