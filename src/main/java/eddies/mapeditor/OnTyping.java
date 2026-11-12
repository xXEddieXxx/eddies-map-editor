package eddies.mapeditor;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

/**
 * Runs an action on every change of a text, at once and on the Swing thread, unlike TripleA's
 * {@code DocumentListenerBuilder}, which runs it 100 ms later on a timer thread: typed text then
 * reaches the map before an undo, a save or another tool, and Swing is touched on its thread.
 */
record OnTyping(Runnable action) implements DocumentListener {

  static void attach(final JTextComponent text, final Runnable action) {
    text.getDocument().addDocumentListener(new OnTyping(action));
  }

  @Override
  public void insertUpdate(final DocumentEvent e) {
    action.run();
  }

  @Override
  public void removeUpdate(final DocumentEvent e) {
    action.run();
  }

  @Override
  public void changedUpdate(final DocumentEvent e) {
    action.run();
  }
}
