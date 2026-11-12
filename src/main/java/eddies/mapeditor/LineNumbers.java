package eddies.mapeditor;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Rectangle2D;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

/**
 * The numbers of a text's lines, as the row header of its scroll pane. Each number stands where its
 * line starts, so a line wrapped over several rows keeps one number.
 */
final class LineNumbers extends JComponent {
  @Serial private static final long serialVersionUID = 1L;

  private final JTextArea text;

  LineNumbers(final JTextArea text) {
    this.text = text;
    setForeground(Theme.getSecondaryTextColor());
    // after the text's own view took the change, so line positions are new
    OnTyping.attach(text, () -> SwingUtilities.invokeLater(this::changed));
    // a new width wraps the lines anew
    text.addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(final ComponentEvent e) {
            changed();
          }
        });
  }

  private void changed() {
    revalidate();
    repaint();
  }

  @Override
  public Dimension getPreferredSize() {
    final FontMetrics metrics = getFontMetrics(text.getFont());
    final int digits = String.valueOf(Math.max(1, text.getLineCount())).length();
    return new Dimension(metrics.charWidth('0') * (digits + 2), text.getPreferredSize().height);
  }

  /** Where each line starts, from the text's top. */
  List<Integer> lineTops() {
    final List<Integer> tops = new ArrayList<>();
    for (int line = 0; line < text.getLineCount(); line++) {
      top(line).ifPresent(tops::add);
    }
    return List.copyOf(tops);
  }

  private Optional<Integer> top(final int line) {
    try {
      return Optional.ofNullable(text.modelToView2D(text.getLineStartOffset(line)))
          .map(Rectangle2D::getY)
          .map(Double::intValue);
    } catch (final BadLocationException e) {
      return Optional.empty();
    }
  }

  @Override
  protected void paintComponent(final Graphics g) {
    // the text's font and metrics, as the text places its lines with them
    final FontMetrics metrics = text.getFontMetrics(text.getFont());
    g.setFont(text.getFont());
    g.setColor(getForeground());
    final Rectangle shown = g.getClipBounds();
    final int first;
    try {
      first = text.getLineOfOffset(text.viewToModel2D(new Point(0, shown.y)));
    } catch (final BadLocationException e) {
      return;
    }
    for (int line = first; line < text.getLineCount(); line++) {
      final Optional<Integer> top = top(line);
      if (top.isEmpty() || top.get() > shown.y + shown.height) {
        return;
      }
      final String number = String.valueOf(line + 1);
      g.drawString(
          number,
          getWidth() - metrics.stringWidth(number) - metrics.charWidth('0'),
          top.get() + metrics.getAscent());
    }
  }
}
