package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

/**
 * Find in a {@link CodeArea}, above its text: what to find, ↑ ↓ for the match before or after the
 * caret, how many there are, Aa for the case. Enter finds the next, Shift+Enter the one before,
 * Esc closes it. Finding wraps round at the text's end.
 */
final class FindBar extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final String FIND_BAR = "findBar";

  private final CodeArea text;
  private final JTextField what = new JTextField(20);
  private final JCheckBox matchCase = new JCheckBox("Aa");
  private final JLabel count = Theme.newSecondaryLabel("");

  private FindBar(final CodeArea text) {
    super(new BorderLayout(Theme.GAP_S, 0));
    this.text = text;
    setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.getSeparatorColor()),
            BorderFactory.createEmptyBorder(Theme.GAP_XS, Theme.GAP_S, Theme.GAP_XS, Theme.GAP_S)));
    what.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Find");
    what.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    what.getAccessibleContext().setAccessibleName("Find");
    OnTyping.attach(what, () -> find(true, false));
    what.addActionListener(e -> find(true, true));
    what.getInputMap()
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "findBefore");
    what.getActionMap().put("findBefore", action(() -> find(false, true)));
    what.getInputMap().put(CodeArea.ESCAPE, "close");
    what.getActionMap().put("close", action(this::close));
    matchCase.setToolTipText("Match the case: Berlin is not berlin");
    matchCase.addActionListener(e -> find(true, false));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_XS, 0));
    buttons.setOpaque(false);
    buttons.add(Theme.newIconButton("↑", "The match before the caret (Shift+Enter)", () -> find(false, true)));
    buttons.add(Theme.newIconButton("↓", "The next match (Enter)", () -> find(true, true)));
    buttons.add(matchCase);
    buttons.add(count);
    add(what, BorderLayout.WEST);
    add(buttons, BorderLayout.CENTER);
    add(Theme.newIconButton("×", "Close (Esc)", this::close), BorderLayout.EAST);
  }

  /** Shows the bar over the text, or focuses it when it is there; a selected word is what to find. */
  static void show(final CodeArea text) {
    final FindBar bar =
        text.getClientProperty(FIND_BAR) instanceof FindBar there ? there : new FindBar(text);
    text.putClientProperty(FIND_BAR, bar);
    if (bar.getParent() == null) {
      text.showAbove(bar);
    }
    final String selected = text.getSelectedText();
    if (selected != null && !selected.isBlank() && !selected.contains("\n")) {
      bar.what.setText(selected);
    }
    bar.what.requestFocusInWindow();
    bar.what.selectAll();
    bar.find(true, false);
  }

  private void close() {
    text.hideAbove();
    text.getHighlighter().removeAllHighlights();
  }

  /**
   * Finds forward or back from the caret, wrapping round; a find as typed keeps the caret where the
   * first match from it is, a find by Enter or ↓ moves on past the match chosen.
   */
  private void find(final boolean forward, final boolean onwards) {
    final String typed = what.getText();
    if (typed.isEmpty()) {
      count.setText("");
      text.setCaretPosition(text.getSelectionStart());
      return;
    }
    final SearchContext context = new SearchContext(typed);
    context.setMatchCase(matchCase.isSelected());
    context.setSearchForward(forward);
    context.setSearchWrap(true);
    context.setMarkAll(true);
    if (!onwards && forward) {
      // from where the selection starts, so the match stays the one shown as more is typed
      text.setCaretPosition(text.getSelectionStart());
    }
    final SearchResult result = SearchEngine.find(text, context);
    final int found = result.getMarkedCount();
    count.setText(found == 0 ? "No match" : found == 1 ? "1 match" : found + " matches");
    count.setForeground(found == 0 ? Theme.getDangerColor() : Theme.getSecondaryTextColor());
  }

  private static Action action(final Runnable action) {
    return new AbstractAction() {
      private static final long serialVersionUID = 1L;

      @Override
      public void actionPerformed(final ActionEvent e) {
        action.run();
      }
    };
  }

  /** The bar's field, for tests. */
  JComponent field() {
    return what;
  }
}
