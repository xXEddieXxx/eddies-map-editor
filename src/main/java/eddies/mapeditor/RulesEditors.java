package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.Serial;
import java.text.ParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PropertyList.Property;
import org.triplea.swing.DocumentListenerBuilder;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.GameRulesXml.Kind;
import eddies.mapeditor.MakerIcon.Glyph;

/** The editor of the game's options, its dice and its variables. */
@UtilityClass
class RulesEditors {

  /**
   * The game's options, eg "Low Luck", each with the value the game starts with, of its kind; the
   * sides of the dice; and the variables, lists of names that attachments are made for each of.
   */
  static Editor newGameOptions(final MapProject project) {
    final OptionsTable model = new OptionsTable(project);
    final JTable table = newOptionsTable(model);
    project.onChange(model::fireTableDataChanged);

    final JPanel options = new JPanel(new BorderLayout(0, 8));
    options.add(newDice(project), BorderLayout.NORTH);
    final JScrollPane tableScroll = SwingComponents.newJScrollPane(table);
    // the header also before the table is shown in a window, eg in an offscreen picture
    tableScroll.setColumnHeaderView(table.getTableHeader());
    // the table ends where its columns do, not in a white strip
    tableScroll.getViewport().setOpaque(false);
    tableScroll.setOpaque(false);
    options.add(tableScroll, BorderLayout.CENTER);
    options.add(newOptionButtons(project, table, model), BorderLayout.SOUTH);

    final VariablesPage variables = new VariablesPage(project);
    final JTabbedPane tabs = new JTabbedPane();
    tabs.addTab("Options", options);
    tabs.addTab("Variables", variables.page);
    final JPanel page = new JPanel(new BorderLayout());
    page.add(tabs, BorderLayout.CENTER);
    return new Editor() {
      @Override
      public String title() {
        return "Game options";
      }

      @Override
      public String hint() {
        return "The options players see before a game, with the value they start with; tick"
            + " \"Players may change it\" to let them. Double-click a value to change it. The"
            + " variables are lists of names, eg nations, that attachments are made for each of.";
      }

      @Override
      public Glyph glyph() {
        return Glyph.OPTIONS;
      }

      @Override
      public JComponent page() {
        return page;
      }

      @Override
      public void commit() {
        if (table.isEditing()) {
          table.getCellEditor().stopCellEditing();
        }
        variables.commit();
      }
    };
  }

  /** The sides of the game's dice, a step for each new number. */
  private static JComponent newDice(final MapProject project) {
    // set from the game, eg after an undo, the spinner's change is no new step: it would clear redo
    final AtomicBoolean filling = new AtomicBoolean();
    final JSpinner sides =
        Form.newNumber(
            GameRulesXml.diceSides(project.game()),
            2,
            100,
            value -> {
              if (!filling.get()) {
                project.edit(g -> GameRulesXml.withDiceSides(g, value));
              }
            });
    project.onChange(
        () -> {
          final int now = GameRulesXml.diceSides(project.game());
          if (!sides.getValue().equals(now)) {
            filling.set(true);
            try {
              sides.setValue(now);
            } finally {
              filling.set(false);
            }
          }
        });
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    final JLabel label = new JLabel("Dice sides");
    label.setLabelFor(sides);
    row.add(Help.with(label, "How many sides the game's dice have; most games roll 6."));
    row.add(sides);
    return row;
  }

  /** The options with a box, a number or a text as their value, as TripleA reads it. */
  private static JTable newOptionsTable(final OptionsTable model) {
    final SpinnerEditor numbers = new SpinnerEditor(model);
    final JTable table =
        new JTable(model) {
          @Serial private static final long serialVersionUID = 1L;

          @Override
          public TableCellRenderer getCellRenderer(final int row, final int column) {
            return column == 1
                ? getDefaultRenderer(model.valueClass(row))
                : super.getCellRenderer(row, column);
          }

          @Override
          public TableCellEditor getCellEditor(final int row, final int column) {
            if (column != 1) {
              return super.getCellEditor(row, column);
            }
            return model.valueClass(row) == Integer.class
                ? numbers
                : getDefaultEditor(model.valueClass(row));
          }
        };
    table.setRowHeight(table.getRowHeight() + 8);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.putClientProperty("terminateEditOnFocusLost", true);
    // the values beside the names instead of spread over a wide window
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    final List<Integer> widths = List.of(0, 160, 150, 80, 80);
    for (int i = 1; i < widths.size(); i++) {
      table.getColumnModel().getColumn(i).setPreferredWidth(widths.get(i));
    }
    fitNames(table, model);
    model.addTableModelListener(e -> fitNames(table, model));
    return table;
  }

  /** The option column as wide as its longest name. */
  private static void fitNames(final JTable table, final OptionsTable model) {
    final FontMetrics metrics = table.getFontMetrics(table.getFont());
    int width = 200;
    for (int row = 0; row < model.getRowCount(); row++) {
      width = Math.max(width, metrics.stringWidth(model.option(row).getName()) + 24);
    }
    table.getColumnModel().getColumn(0).setPreferredWidth(width);
  }

  private static JComponent newOptionButtons(
      final MapProject project, final JTable table, final OptionsTable model) {
    final JButton add = new JButton("Add option...");
    add.addActionListener(e -> addOption(project, table));
    final JButton remove = new JButton("Remove option");
    remove.addActionListener(
        e -> {
          if (table.getSelectedRow() >= 0) {
            final String name = model.option(table.getSelectedRow()).getName();
            project.edit(g -> GameRulesXml.withoutOption(g, name));
          }
        });
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    buttons.add(
        Help.with(add, "An option TripleA knows, eg Low Luck, or one the map's triggers read."));
    buttons.add(remove);
    return buttons;
  }

  /** Asks for the name, of TripleA's options or a new one, and the kind of a new option. */
  private static void addOption(final MapProject project, final JComponent parent) {
    final List<String> taken =
        GameRulesXml.options(project.game()).stream().map(Property::getName).toList();
    final JComboBox<String> name =
        new JComboBox<>(
            GameRulesXml.COMMON_OPTIONS.stream()
                .filter(n -> !taken.contains(n))
                .toArray(String[]::new));
    name.setEditable(true);
    final JComboBox<String> kind = new JComboBox<>(new String[] {"Yes or no", "Number", "Text"});
    final JPanel fields = new JPanel(new GridLayout(0, 1, 0, 4));
    fields.add(new JLabel("Option"));
    fields.add(name);
    fields.add(new JLabel("Its value is"));
    fields.add(kind);
    if (!Panels.ask(parent, fields, "Add option")) {
      return;
    }
    final String option =
        Optional.ofNullable(name.getSelectedItem()).map(Object::toString).orElse("").strip();
    final Kind chosen = Kind.values()[kind.getSelectedIndex()];
    final Game game = project.game();
    if (GameRulesXml.withNewOption(game, option, chosen) == game) {
      JOptionPane.showMessageDialog(
          parent,
          "The name must not be empty or another option's.",
          "Add option",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    project.edit(g -> GameRulesXml.withNewOption(g, option, chosen));
  }

  private static final class OptionsTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient MapProject project;

    OptionsTable(final MapProject project) {
      this.project = project;
    }

    Property option(final int row) {
      return GameRulesXml.options(project.game()).get(row);
    }

    /** What the value is to the table: a box, a number or a text. */
    Class<?> valueClass(final int row) {
      return switch (GameRulesXml.kind(option(row))) {
        case YES_NO -> Boolean.class;
        case NUMBER -> Integer.class;
        case TEXT -> String.class;
      };
    }

    @Override
    public int getRowCount() {
      return GameRulesXml.options(project.game()).size();
    }

    @Override
    public int getColumnCount() {
      return 5;
    }

    @Override
    public String getColumnName(final int column) {
      return List.of("Option", "Value", "Players may change it", "Least", "Most").get(column);
    }

    @Override
    public Class<?> getColumnClass(final int column) {
      return switch (column) {
        case 2 -> Boolean.class;
        case 3, 4 -> Integer.class;
        default -> String.class;
      };
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
      // bounds only for numbers players may change, as only those have them in TripleA
      return column > 0
          && (column < 3
              || (valueClass(row) == Integer.class
                  && Boolean.TRUE.equals(option(row).getEditable())));
    }

    @Override
    public @Nullable Object getValueAt(final int row, final int column) {
      final Property option = option(row);
      final String value = GameRulesXml.value(option).strip();
      return switch (column) {
        case 0 -> option.getName();
        case 1 ->
            switch (GameRulesXml.kind(option)) {
              case YES_NO -> Boolean.parseBoolean(value);
              case NUMBER -> Integer.parseInt(value);
              case TEXT -> GameRulesXml.value(option);
            };
        case 2 -> Boolean.TRUE.equals(option.getEditable());
        case 3 -> valueClass(row) == Integer.class ? GameRulesXml.min(option).orElse(null) : null;
        default -> valueClass(row) == Integer.class ? GameRulesXml.max(option).orElse(null) : null;
      };
    }

    @Override
    public void setValueAt(@Nullable final Object value, final int row, final int column) {
      if (Objects.equals(value, getValueAt(row, column))) {
        return;
      }
      final Property option = option(row);
      final String name = option.getName();
      final Optional<Integer> number =
          Optional.ofNullable(value).filter(Integer.class::isInstance).map(Integer.class::cast);
      switch (column) {
        case 1 ->
            project.edit(
                g -> GameRulesXml.withOptionValue(g, name, value == null ? "" : value.toString()));
        case 2 ->
            project.edit(
                g ->
                    Boolean.TRUE.equals(value)
                        ? GameRulesXml.withEditable(g, name)
                        : GameRulesXml.withFixed(g, name));
        case 3 ->
            project.edit(g -> GameRulesXml.withRange(g, name, number, GameRulesXml.max(option)));
        default ->
            project.edit(g -> GameRulesXml.withRange(g, name, GameRulesXml.min(option), number));
      }
    }
  }

  /** A spinner for a number option, within its least and most. */
  private static final class SpinnerEditor extends AbstractCellEditor implements TableCellEditor {
    @Serial private static final long serialVersionUID = 1L;
    private final transient OptionsTable model;
    private final JSpinner spinner = new JSpinner();

    SpinnerEditor(final OptionsTable model) {
      this.model = model;
    }

    @Override
    public Component getTableCellEditorComponent(
        final JTable table,
        final Object value,
        final boolean selected,
        final int row,
        final int column) {
      final Property option = model.option(table.convertRowIndexToModel(row));
      final int now = (Integer) value;
      spinner.setModel(
          new SpinnerNumberModel(
              now,
              Math.min(now, GameRulesXml.min(option).orElse(Integer.MIN_VALUE)),
              Math.max(now, GameRulesXml.max(option).orElse(Integer.MAX_VALUE)),
              1));
      return spinner;
    }

    @Override
    public Object getCellEditorValue() {
      return spinner.getValue();
    }

    @Override
    public boolean stopCellEditing() {
      try {
        spinner.commitEdit();
      } catch (final ParseException e) {
        // what was typed is no number: the value it had stays
      }
      return super.stopCellEditing();
    }
  }

  /** The variables: a list of them, and the names in the chosen one, one per line. */
  private static final class VariablesPage {
    private final MapProject project;
    private final JPanel page = new JPanel(new BorderLayout(8, 0));
    private final SearchList list;
    private final Form inspector = new Form(280);
    private @Nullable String selected;
    // the commit of names being typed, made a step by commit()
    private Optional<Runnable> typing = Optional.empty();

    VariablesPage(final MapProject project) {
      this.project = project;
      list =
          new SearchList(
              "Search variable",
              () -> GameRulesXml.variableNames(project.game()),
              v -> String.join(", ", GameRulesXml.elements(project.game(), v)),
              this::choose);
      final JButton add = new JButton("Add variable...");
      add.addActionListener(e -> add());
      final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
      listColumn.add(list, BorderLayout.CENTER);
      listColumn.add(
          Help.with(
              add,
              "A list of names, eg nations. An attachment with foreach=\"$Nations$\" is made once"
                  + " for each, with @Nations@ in it standing for the name."),
          BorderLayout.SOUTH);
      page.add(
          Panels.sideBySide(listColumn, SwingComponents.newJScrollPane(inspector)),
          BorderLayout.CENTER);
      project.onChange(
          () -> {
            list.refresh();
            show();
          });
      show();
    }

    void commit() {
      typing.ifPresent(Runnable::run);
    }

    private void choose(final String variable) {
      commit();
      selected = variable;
      show();
    }

    private void add() {
      final String typed =
          JOptionPane.showInputDialog(
              page, "Name of the new variable:", "Add variable", JOptionPane.PLAIN_MESSAGE);
      if (typed == null) {
        return;
      }
      final String variable = typed.strip();
      if (variable.isEmpty()
          || variable.contains("$")
          || variable.contains("@")
          || GameRulesXml.variableNames(project.game()).contains(variable)) {
        JOptionPane.showMessageDialog(
            page,
            "The name must be new, without $ or @.",
            "Add variable",
            JOptionPane.WARNING_MESSAGE);
        return;
      }
      selected = variable;
      project.edit(g -> GameRulesXml.withVariable(g, variable, List.of()));
      list.choose(variable);
    }

    private void show() {
      inspector.clear();
      typing = Optional.empty();
      final Game game = project.game();
      if (selected == null || !GameRulesXml.variableNames(game).contains(selected)) {
        inspector
            .note(
                GameRulesXml.variableNames(game).size()
                    + " variables. Choose one to see its names.")
            .end();
        return;
      }
      final String variable = selected;
      inspector.title(variable);
      final List<String> names = GameRulesXml.elements(game, variable);
      final JTextArea text = new JTextArea(String.join("\n", names), 8, 20);
      onCommit(
          text,
          typed -> {
            final List<String> changed =
                Arrays.stream(typed.split("\n"))
                    .map(String::strip)
                    .filter(n -> !n.isEmpty())
                    .toList();
            if (!changed.equals(names)) {
              // not again after an undo took the variable away
              project.edit(
                  g ->
                      GameRulesXml.variableNames(g).contains(variable)
                          ? GameRulesXml.withVariable(g, variable, changed)
                          : g);
            }
          });
      inspector.field(
          "Names",
          "One per line, eg territories, nations or unit types, or $another$ variable.",
          SwingComponents.newJScrollPane(text));
      final List<String> used = GameRulesXml.whereUsed(game, variable);
      inspector.note(
          used.isEmpty() ? "Not used yet." : "Used in " + String.join(", ", used) + ".");
      final JButton remove = new JButton("Remove variable");
      remove.addActionListener(
          e -> {
            final List<String> now = GameRulesXml.whereUsed(project.game(), variable);
            if (now.isEmpty()) {
              project.edit(g -> GameRulesXml.withoutVariable(g, variable));
            } else {
              JOptionPane.showMessageDialog(
                  page,
                  "It is used in " + String.join(", ", now) + ". Change it there first.",
                  "Remove " + variable,
                  JOptionPane.WARNING_MESSAGE);
            }
          });
      final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      buttons.add(remove);
      inspector.row(buttons);
      inspector.end();
    }

    /** The text becomes a step when it loses the focus or before a save. */
    private void onCommit(final JTextArea text, final Consumer<String> commit) {
      final Runnable run =
          () -> {
            typing = Optional.empty();
            commit.accept(text.getText());
          };
      new DocumentListenerBuilder(() -> typing = Optional.of(run)).attachTo(text);
      text.addFocusListener(
          new FocusAdapter() {
            @Override
            public void focusLost(final FocusEvent e) {
              typing.ifPresent(Runnable::run);
            }
          });
    }
  }
}
