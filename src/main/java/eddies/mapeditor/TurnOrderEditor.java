package eddies.mapeditor;

import games.strategy.engine.data.GameStep;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.Serial;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import lombok.experimental.ExtensionMethod;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.triplea.map.data.elements.GamePlay.Sequence.Step.StepProperty;
import org.triplea.swing.DocumentListenerBuilder;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/** The turn order: the steps of a round, each of a delegate and mostly of a nation. */
@ExtensionMethod(Builders.class)
final class TurnOrderEditor implements Editor {
  // what the engine reads from a step, from its list of them
  private static final List<String> STEP_PROPERTIES =
      Arrays.stream(GameStep.PropertyKeys.class.getFields())
          .map(TurnOrderEditor::constant)
          .toList();
  private static final String NO_NATION = "(none)";
  private static final int STEPS_WIDTH = 520;
  private static final int FIELD_WIDTH = 300;

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final StepsTable steps;
  private final JTable table;
  private final Form inspector = new Form(280);
  private int selected = -1;
  private boolean editingHere;
  // the commit of a text being typed, made a step by commit()
  private Optional<Runnable> typing = Optional.empty();

  TurnOrderEditor(final MapProject project) {
    this.project = project;
    steps = new StepsTable(project);
    table = new JTable(steps);
    table.setRowHeight(table.getRowHeight() + 8);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getColumnModel().getColumn(0).setMaxWidth(40);
    table
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              // a row chosen again after a change keeps the inspector, eg a spinner in use
              if (!e.getValueIsAdjusting()
                  && table.getSelectedRow() >= 0
                  && table.getSelectedRow() != selected) {
                selected = table.getSelectedRow();
                showInspector();
              }
            });
    final JScrollPane tableScroll = SwingComponents.newJScrollPane(table);
    // the header also before the table is shown in a window, eg in an offscreen picture
    tableScroll.setColumnHeaderView(table.getTableHeader());
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(tableScroll, BorderLayout.CENTER);
    listColumn.add(newButtons(), BorderLayout.SOUTH);
    final JSplitPane columns =
        Panels.sideBySide(listColumn, SwingComponents.newJScrollPane(inspector));
    // the steps with what happens in them, the step's fields need less
    columns.setDividerLocation(STEPS_WIDTH);
    page.add(columns, BorderLayout.CENTER);
    project.onChange(
        () -> {
          steps.fireTableDataChanged();
          if (selected >= 0 && selected < steps.getRowCount()) {
            table.setRowSelectionInterval(selected, selected);
          } else {
            selected = -1;
          }
          if (!editingHere) {
            showInspector();
          }
        });
    showInspector();
  }

  private static String constant(final Field field) {
    try {
      return (String) field.get(null);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String title() {
    return "Turn order";
  }

  @Override
  public Glyph glyph() {
    return Glyph.TURN_ORDER;
  }

  @Override
  public String hint() {
    return "The steps of a round in their order: what happens in each, eg purchase or combat"
        + " move, and whose step it is. Add, move or remove steps; choose one to change it.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    typing.ifPresent(Runnable::run);
  }

  private JPanel newButtons() {
    final JButton add = new JButton("Add step");
    add.setToolTipText("A new step after the chosen one, of its kind and nation");
    add.addActionListener(e -> addStep());
    final JButton up = new JButton("▲");
    up.setToolTipText("Move the step up");
    up.addActionListener(e -> move(-1));
    final JButton down = new JButton("▼");
    down.setToolTipText("Move the step down");
    down.addActionListener(e -> move(1));
    final JButton remove = new JButton("Remove");
    remove.setToolTipText("Remove the step; undo takes it back");
    remove.addActionListener(
        e -> {
          if (selected >= 0) {
            final int removed = selected;
            selected = Math.min(removed, steps.getRowCount() - 2);
            project.edit(g -> RulesXml.withoutStep(g, removed));
          }
        });
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    buttons.add(add);
    buttons.add(up);
    buttons.add(down);
    buttons.add(remove);
    return buttons;
  }

  private void addStep() {
    final List<Step> all = RulesXml.steps(project.game());
    final List<String> delegates = RulesXml.delegates(project.game());
    if (delegates.isEmpty()) {
      return;
    }
    final Optional<Step> like = selected >= 0 ? Optional.of(all.get(selected)) : Optional.empty();
    final int at = selected >= 0 ? selected + 1 : all.size();
    selected = at;
    project.edit(
        g ->
            RulesXml.withStepAdded(
                g,
                at,
                like.map(Step::getDelegate).orElse(delegates.get(0)),
                like.map(Step::getPlayer).orElse(null)));
  }

  private void move(final int by) {
    final int to = selected + by;
    if (selected >= 0 && to >= 0 && to < steps.getRowCount()) {
      final int from = selected;
      selected = to;
      project.edit(g -> RulesXml.withStepMoved(g, from, to));
    }
  }

  private void showInspector() {
    inspector.clear();
    typing = Optional.empty();
    final Game game = project.game();
    final List<Step> all = RulesXml.steps(game);
    if (selected < 0 || selected >= all.size()) {
      inspector.note(all.size() + " steps in a round. Choose one to change it.").end();
      return;
    }
    final int index = selected;
    final Step step = all.get(index);
    inspector.title(step.getName());

    final JComboBox<String> delegate = new JComboBox<>();
    RulesXml.delegates(game).forEach(delegate::addItem);
    delegate.setSelectedItem(step.getDelegate());
    delegate.addActionListener(
        e ->
            change(
                index,
                step,
                s -> s.toBuilder().delegate((String) delegate.getSelectedItem()).build()));
    inspector.field(
        "What happens",
        "The delegate that runs the step, eg purchase or battle.",
        ofFieldWidth("What happens", delegate));

    final JComboBox<String> nation = new JComboBox<>();
    nation.addItem(NO_NATION);
    MapXml.nations(game).forEach(nation::addItem);
    nation.setSelectedItem(Optional.ofNullable(step.getPlayer()).orElse(NO_NATION));
    nation.addActionListener(
        e ->
            change(
                index,
                step,
                s ->
                    s.toBuilder()
                        .player(
                            NO_NATION.equals(nation.getSelectedItem())
                                ? null
                                : (String) nation.getSelectedItem())
                        .build()));
    inspector.field(
        "Nation",
        "Whose step it is; none for steps of the whole round, eg its end.",
        ofFieldWidth("Nation", nation));

    inspector.field(
        "Shown as",
        "The step's name in the game; empty for the delegate's.",
        ofFieldWidth(
            "Shown as",
            newText(
                Optional.ofNullable(step.getDisplay()).orElse(""),
                text ->
                    change(
                        index,
                        step,
                        s -> s.toBuilder().display(text.isBlank() ? null : text).build()))));

    inspector.field(
        "Runs at most",
        "How many times the step runs in the whole game, eg 1 for a bid at the start; 0 for"
            + " every round.",
        Form.newNumber(
            Optional.ofNullable(step.getMaxRunCount()).orElse(0),
            99,
            value -> {
              editingHere = true;
              try {
                change(
                    index, step, s -> s.toBuilder().maxRunCount(value == 0 ? null : value).build());
              } finally {
                editingHere = false;
              }
            }));
    showProperties(index, step);
    inspector.end();
  }

  /** The step's properties, each with its value, and a box to add one the engine knows. */
  private void showProperties(final int index, final Step step) {
    inspector.section(
        "Properties",
        "Settings the engine reads from a step, eg resetUnitStateAtStart true, or"
            + " turnSummaryPlayers with nations joined by colons. Without them the engine goes by"
            + " the step's name.");
    final List<StepProperty> properties = RulesXml.stepProperties(step);
    for (final StepProperty property : properties) {
      final JButton remove = new JButton("×");
      remove.setToolTipText("Remove " + property.getName());
      remove.addActionListener(
          e -> change(index, step, s -> RulesXml.withoutStepProperty(s, property.getName())));
      final JPanel row = new JPanel(new BorderLayout(6, 0));
      row.add(new JLabel(property.getName()), BorderLayout.WEST);
      row.add(
          newText(
              Optional.ofNullable(property.getValue()).orElse(""),
              text ->
                  change(
                      index, step, s -> RulesXml.withStepProperty(s, property.getName(), text))),
          BorderLayout.CENTER);
      row.add(remove, BorderLayout.EAST);
      inspector.row(row);
    }
    final List<String> names = properties.stream().map(StepProperty::getName).toList();
    final List<String> addable = new ArrayList<>();
    addable.add("Add a property...");
    STEP_PROPERTIES.stream().filter(p -> !names.contains(p)).forEach(addable::add);
    final JComboBox<String> add = new JComboBox<>(addable.toArray(String[]::new));
    add.addActionListener(
        e -> {
          if (add.getSelectedIndex() > 0) {
            final String name = (String) add.getSelectedItem();
            change(index, step, s -> RulesXml.withStepProperty(s, name, "true"));
          }
        });
    final JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    addRow.add(add);
    inspector.row(addRow);
  }

  /**
   * The field at a width for a name, not the whole column's; named for screen readers, as the
   * form's label then names the panel around it.
   */
  private static JComponent ofFieldWidth(final String name, final JComponent field) {
    field.getAccessibleContext().setAccessibleName(name);
    field.setPreferredSize(new Dimension(FIELD_WIDTH, field.getPreferredSize().height));
    final JPanel left = new JPanel(new BorderLayout());
    left.add(field, BorderLayout.WEST);
    return left;
  }

  /** A text that becomes a step on Enter, when it loses the focus, or before a save. */
  private JTextField newText(final String value, final Consumer<String> onCommit) {
    final JTextField field = new JTextField(value);
    final Runnable commit =
        () -> {
          typing = Optional.empty();
          if (!field.getText().strip().equals(value)) {
            onCommit.accept(field.getText().strip());
          }
        };
    new DocumentListenerBuilder(() -> typing = Optional.of(commit)).attachTo(field);
    field.addActionListener(e -> commit.run());
    field.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            commit.run();
          }
        });
    return field;
  }

  /**
   * Changes the step as one undo step, if it is still the one shown: a text committed late, eg
   * after a move, must not change another step.
   */
  private void change(final int index, final Step step, final UnaryOperator<Step> change) {
    project.edit(
        g -> {
          final List<Step> now = RulesXml.steps(g);
          return index < now.size() && now.get(index) == step
              ? RulesXml.withStep(g, index, change.apply(step))
              : g;
        });
  }

  private static final class StepsTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient MapProject project;

    StepsTable(final MapProject project) {
      this.project = project;
    }

    @Override
    public int getRowCount() {
      return RulesXml.steps(project.game()).size();
    }

    @Override
    public int getColumnCount() {
      return 4;
    }

    @Override
    public String getColumnName(final int column) {
      return List.of("#", "Step", "Nation", "What happens").get(column);
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final Step step = RulesXml.steps(project.game()).get(row);
      return switch (column) {
        case 0 -> row + 1;
        case 1 -> Objects.requireNonNullElse(step.getDisplay(), step.getName());
        case 2 -> Optional.ofNullable(step.getPlayer()).orElse("");
        default -> step.getDelegate();
      };
    }
  }
}
