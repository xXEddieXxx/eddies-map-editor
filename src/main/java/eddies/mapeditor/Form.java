package eddies.mapeditor;

import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridBagLayoutInfo;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.Serial;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.JTextComponent;
import javax.swing.text.View;

/**
 * The inspector of an editor: a title, then fields one below the other, each with its label above
 * it, numbers with theirs on the left; sections may go side by side, rare options behind "More
 * options".
 */
final class Form extends JPanel implements Scrollable {
  @Serial private static final long serialVersionUID = 1L;
  private static final int NUMBER_WIDTH = 96;
  private static final int NUMBER_LABEL_WIDTH = 140;
  private static final int TWO_COLUMNS_FROM = 820;
  // which "More options" are open, by id: static, so a choice outlives the form an editor rebuilds
  // on every pick, for the session only (Swing thread)
  private static final Set<String> OPEN = new HashSet<>();

  private final int textWidth;
  private int row;
  // after columns(): the sections side by side, and the one that new rows go into
  private @Nullable JPanel columns;
  private @Nullable Form block;

  /**
   * @param textWidth how wide notes may get before they wrap, in pixels
   */
  Form(final int textWidth) {
    super(new PreferredLayout());
    this.textWidth = textWidth;
    setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_XS, Theme.GAP_L, Theme.GAP_L, Theme.GAP_L));
    // a new width changes how tall notes and columns are: the scroll pane must ask the size again
    addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(final ComponentEvent e) {
            revalidate();
          }
        });
  }

  private static int columnsAt(final int formWidth) {
    return formWidth >= TWO_COLUMNS_FROM ? 2 : 1;
  }

  void clear() {
    removeAll();
    row = 0;
    columns = null;
    block = null;
  }

  Form title(final String text) {
    return add(Theme.newTitle(text), 0, Theme.GAP_XS);
  }

  /** The title with a "?" that explains what to do here. */
  Form title(final String text, final String help) {
    return add(Help.with(Theme.newTitle(text), help), 0, Theme.GAP_XS);
  }

  /** A small line below the title, eg what the chosen one is. */
  Form subtitle(final String text) {
    return add(Theme.newSecondaryLabel(text), 0, Theme.GAP_L);
  }

  /** A grey line, eg what a field means; it wraps at textWidth or the column, the narrower. */
  Form note(final String text) {
    final JLabel note =
        new JLabel("<html>" + text + "</html>") {
          @Serial private static final long serialVersionUID = 1L;

          @Override
          public Dimension getPreferredSize() {
            final Dimension natural = super.getPreferredSize();
            final Container column = getParent();
            final int room =
                column == null || column.getWidth() <= 0
                    ? Integer.MAX_VALUE
                    : column.getWidth() - column.getInsets().left - column.getInsets().right;
            final int width = Math.min(natural.width, Math.min(UIScale.scale(textWidth), room));
            final View view = (View) getClientProperty(BasicHTML.propertyKey);
            if (view == null || width <= 0) {
              return natural;
            }
            view.setSize(width, 0);
            return new Dimension(width, (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)));
          }
        };
    note.setForeground(Theme.getSecondaryTextColor());
    return add(note, 2, Theme.GAP_S);
  }

  Form section(final String text) {
    return addSection(Theme.newSectionLabel(text));
  }

  /** The section's heading with a "?" that explains it. */
  Form section(final String text, final String help) {
    return addSection(Help.with(Theme.newSectionLabel(text), help));
  }

  // a line above the heading; after columns() each section is a block of its own
  private Form addSection(final JComponent heading) {
    final JPanel separated = new JPanel(new BorderLayout());
    separated.setOpaque(false);
    separated.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.getSeparatorColor()),
            BorderFactory.createEmptyBorder(Theme.GAP_M, 0, 0, 0)));
    separated.add(heading, BorderLayout.CENTER);
    if (columns != null) {
      block = newBlock();
      columns.add(block);
    }
    return add(separated, Theme.GAP_XL, Theme.GAP_S);
  }

  private Form newBlock() {
    final Form inner = new Form(textWidth);
    inner.setBorder(BorderFactory.createEmptyBorder());
    inner.setOpaque(false);
    return inner;
  }

  /** A field with its label above it; a number keeps its size instead of the form's width. */
  Form field(final String label, final JComponent field) {
    add(labelFor(label, field), Theme.GAP_M, Theme.GAP_XS);
    return addField(field);
  }

  /** A field whose label has a "?" that explains what it means. */
  Form field(final String label, final String help, final JComponent field) {
    add(Help.with(labelFor(label, field), help), Theme.GAP_M, Theme.GAP_XS);
    return addField(field);
  }

  /** The field's label, which screen readers take as the field's name. */
  private static JLabel labelFor(final String text, final JComponent field) {
    final JLabel label = new JLabel(text);
    label.setLabelFor(field);
    return label;
  }

  private Form addField(final JComponent field) {
    if (field instanceof JTextComponent text && !text.isFocusOwner()) {
      // a long text shows its start, not its end where setting the text left the caret
      text.setCaretPosition(0);
    }
    if (field instanceof JSpinner) {
      return add(leftAligned(narrowed(field)), 0, 0);
    }
    return add(field, 0, 0);
  }

  private static JComponent narrowed(final JComponent spinner) {
    spinner.setPreferredSize(new Dimension(NUMBER_WIDTH, spinner.getPreferredSize().height));
    return spinner;
  }

  private static JPanel leftAligned(final JComponent... parts) {
    final JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    line.setOpaque(false);
    for (final JComponent part : parts) {
      line.add(part);
    }
    return line;
  }

  /** A number with its label on its left, on one line. */
  Form number(final String label, final JSpinner spinner) {
    return addNumber(labelFor(label, spinner), spinner);
  }

  /** A number whose label has a "?" that explains it. */
  Form number(final String label, final String help, final JSpinner spinner) {
    return addNumber(Help.with(labelFor(label, spinner), help), spinner);
  }

  private Form addNumber(final JComponent label, final JSpinner spinner) {
    final Dimension size = label.getPreferredSize();
    label.setPreferredSize(new Dimension(Math.max(NUMBER_LABEL_WIDTH, size.width), size.height));
    return add(
        leftAligned(
            label, (JComponent) Box.createHorizontalStrut(Theme.GAP_S), narrowed(spinner)),
        Theme.GAP_M,
        0);
  }

  /** A component of its own, eg a check box or a list of rows. */
  Form row(final JComponent component) {
    return add(component, 6, 0);
  }

  /** The line below a field that tells what is wrong with it; call it right after the field. */
  FieldError error(final JComponent field) {
    final FieldError error = new FieldError(field);
    add(error, Theme.GAP_XS, 0);
    return error;
  }

  /**
   * The sections that follow go side by side, two in a row, when the form is 820 px wide or more;
   * until {@link #end}.
   */
  Form columns() {
    final JPanel sideBySide = new JPanel(new Columns());
    sideBySide.setOpaque(false);
    add(sideBySide, 0, 0);
    columns = sideBySide;
    block = null;
    return this;
  }

  /**
   * "More options (n)": options the game reads more rarely, behind a row that opens them. Whether
   * it is open is kept by id for the session.
   *
   * @param count how many fields fill adds
   */
  Form more(final String id, final int count, final Consumer<Form> fill) {
    return more(id, "More options (" + count + ")", fill);
  }

  /** Like {@link #more(String, int, Consumer)}, the row showing the label instead. */
  Form more(final String id, final String label, final Consumer<Form> fill) {
    final Form inner = newBlock();
    fill.accept(inner);
    final JButton toggle = new JButton();
    Theme.setBorderlessButtonStyle(toggle);
    toggle.setHorizontalAlignment(SwingConstants.LEADING);
    toggle.setToolTipText("Options the game reads more rarely");
    final Runnable show =
        () -> {
          final boolean open = OPEN.contains(id);
          toggle.setText((open ? "▾ " : "▸ ") + label);
          toggle
              .getAccessibleContext()
              .setAccessibleName(label + (open ? ", expanded" : ", collapsed"));
          // closed, the fields stay in the form, only hidden
          inner.setVisible(open);
          revalidate();
          repaint();
        };
    toggle.addActionListener(
        e -> {
          if (!OPEN.remove(id)) {
            OPEN.add(id);
          }
          show.run();
        });
    show.run();
    // its text starts where the fields do, as Remove's; the hover background reaches out left
    final JPanel line = new JPanel(new BorderLayout());
    line.setOpaque(false);
    line.setBorder(BorderFactory.createEmptyBorder(0, -toggle.getInsets().left, 0, 0));
    line.add(toggle, BorderLayout.CENTER);
    add(line, Theme.GAP_M, 0);
    return add(inner, 0, 0);
  }

  /** The action that removes the chosen one, at the bottom on the left, its text in line. */
  Form remove(final String text, final Runnable action) {
    final JButton button = new JButton(text);
    Theme.setDestructiveButtonStyle(button);
    button.addActionListener(e -> action.run());
    final JPanel line = leftAligned(button);
    // the button's text starts where the fields do; its hover background reaches out to the left
    line.setBorder(BorderFactory.createEmptyBorder(0, -button.getInsets().left, 0, 0));
    return add(line, Theme.GAP_XL, 0);
  }

  private Form add(final JComponent component, final int above, final int below) {
    if (columns != null && block == null) {
      // rows after columns() before its first section
      block = newBlock();
      columns.add(block);
    }
    if (block != null) {
      block.add(component, above, below);
      return this;
    }
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

  /** Ends the form, and its columns, so that its fields stay at the top. */
  Form end() {
    columns = null;
    block = null;
    final GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridy = row++;
    constraints.weighty = 1;
    // no height of its own, so what follows the form starts right below its last row
    super.add(Box.createGlue(), constraints);
    revalidate();
    repaint();
    return this;
  }

  /**
   * GridBagLayout that keeps each row's preferred height: GridBagLayout itself takes minimum sizes
   * for all rows once one row is wider than the form, eg a long subtitle, which collapses the
   * fields. A row wider than the form gets the form's width instead.
   */
  private static final class PreferredLayout extends GridBagLayout {
    @Serial private static final long serialVersionUID = 1L;
    private boolean arranging;

    @Override
    public void layoutContainer(final Container parent) {
      arranging = true;
      try {
        super.layoutContainer(parent);
      } finally {
        arranging = false;
      }
    }

    @Override
    protected GridBagLayoutInfo getLayoutInfo(final Container parent, final int sizeflag) {
      return super.getLayoutInfo(parent, arranging ? PREFERREDSIZE : sizeflag);
    }
  }

  /** The blocks of sections, two in a row when the form is wide enough, else one below another. */
  private final class Columns implements LayoutManager {
    private int perRow() {
      return columnsAt(Form.this.getWidth());
    }

    @Override
    public Dimension preferredLayoutSize(final Container parent) {
      final int perRow = perRow();
      final Component[] blocks = parent.getComponents();
      int width = 0;
      int height = 0;
      for (int first = 0; first < blocks.length; first += perRow) {
        int rowWidth = 0;
        int rowHeight = 0;
        for (int i = first; i < Math.min(first + perRow, blocks.length); i++) {
          final Dimension size = blocks[i].getPreferredSize();
          rowWidth += size.width + (i > first ? Theme.GAP_L : 0);
          rowHeight = Math.max(rowHeight, size.height);
        }
        width = Math.max(width, rowWidth);
        height += rowHeight;
      }
      return new Dimension(width, height);
    }

    @Override
    public Dimension minimumLayoutSize(final Container parent) {
      return preferredLayoutSize(parent);
    }

    @Override
    public void layoutContainer(final Container parent) {
      final int perRow = perRow();
      final Component[] blocks = parent.getComponents();
      final int width = (parent.getWidth() - (perRow - 1) * Theme.GAP_L) / perRow;
      int y = 0;
      for (int first = 0; first < blocks.length; first += perRow) {
        int rowHeight = 0;
        for (int i = first; i < Math.min(first + perRow, blocks.length); i++) {
          final int height = blocks[i].getPreferredSize().height;
          blocks[i].setBounds((i - first) * (width + Theme.GAP_L), y, width, height);
          rowHeight = Math.max(rowHeight, height);
        }
        y += rowHeight;
      }
    }

    @Override
    public void addLayoutComponent(final String name, final Component component) {}

    @Override
    public void removeLayoutComponent(final Component component) {}
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
    // digits only, as in the map's files: no "2.300" for 2300 on a German system
    spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
    spinner.addChangeListener(e -> onChange.accept((Integer) spinner.getValue()));
    return spinner;
  }
}
