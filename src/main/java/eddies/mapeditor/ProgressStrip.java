package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.Serial;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

/**
 * A thin bar with a line of text for work that takes a while, eg a map resized tile by tile or a
 * big map loading: shown while the work runs, moving until the work says how far it is, hidden
 * when it ends.
 */
final class ProgressStrip extends JPanel {
  @Serial private static final long serialVersionUID = 1L;
  private static final int BAR_HEIGHT = 6;

  private final JLabel text = Theme.newSecondaryLabel(" ");
  private final JProgressBar bar = new JProgressBar();

  ProgressStrip() {
    super(new BorderLayout(0, Theme.GAP_XS));
    setOpaque(false);
    setBorder(BorderFactory.createEmptyBorder(Theme.GAP_XS, Theme.GAP_M, Theme.GAP_XS, Theme.GAP_M));
    bar.setPreferredSize(new Dimension(0, BAR_HEIGHT));
    bar.setBorderPainted(false);
    add(text, BorderLayout.NORTH);
    add(bar, BorderLayout.CENTER);
    setVisible(false);
  }

  /** Shows the strip, moving, until {@link #setDone} says how far the work is. */
  void start(final String what) {
    text.setText(what);
    bar.setIndeterminate(true);
    bar.setValue(0);
    setVisible(true);
    revalidate();
    repaint();
  }

  /** How far the work is, eg 120 of 480 tiles; the text stays. */
  void setDone(final int done, final int all) {
    if (bar.isIndeterminate()) {
      bar.setIndeterminate(false);
    }
    bar.setMaximum(Math.max(1, all));
    bar.setValue(Math.min(done, all));
  }

  void end() {
    bar.setIndeterminate(false);
    setVisible(false);
    revalidate();
    repaint();
  }

  /** The text shown, for tests; empty while hidden. */
  String text() {
    return isVisible() ? text.getText() : "";
  }

  /** How far the bar is, 0 to 1, for tests; -1 while it moves without a measure. */
  double fraction() {
    return bar.isIndeterminate() ? -1 : bar.getValue() / (double) bar.getMaximum();
  }
}
