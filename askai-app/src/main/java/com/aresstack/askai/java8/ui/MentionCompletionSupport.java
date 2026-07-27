package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.party.MentionCompletion;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JWindow;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;

/**
 * Attaches {@code @}-mention completion to the composer editor.
 *
 * <p>While Partying is active and the caret sits in an {@code @token}, a small popup lists the
 * matching mention handles (current participants plus {@code @AskAI}). Up/Down navigate, Enter or
 * Tab insert the selected handle, Escape dismisses. The popup never steals focus from the
 * editor.</p>
 */
public final class MentionCompletionSupport {

    private final JTextArea editor;
    private final DefaultListModel<String> model = new DefaultListModel<String>();
    private final JList<String> list = new JList<String>(model);

    private JWindow popup;
    private List<String> handles = Collections.emptyList();
    private boolean active;
    private MentionCompletion.Result current;

    public MentionCompletionSupport(JTextArea editor) {
        this.editor = editor;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) {
                applySelection();
            }
        });
        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { scheduleUpdate(); }
            public void removeUpdate(DocumentEvent event) { scheduleUpdate(); }
            public void changedUpdate(DocumentEvent event) { scheduleUpdate(); }
        });
        editor.addCaretListener(event -> scheduleUpdate());
        editor.addFocusListener(new FocusAdapter() {
            public void focusLost(FocusEvent event) {
                hidePopup();
            }
        });
        // KeyListeners run before the editor's key bindings, so consuming Enter here keeps the
        // composer's send action from firing while the completion popup is open.
        editor.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent event) {
                if (!isPopupVisible()) {
                    return;
                }
                switch (event.getKeyCode()) {
                    case KeyEvent.VK_DOWN:
                        moveSelection(1);
                        event.consume();
                        break;
                    case KeyEvent.VK_UP:
                        moveSelection(-1);
                        event.consume();
                        break;
                    case KeyEvent.VK_ENTER:
                    case KeyEvent.VK_TAB:
                        applySelection();
                        event.consume();
                        break;
                    case KeyEvent.VK_ESCAPE:
                        hidePopup();
                        event.consume();
                        break;
                    default:
                        // other keys re-trigger the document listener update
                }
            }
        });
    }

    /** Replace the completable handles (current participants plus the bot handle). */
    public void setHandles(List<String> handles) {
        this.handles = handles != null ? handles : Collections.<String>emptyList();
    }

    /** Enable while Partying is the active mode; disabling hides the popup. */
    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            hidePopup();
        }
    }

    /** @return {@code true} when the suggestion popup is currently showing. */
    public boolean isPopupVisible() {
        return popup != null && popup.isVisible();
    }

    private void scheduleUpdate() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                updatePopup();
            }
        });
    }

    private void updatePopup() {
        if (!active || !editor.isShowing() || handles.isEmpty()) {
            hidePopup();
            return;
        }
        current = MentionCompletion.compute(editor.getText(), editor.getCaretPosition(), handles);
        if (current == null) {
            hidePopup();
            return;
        }
        model.clear();
        for (String suggestion : current.getSuggestions()) {
            model.addElement(suggestion);
        }
        list.setSelectedIndex(0);
        showPopupAtCaret();
    }

    private void showPopupAtCaret() {
        try {
            Rectangle caret = editor.modelToView(editor.getCaretPosition());
            if (caret == null) {
                return;
            }
            Point screen = editor.getLocationOnScreen();
            if (popup == null) {
                Window owner = SwingUtilities.getWindowAncestor(editor);
                popup = new JWindow(owner);
                popup.setFocusableWindowState(false);
                popup.getContentPane().add(new JScrollPane(list));
            }
            list.setVisibleRowCount(Math.min(6, model.size()));
            popup.pack();
            popup.setLocation(screen.x + caret.x, screen.y + caret.y + caret.height + 2);
            popup.setVisible(true);
        } catch (Exception ignored) {
            hidePopup();
        }
    }

    private void moveSelection(int delta) {
        int size = model.size();
        if (size == 0) {
            return;
        }
        int next = (list.getSelectedIndex() + delta + size) % size;
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    private void applySelection() {
        String handle = list.getSelectedValue();
        if (handle == null || current == null) {
            hidePopup();
            return;
        }
        int caret = editor.getCaretPosition();
        String applied = MentionCompletion.apply(editor.getText(), caret, current, handle);
        editor.setText(applied);
        editor.setCaretPosition(Math.min(
                MentionCompletion.caretAfterApply(current, handle), applied.length()));
        hidePopup();
    }

    private void hidePopup() {
        current = null;
        if (popup != null) {
            popup.setVisible(false);
        }
    }
}
