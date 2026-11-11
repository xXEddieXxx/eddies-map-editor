package eddies.mapeditor;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.io.Serial;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;

/**
 * The inspector of an editor: a title, then fields one below the other, each with its label above
 * it as in the setup screen's option cards.
 */
final class Form extends JPanel implements Scrollable {
  @Serial private static final long serialVersionUID = 1L;

  private final int textWidth;
  private int row;

  /**
   * @param textWidth how wide notes may get before they wrap, in pixels
   */
  Form(final int textWidth) {
    super(new GridBagLayout());
    this.textWidth = textWidth;
    setBorder(BorderFactory.createEmptyBorder(4, 16, 16, 16));
  }

  void clear() {
    removeAll();
    row = 0;
  }

  Form title(final String text) {
    return add(newTitle(text), 0, 4);
  }

  /** The title with a "?" that explains what to do here. */
  Form title(final String text, final String help) {
    return add(Help.with(newTitle(text), help), 0, 4);
  }

  private static JLabel newTitle(final String text) {
    final JLabel title = new JLabel(text);
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() * 1.6f));
    return title;
  }

  /** A grey line, eg what a field means. */
  Form note(final String text) {
    final JLabel note =
        new JLabel("<html><div style='width:" + textWidth + "px'>" + text + "</div></html>");
    note.setForeground(Theme.getSecondaryTextColor());
    return add(note, 2, 8);
  }

  Form section(final String text) {
    return add(Theme.newSectionLabel(text), 16, 6);
  }

  /** The section's heading with a "?" that explains it. */
  Form section(final String text, final String help) {
    return add(Help.with(Theme.newSectionLabel(text), help), 16, 6);
  }

  /** A field with its label above it; a number keeps its size instead of the form's width. */
  Form field(final String label, final JComponent field) {
    add(labelFor(label, field), 8, 4);
    return addField(field);
  }

  /** A field whose label has a "?" that explains what it means. */
  Form field(final String label, final String help, final JComponent field) {
    add(Help.with(labelFor(label, field), help), 8, 4);
    return addField(field);
  }

  /** The field's label, which screen readers take as the field's name. */
  private static JLabel labelFor(final String text, final JComponent field) {
    final JLabel label = new JLabel(text);
    label.setLabelFor(field);
    return label;
  }

  private Form addField(final JComponent field) {
    if (field instanceof JSpinner) {
      final JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      field.setPreferredSize(new Dimension(110, field.getPreferredSize().height));
      left.add(field);
      return add(left, 0, 0);
    }
    return add(field, 0, 0);
  }

  /** A component of its own, eg a check box or a list of rows. */
  Form row(final JComponent component) {
    return add(component, 6, 0);
  }

  private Form add(final JComponent component, final int above, final int below) {
    final GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = row++;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.anchor = GridBagConstraints.NORTHWEST;
    constraints.insets = new Insets(above, 0, below, 0);
    super.add(component, constraints);
    return this;
  }

  /** Ends the form, so that its fields stay at the top. */
  Form end() {
    final GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridy = row++;
    constraints.weighty = 1;
    super.add(new JPanel(), constraints);
    revalidate();
    repaint();
    return this;
  }

  // as wide as the column it is in, scrolled only up and down

  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return getPreferredSize();
  }

  @Override
  public int getScrollableUnitIncrement(
      final Rectangle visible, final int orientation, final int direction) {
    return 24;
  }

  @Override
  public int getScrollableBlockIncrement(
      final Rectangle visible, final int orientation, final int direction) {
    return visible.height;
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return false;
  }

  /** A spinner for whole numbers from 0 that tells each new value. */
  static JSpinner newNumber(final int value, final int max, final IntConsumer onChange) {
    return newNumber(value, 0, max, onChange);
  }

  /**
   * A spinner for whole numbers from min that tells each new value; a map's value beyond the bounds
   * widens them, as the model refuses a value outside.
   */
  static JSpinner newNumber(
      final int value, final int min, final int max, final IntConsumer onChange) {
    final JSpinner spinner =
        new JSpinner(new SpinnerNumberModel(value, Math.min(min, value), Math.max(max, value), 1));
    spinner.addChangeListener(e -> onChange.accept((Integer) spinner.getValue()));
    return spinner;
  }
}
