package eddies.mapeditor;

import java.awt.BorderLayout;
import java.io.Serial;
import java.util.List;
import java.util.Optional;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.GamePlay;
import org.triplea.map.data.elements.GamePlay.Sequence;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.triplea.map.data.elements.PropertyList.Property;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/** The editors of the game's rules: its options and its turn order. */
@UtilityClass
class RulesEditors {

  /** The game's options, eg "Low Luck", each with the value the game starts with. */
  static Editor newGameOptions(final MapProject project) {
    final OptionsTable model = new OptionsTable(project);
    project.onChange(model::fireTableDataChanged);
    final JTable table = new JTable(model);
    table.setRowHeight(table.getRowHeight() + 8);
    return newEditor(
        "Game options",
        "The options players see before a game, with the value they start with. Double-click a"
            + " value to change it.",
        Glyph.OPTIONS,
        SwingComponents.newJScrollPane(table));
  }

  /** The game's turn, phase by phase; shown only, changing it comes later. */
  static Editor newTurnOrder(final MapProject project) {
    final StepsTable model = new StepsTable(project);
    project.onChange(model::fireTableDataChanged);
    final JTable table = new JTable(model);
    table.setRowHeight(table.getRowHeight() + 8);
    return newEditor(
        "Turn order",
        "The phases of a round in their order, and whose they are. Shown only for now.",
        Glyph.TURN_ORDER,
        SwingComponents.newJScrollPane(table));
  }

  private static Editor newEditor(
      final String title, final String hint, final Glyph glyph, final JComponent content) {
    final JPanel page = new JPanel(new BorderLayout());
    page.add(content, BorderLayout.CENTER);
    return new Editor() {
      @Override
      public String title() {
        return title;
      }

      @Override
      public String hint() {
        return hint;
      }

      @Override
      public Glyph glyph() {
        return glyph;
      }

      @Override
      public JComponent page() {
        return page;
      }
    };
  }

  private static final class OptionsTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient MapProject project;

    OptionsTable(final MapProject project) {
      this.project = project;
    }

    private List<Property> options() {
      return MapXml.properties(project.game()).stream()
          .filter(p -> p.getName() != null && p.getValue() != null)
          .toList();
    }

    @Override
    public int getRowCount() {
      return options().size();
    }

    @Override
    public int getColumnCount() {
      return 3;
    }

    @Override
    public String getColumnName(final int column) {
      return List.of("Option", "Value", "Players may change it").get(column);
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final Property option = options().get(row);
      return switch (column) {
        case 0 -> option.getName();
        case 1 -> option.getValue();
        default -> Boolean.TRUE.equals(option.getEditable()) ? "yes" : "";
      };
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
      return column == 1;
    }

    @Override
    public void setValueAt(final Object value, final int row, final int column) {
      final String name = options().get(row).getName();
      final String text = String.valueOf(value).strip();
      if (!text.equals(options().get(row).getValue())) {
        project.edit(g -> MapXml.withPropertyValue(g, name, text));
      }
    }
  }

  private static final class StepsTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient MapProject project;

    StepsTable(final MapProject project) {
      this.project = project;
    }

    private List<Step> steps() {
      return Optional.ofNullable(project.game().getGamePlay())
          .map(GamePlay::getSequence)
          .map(Sequence::getSteps)
          .map(List::copyOf)
          .orElse(List.of());
    }

    @Override
    public int getRowCount() {
      return steps().size();
    }

    @Override
    public int getColumnCount() {
      return 4;
    }

    @Override
    public String getColumnName(final int column) {
      return List.of("#", "Phase", "Nation", "Shown as").get(column);
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final Step step = steps().get(row);
      return switch (column) {
        case 0 -> row + 1;
        case 1 -> step.getDelegate();
        case 2 -> Optional.ofNullable(step.getPlayer()).orElse("");
        default -> Optional.ofNullable(step.getDisplay()).orElse(step.getName());
      };
    }
  }
}
