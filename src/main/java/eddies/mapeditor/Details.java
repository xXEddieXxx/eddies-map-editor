package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import lombok.experimental.UtilityClass;

/** Shared parts of an item's details: its header, and table cells to choose from. */
@UtilityClass
class Details {

  /**
   * The top of an item's details: a visual (swatch, unit picture) left, then its name, one summary
   * line and the links below each other.
   */
  static JComponent header(
      final Optional<JComponent> visual,
      final String name,
      final JComponent line,
      final List<JComponent> links) {
    final JPanel text = new JPanel();
    text.setOpaque(false);
    text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
    final JPanel linkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    linkRow.setOpaque(false);
    links.forEach(linkRow::add);
    for (final JComponent part : List.of(Theme.newTitle(name), line, linkRow)) {
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      text.add(part);
    }
    final JPanel header = new JPanel(new BorderLayout(Theme.GAP_M, 0));
    header.setOpaque(false);
    visual.ifPresent(v -> header.add(v, BorderLayout.WEST));
    header.add(text, BorderLayout.CENTER);
    return header;
  }

  /**
   * Makes the column choose from choices: the list opens on the first click, and a "▾" at the
   * cell's right shows that it does.
   */
  static void comboColumn(final JTable table, final int column, final JComboBox<String> choices) {
    final DefaultCellEditor editor = new DefaultCellEditor(choices);
    editor.setClickCountToStart(1);
    final TableColumn tableColumn = table.getColumnModel().getColumn(column);
    tableColumn.setCellEditor(editor);
    tableColumn.setCellRenderer(new ComboCell());
  }

  /** The value as text, with the default renderer's colors, and the arrow beside it. */
  private static final class ComboCell extends DefaultTableCellRenderer {
    private final JLabel arrow = new JLabel("▾");
    private final JPanel cell = new JPanel(new BorderLayout());

    ComboCell() {
      arrow.setForeground(Theme.getSecondaryTextColor());
      arrow.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, Theme.GAP_XS));
      cell.add(this, BorderLayout.CENTER);
      cell.add(arrow, BorderLayout.EAST);
    }

    @Override
    public Component getTableCellRendererComponent(
        final JTable table,
        final Object value,
        final boolean isSelected,
        final boolean hasFocus,
        final int row,
        final int column) {
      super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
      cell.setBackground(getBackground());
      cell.setToolTipText(getToolTipText());
      return cell;
    }
  }
}
