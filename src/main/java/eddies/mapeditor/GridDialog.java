package eddies.mapeditor;

import eddies.mapeditor.TerritoryGrid.Pattern;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Optional;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Grid of territories: which pattern, and how big its cells are, by their width or by how many
 * columns go across the picture, with the count of cells that gives.
 */
final class GridDialog {
  static final String TEXT =
      "Lay a grid of territories over the picture\n"
          + "Every cell becomes a land territory named by its row and column, A1, A2, B1, with its"
          + " center and its neighbours; the borders are drawn into the picture as black lines."
          + " Paint owners and sea zones after. Undo takes it all back.";
  /** More cells than this would make a map no one plays, and a slow one. */
  static final int MAX_CELLS = 10_000;

  /** What was asked: the pattern and the cells' width. */
  record Request(Pattern pattern, int cellWidth) {}

  private final Dimension size;
  private final JPanel content = new JPanel();
  private final ButtonGroup patterns = new ButtonGroup();
  private final JRadioButton byWidth = new JRadioButton("Cells this wide:");
  private final JRadioButton byColumns = new JRadioButton("This many columns:");
  private final JSpinner width;
  private final JSpinner columns;
  private final JLabel result = new JLabel(" ");
  private Pattern pattern = Pattern.SQUARES;

  GridDialog(final Dimension size) {
    this.size = size;
    final int side = Math.max(TerritoryGrid.MIN_CELL_WIDTH, Math.min(size.width, size.height));
    width =
        new JSpinner(
            new SpinnerNumberModel(
                Math.min(side, Math.max(TerritoryGrid.MIN_CELL_WIDTH, size.width / 20)),
                TerritoryGrid.MIN_CELL_WIDTH,
                side,
                1));
    columns = new JSpinner(new SpinnerNumberModel(20, 1, Math.max(1, size.width / 2), 1));
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setOpaque(false);
    for (final Pattern p : Pattern.values()) {
      final JRadioButton button = new JRadioButton(p.label);
      button.setToolTipText(p.neighbours);
      button.setSelected(p == pattern);
      button.addActionListener(
          e -> {
            pattern = p;
            show();
          });
      patterns.add(button);
      content.add(leftAligned(button, Theme.newSecondaryLabel(p.neighbours)));
    }
    content.add(javax.swing.Box.createVerticalStrut(Theme.GAP_M));
    final ButtonGroup sizes = new ButtonGroup();
    sizes.add(byWidth);
    sizes.add(byColumns);
    byWidth.setSelected(true);
    byWidth.addActionListener(e -> show());
    byColumns.addActionListener(e -> show());
    width.addChangeListener(e -> show());
    columns.addChangeListener(e -> show());
    content.add(leftAligned(byWidth, width, new JLabel("pixels")));
    content.add(leftAligned(byColumns, columns));
    content.add(javax.swing.Box.createVerticalStrut(Theme.GAP_S));
    content.add(leftAligned(result));
    show();
  }

  /** Asks, and gives what was asked; empty when cancelled. */
  static Optional<Request> ask(final Component parent, final Dimension size) {
    final GridDialog dialog = new GridDialog(size);
    return Dialogs.ask(
            parent, "Grid of territories", TEXT, dialog.content, "Make the grid", dialog::isValid)
        ? Optional.of(dialog.request())
        : Optional.empty();
  }

  JComponent content() {
    return content;
  }

  void setPattern(final Pattern chosen) {
    pattern = chosen;
    show();
  }

  void setCellWidth(final int pixels) {
    byWidth.setSelected(true);
    width.setValue(pixels);
    show();
  }

  void setColumns(final int count) {
    byColumns.setSelected(true);
    columns.setValue(count);
    show();
  }

  int cellWidth() {
    return byColumns.isSelected()
        ? TerritoryGrid.cellWidthFor(size, pattern, (Integer) columns.getValue())
        : (Integer) width.getValue();
  }

  Request request() {
    return new Request(pattern, cellWidth());
  }

  boolean isValid() {
    final long cells = cells();
    return cells >= 2 && cells <= MAX_CELLS;
  }

  /** What the dialog says about the grid chosen, for tests. */
  String resultText() {
    return result.getText();
  }

  private long cells() {
    return TerritoryGrid.count(size, pattern, cellWidth());
  }

  private void show() {
    final int across = TerritoryGrid.columns(size, pattern, cellWidth());
    final int down = TerritoryGrid.rows(size, pattern, cellWidth());
    final long cells = cells();
    final String about =
        across
            + " columns × "
            + down
            + " rows, "
            + cells
            + " territories, cells "
            + cellWidth()
            + " pixels wide";
    if (cells < 2) {
      result.setText(about + ": too few. Make the cells smaller.");
    } else if (cells > MAX_CELLS) {
      result.setText(about + ": too many. Make the cells wider.");
    } else {
      result.setText(about);
    }
  }

  private static JPanel leftAligned(final JComponent... parts) {
    final JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    line.setOpaque(false);
    for (final JComponent part : parts) {
      line.add(part);
    }
    line.setAlignmentX(Component.LEFT_ALIGNMENT);
    return line;
  }
}
