package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import games.strategy.engine.data.GameStep;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.Serial;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import lombok.experimental.ExtensionMethod;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.triplea.map.data.elements.GamePlay.Sequence.Step.StepProperty;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.GameRulesXml.DelegateKind;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The turn order: the steps of a round, each of a delegate and mostly of a nation, in bands of the
 * nation whose steps follow each other.
 */
@ExtensionMethod(Builders.class)
final class TurnOrderEditor implements Editor {
  // what the engine reads from a step, from its list of them
  private static final List<String> STEP_PROPERTIES =
      Arrays.stream(GameStep.PropertyKeys.class.getFields())
          .map(TurnOrderEditor::constant)
          .toList();
  private static final String NO_NATION = "(none)";
  // the separator item of the kinds' combo, before the rare kinds
  private static final String LESS_COMMON = "Less common";
  private static final int STEPS_WIDTH = 560;
  private static final int FIELD_WIDTH = 320;

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(0, Theme.GAP_S));
  private final JLabel count = Theme.newSecondaryLabel("");
  private final StepsTable steps = new StepsTable();
  private final JTable table;
  private final Form inspector = new Form(FIELD_WIDTH);
  private final JScrollPane inspectorScroll = SwingComponents.newJScrollPane(inspector);
  private final JPanel inspectorSlot = new JPanel(new BorderLayout());
  // the chosen step's index in the steps, -1 for none
  private int selected = -1;
  private boolean editingHere;
  // the commit of a text being typed, made a step by commit()
  private Optional<Runnable> typing = Optional.empty();

  TurnOrderEditor(final MapProject project) {
    this.project = project;
    steps.refresh(project.game());
    table =
        new JTable(steps) {
          @Serial private static final long serialVersionUID = 1L;

          // a band is no step: up from below goes on up, anything else to the step after it
          @Override
          public void changeSelection(
              final int row, final int column, final boolean toggle, final boolean extend) {
            if (steps.step(row).isPresent()) {
              super.changeSelection(row, column, toggle, extend);
              return;
            }
            final int to = getSelectedRow() == row + 1 && row > 0 ? row - 1 : row + 1;
            if (to < getRowCount()) {
              super.changeSelection(to, column, toggle, extend);
            }
          }
        };
    table.setRowHeight(table.getRowHeight() + 8);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setShowGrid(false);
    table.setDefaultRenderer(Object.class, new BandRenderer());
    table.getColumnModel().getColumn(0).setMaxWidth(44);
    table.getColumnModel().getColumn(1).setPreferredWidth(280);
    table.getColumnModel().getColumn(2).setPreferredWidth(220);
    table
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              // a row chosen again after a change keeps the inspector, eg a spinner in use
              final Optional<Integer> step = steps.step(table.getSelectedRow());
              if (!e.getValueIsAdjusting() && step.isPresent() && step.get() != selected) {
                selected = step.get();
                showInspector();
              }
            });
    final JScrollPane tableScroll = SwingComponents.newJScrollPane(table);
    // the header also before the table is shown in a window, eg in an offscreen picture
    tableScroll.setColumnHeaderView(table.getTableHeader());
    final JPanel listColumn = new JPanel(new BorderLayout(0, Theme.GAP_S));
    listColumn.add(newButtons(), BorderLayout.NORTH);
    listColumn.add(tableScroll, BorderLayout.CENTER);
    final JSplitPane columns = Panels.sideBySide(listColumn, inspectorSlot);
    columns.setDividerLocation(STEPS_WIDTH);
    page.add(count, BorderLayout.NORTH);
    page.add(columns, BorderLayout.CENTER);
    project.onChange(
        () -> {
          steps.refresh(project.game());
          steps.fireTableDataChanged();
          if (selected >= steps.stepCount()) {
            selected = -1;
          }
          selectRow();
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
    return "The steps of a round, top to bottom: what happens in each, eg buying or battles, and"
        + " whose step it is. Add, move or remove steps; choose one to change it.";
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
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_XS, 0));
    buttons.add(Theme.newAddButton("Add step...", "Add a step after the chosen one", this::addStep));
    buttons.add(Box.createHorizontalStrut(Theme.GAP_S));
    buttons.add(Theme.newIconButton("▲", "Move the step up", () -> move(-1)));
    buttons.add(Theme.newIconButton("▼", "Move the step down", () -> move(1)));
    return buttons;
  }

  /** Asks what happens in the new step and whose it is, and adds it after the chosen one. */
  private void addStep() {
    final Game game = project.game();
    final List<Step> all = RulesXml.steps(game);
    final Optional<Step> like = selected >= 0 ? Optional.of(all.get(selected)) : Optional.empty();
    final JComboBox<Object> kinds = newKinds();
    kinds.setSelectedItem(
        like.flatMap(s -> GameRulesXml.kindOf(game, s)).orElse(GameRulesXml.DELEGATE_KINDS.get(0)));
    final JComboBox<String> nations =
        new JComboBox<>(MapXml.nations(game).toArray(String[]::new));
    like.map(Step::getPlayer).ifPresent(nations::setSelectedItem);
    final JPanel nation = labelled("Nation", nations);
    // steps of the whole round have no nation
    final Runnable showNation =
        () -> nation.setVisible(!((DelegateKind) kinds.getSelectedItem()).isOfRound());
    kinds.addActionListener(e -> showNation.run());
    showNation.run();
    final JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setOpaque(false);
    content.add(labelled("What happens", kinds));
    content.add(nation);
    content.add(
        Theme.newSecondaryLabel(
            like.map(
                    s ->
                        "Goes after step "
                            + (selected + 1)
                            + " ("
                            + what(game, s)
                            + Optional.ofNullable(s.getPlayer()).map(p -> ", " + p).orElse("")
                            + ").")
                .orElse(all.isEmpty() ? "It is the first step." : "Goes at the end.")));
    for (final Component part : content.getComponents()) {
      ((JComponent) part).setAlignmentX(Component.LEFT_ALIGNMENT);
    }
    if (!Dialogs.ask(page, "Add step", content, "Add step")) {
      return;
    }
    final DelegateKind kind = (DelegateKind) kinds.getSelectedItem();
    final String player = (String) nations.getSelectedItem();
    final int at = selected >= 0 ? selected + 1 : all.size();
    selected = at;
    project.edit(g -> GameRulesXml.withDelegateStep(g, at, kind, player));
  }

  /** The label above the field, with the room below it a form has between fields. */
  private static JPanel labelled(final String text, final JComponent field) {
    final JLabel label = new JLabel(text);
    label.setLabelFor(field);
    final JPanel part = new JPanel(new BorderLayout(0, Theme.GAP_XS));
    part.setOpaque(false);
    part.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.GAP_M, 0));
    part.add(label, BorderLayout.NORTH);
    part.add(field, BorderLayout.CENTER);
    return part;
  }

  /** TripleA's kinds of step, the common ones first, then the rare below "Less common". */
  private static JComboBox<Object> newKinds() {
    final List<Object> items = new ArrayList<>();
    GameRulesXml.DELEGATE_KINDS.stream().filter(DelegateKind::common).forEach(items::add);
    items.add(LESS_COMMON);
    GameRulesXml.DELEGATE_KINDS.stream().filter(k -> !k.common()).forEach(items::add);
    final JComboBox<Object> kinds = new JComboBox<>(items.toArray());
    kinds.setMaximumRowCount(items.size());
    kinds.setRenderer(
        new DefaultListCellRenderer() {
          @Serial private static final long serialVersionUID = 1L;

          @Override
          public Component getListCellRendererComponent(
              final JList<?> list,
              final Object value,
              final int index,
              final boolean isSelected,
              final boolean cellHasFocus) {
            final boolean separator = LESS_COMMON.equals(value);
            super.getListCellRendererComponent(
                list, value, index, isSelected && !separator, cellHasFocus && !separator);
            if (separator) {
              setForeground(Theme.getSecondaryTextColor());
            } else if (value instanceof final DelegateKind kind && index >= 0) {
              // TripleA's name for it in the list only, the field stays short
              setText(
                  "<html>"
                      + kind.what()
                      + " <font color='"
                      + Theme.convertColorToHex(
                          isSelected
                              ? Theme.getSelectionSecondaryTextColor()
                              : Theme.getSecondaryTextColor())
                      + "'>· "
                      + kind.display()
                      + "</font></html>");
            }
            return this;
          }
        });
    // the separator is no choice: on to the kind below it
    kinds.addActionListener(
        e -> {
          if (LESS_COMMON.equals(kinds.getSelectedItem())) {
            kinds.setSelectedIndex(kinds.getSelectedIndex() + 1);
          }
        });
    return kinds;
  }

  private void move(final int by) {
    final int to = selected + by;
    if (selected >= 0 && to >= 0 && to < steps.stepCount()) {
      final int from = selected;
      selected = to;
      project.edit(g -> RulesXml.withStepMoved(g, from, to));
    }
  }

  private void selectRow() {
    final int row = steps.rowOf(selected);
    if (row >= 0) {
      table.setRowSelectionInterval(row, row);
      table.scrollRectToVisible(table.getCellRect(row, 0, true));
    } else {
      table.clearSelection();
    }
  }

  private void showInspector() {
    inspector.clear();
    typing = Optional.empty();
    inspectorSlot.removeAll();
    final Game game = project.game();
    final List<Step> all = RulesXml.steps(game);
    count.setText(all.size() + " steps · a round runs top to bottom");
    if (all.isEmpty() || selected < 0 || selected >= all.size()) {
      inspectorSlot.add(
          all.isEmpty()
              ? Panels.empty(
                  Glyph.TURN_ORDER,
                  "No turn order yet",
                  "Each round runs these steps top to bottom. Add the first with Add step.")
              : Panels.empty(Glyph.TURN_ORDER, "Choose a step", "Choose a step to change it."),
          BorderLayout.CENTER);
      inspectorSlot.revalidate();
      inspectorSlot.repaint();
      return;
    }
    inspectorSlot.add(inspectorScroll, BorderLayout.CENTER);
    final int index = selected;
    // the step as this inspector last changed it; one changed elsewhere meanwhile is left alone
    final AtomicReference<Step> step = new AtomicReference<>(all.get(index));
    inspector.title(what(game, step.get()));
    inspector.subtitle(
        Optional.ofNullable(step.get().getPlayer()).map(p -> p + " · ").orElse("")
            + "step "
            + step.get().getName());

    final JComboBox<Object> kinds = newKinds();
    GameRulesXml.kindOf(game, step.get())
        .<Object>map(k -> k)
        .ifPresentOrElse(
            kinds::setSelectedItem,
            () -> {
              // a delegate of the map's own: shown by its name, kept until another is chosen
              kinds.insertItemAt(step.get().getDelegate(), 0);
              kinds.setSelectedIndex(0);
            });
    kinds.addActionListener(
        e -> {
          if (kinds.getSelectedItem() instanceof final DelegateKind kind) {
            changeKind(index, step, kind);
          }
        });
    inspector.field("What happens", ofFieldWidth("What happens", kinds));

    final JComboBox<String> nation = new JComboBox<>();
    nation.addItem(NO_NATION);
    MapXml.nations(game).forEach(nation::addItem);
    nation.setSelectedItem(Optional.ofNullable(step.get().getPlayer()).orElse(NO_NATION));
    nation.addActionListener(
        e ->
            changeStep(
                index,
                step,
                s ->
                    s.toBuilder()
                        .player(
                            NO_NATION.equals(nation.getSelectedItem())
                                ? null
                                : (String) nation.getSelectedItem())
                        .build()));
    inspector.field("Nation", ofFieldWidth("Nation", nation));

    final JTextField shown =
        newText(
            Optional.ofNullable(step.get().getDisplay()).orElse(""),
            text ->
                changeStep(
                    index, step, s -> s.toBuilder().display(text.isBlank() ? null : text).build()));
    // empty, the game shows the delegate's name
    shown.putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT,
        GameRulesXml.shownName(game, step.get().toBuilder().display(null).build()));
    inspector.field("Name in game", ofFieldWidth("Name in game", shown));

    inspector.field("Runs", newRuns(index, step));
    final List<StepProperty> properties = RulesXml.stepProperties(step.get());
    inspector.more("step-properties", properties.size(), more -> showProperties(more, index, step));
    inspector.remove(
        "Remove step",
        () -> {
          selected = Math.min(index, all.size() - 2);
          project.edit(g -> RulesXml.withoutStep(g, index));
        });
    inspector.end();
    inspectorSlot.revalidate();
    inspectorSlot.repaint();
  }

  /** What happens in the step in plain words, else its delegate's name. */
  private static String what(final Game game, final Step step) {
    return GameRulesXml.kindOf(game, step).map(DelegateKind::what).orElse(step.getDelegate());
  }

  /**
   * Makes the step of the kind; refused while others name it by the name that must change, as they
   * would no longer find it.
   */
  private void changeKind(final int index, final AtomicReference<Step> step, final DelegateKind kind) {
    final Game game = project.game();
    final String name = step.get().getName();
    final Game changed = GameRulesXml.withStepKind(game, index, kind);
    final List<String> naming = RulesXml.whereNamed(game, name);
    if (!naming.isEmpty() && !name.equals(RulesXml.steps(changed).get(index).getName())) {
      Dialogs.warn(
          page,
          "Change what happens",
          "TripleA tells by a step's name what happens in it, so the name "
              + name
              + " would change. These still name it: "
              + String.join(", ", naming)
              + ". Change them first.");
      showInspector();
      return;
    }
    change(index, step, g -> GameRulesXml.withStepKind(g, index, kind));
  }

  /** "Every round" or "Only in the first n rounds", which writes maxRunCount. */
  private JPanel newRuns(final int index, final AtomicReference<Step> step) {
    final int runs = Optional.ofNullable(step.get().getMaxRunCount()).orElse(0);
    final JRadioButton every = new JRadioButton("Every round", runs == 0);
    final JRadioButton first = new JRadioButton("Only in the first", runs > 0);
    final ButtonGroup group = new ButtonGroup();
    group.add(every);
    group.add(first);
    final JLabel rounds = new JLabel("rounds");
    final JSpinner number =
        Form.newNumber(Math.max(1, runs), 1, 99, value -> setRuns(index, step, value));
    number.setPreferredSize(new Dimension(72, number.getPreferredSize().height));
    final Runnable showNumber =
        () -> {
          number.setVisible(first.isSelected());
          rounds.setVisible(first.isSelected());
        };
    showNumber.run();
    every.addActionListener(
        e -> {
          showNumber.run();
          setRuns(index, step, 0);
        });
    first.addActionListener(
        e -> {
          showNumber.run();
          setRuns(index, step, (Integer) number.getValue());
        });
    final JPanel firstRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_XS, 0));
    firstRow.setOpaque(false);
    firstRow.add(first);
    firstRow.add(number);
    firstRow.add(rounds);
    final JPanel both = new JPanel();
    both.setLayout(new BoxLayout(both, BoxLayout.Y_AXIS));
    both.setOpaque(false);
    for (final JComponent part : List.of(every, firstRow)) {
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      both.add(part);
    }
    return both;
  }

  // the inspector stays, so the spinner keeps the focus while it changes
  private void setRuns(final int index, final AtomicReference<Step> step, final int runs) {
    editingHere = true;
    try {
      changeStep(
          index, step, s -> s.toBuilder().maxRunCount(runs == 0 ? null : runs).build());
    } finally {
      editingHere = false;
    }
  }

  /** The step's properties, each with its value, and a button to add one the engine knows. */
  private void showProperties(final Form more, final int index, final AtomicReference<Step> step) {
    final List<StepProperty> properties = RulesXml.stepProperties(step.get());
    for (final StepProperty property : properties) {
      final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
      row.setOpaque(false);
      row.add(new JLabel(property.getName()), BorderLayout.WEST);
      row.add(
          newText(
              Optional.ofNullable(property.getValue()).orElse(""),
              text ->
                  changeStep(
                      index, step, s -> RulesXml.withStepProperty(s, property.getName(), text))),
          BorderLayout.CENTER);
      row.add(
          Theme.newIconButton(
              "×",
              "Remove " + property.getName(),
              () ->
                  changeStep(
                      index, step, s -> RulesXml.withoutStepProperty(s, property.getName()))),
          BorderLayout.EAST);
      more.row(row);
    }
    final List<String> names = properties.stream().map(StepProperty::getName).toList();
    final List<String> addable = STEP_PROPERTIES.stream().filter(p -> !names.contains(p)).toList();
    final JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    addRow.setOpaque(false);
    addRow.add(
        Theme.newAddButton(
            "Add property...",
            "A setting the engine reads from a step, eg resetUnitStateAtStart",
            () ->
                Dialogs.pick(
                        page,
                        "Add property",
                        "Settings the engine reads from a step.",
                        addable,
                        n -> n,
                        "Add property")
                    .ifPresent(
                        name ->
                            changeStep(
                                index, step, s -> RulesXml.withStepProperty(s, name, "true")))));
    more.row(addRow);
  }

  /**
   * The field at a width for a name, not the whole column's; named for screen readers, as the
   * form's label then names the panel around it.
   */
  private static JComponent ofFieldWidth(final String name, final JComponent field) {
    field.getAccessibleContext().setAccessibleName(name);
    field.setPreferredSize(new Dimension(FIELD_WIDTH, field.getPreferredSize().height));
    final JPanel left = new JPanel(new BorderLayout());
    left.setOpaque(false);
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
    OnTyping.attach(field, () -> typing = Optional.of(commit));
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

  private void changeStep(
      final int index, final AtomicReference<Step> step, final UnaryOperator<Step> change) {
    change(index, step, g -> RulesXml.withStep(g, index, change.apply(step.get())));
  }

  /**
   * Changes the game as one undo step, if the step is still the one shown: a text committed late,
   * eg after a move, must not change another step.
   */
  private void change(
      final int index, final AtomicReference<Step> step, final UnaryOperator<Game> change) {
    final List<Step> before = RulesXml.steps(project.game());
    if (index < before.size() && before.get(index) == step.get()) {
      project.edit(change);
      step.set(RulesXml.steps(project.game()).get(index));
    }
  }

  /** A row of the table: a step by its index, or the band before the steps of one nation. */
  record Row(Optional<Integer> step, String band) {}

  /**
   * The rows: before each run of steps of one nation a band with its name; bids in one band, as
   * nations take turns there; steps of no nation are "Game start" before the first nation's,
   * "End of round" after the last, else "Whole round".
   */
  static List<Row> rows(final Game game) {
    final List<Step> all = RulesXml.steps(game);
    int firstOfNation = all.size();
    int lastOfNation = -1;
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).getPlayer() != null) {
        firstOfNation = Math.min(firstOfNation, i);
        lastOfNation = i;
      }
    }
    final List<Row> rows = new ArrayList<>();
    String band = null;
    for (int i = 0; i < all.size(); i++) {
      final Step step = all.get(i);
      final String ending = GameRulesXml.kindOf(game, step).map(DelegateKind::ending).orElse("");
      final String now;
      if (ending.equals("Bid") || ending.equals("BidPlace")) {
        now = "Bids";
      } else if (step.getPlayer() != null) {
        now = step.getPlayer();
      } else if (i < firstOfNation) {
        now = "Game start";
      } else if (i > lastOfNation) {
        now = "End of round";
      } else {
        now = "Whole round";
      }
      if (!now.equals(band)) {
        band = now;
        rows.add(new Row(Optional.empty(), now));
      }
      rows.add(new Row(Optional.of(i), now));
    }
    return List.copyOf(rows);
  }

  private static final class StepsTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private transient Game game;
    private transient List<Step> all = List.of();
    private transient List<Row> rows = List.of();

    void refresh(final Game game) {
      this.game = game;
      all = RulesXml.steps(game);
      rows = rows(game);
    }

    int stepCount() {
      return all.size();
    }

    Optional<Integer> step(final int row) {
      return row >= 0 && row < rows.size() ? rows.get(row).step() : Optional.empty();
    }

    int rowOf(final int step) {
      for (int row = 0; row < rows.size(); row++) {
        if (rows.get(row).step().filter(s -> s == step).isPresent()) {
          return row;
        }
      }
      return -1;
    }

    boolean isBand(final int row) {
      return rows.get(row).step().isEmpty();
    }

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return 3;
    }

    @Override
    public String getColumnName(final int column) {
      return List.of("#", "What happens", "Shown in the game").get(column);
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final Row shown = rows.get(row);
      if (shown.step().isEmpty()) {
        return column == 1 ? shown.band() : "";
      }
      final Step step = all.get(shown.step().get());
      return switch (column) {
        case 0 -> shown.step().get() + 1;
        // in a band of several nations, eg bids, whose step it is
        case 1 ->
            what(game, step)
                + Optional.ofNullable(step.getPlayer())
                    .filter(p -> !p.equals(shown.band()))
                    .map(p -> " · " + p)
                    .orElse("");
        default -> GameRulesXml.shownName(game, step);
      };
    }
  }

  /**
   * Steps as usual; a band in the secondary color, semibold, with a line above across the row that
   * parts it from the group before.
   */
  private final class BandRenderer extends DefaultTableCellRenderer {
    @Serial private static final long serialVersionUID = 1L;

    @Override
    public Component getTableCellRendererComponent(
        final JTable table,
        final Object value,
        final boolean isSelected,
        final boolean hasFocus,
        final int row,
        final int column) {
      final boolean band = steps.isBand(row);
      super.getTableCellRendererComponent(
          table, value, isSelected && !band, hasFocus && !band, row, column);
      setHorizontalAlignment(column == 0 ? RIGHT : LEFT);
      if (band) {
        setFont(getFont().deriveFont(Font.BOLD));
        setForeground(Theme.getSecondaryTextColor());
        setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(row > 0 ? 1 : 0, 0, 0, 0, Theme.getSeparatorColor()),
                BorderFactory.createEmptyBorder(0, Theme.GAP_XS, 0, Theme.GAP_XS)));
      } else {
        // the renderer keeps a color set once: a band's must not stay for the steps
        if (!isSelected) {
          setForeground(table.getForeground());
        }
        setBorder(BorderFactory.createEmptyBorder(0, Theme.GAP_XS, 0, Theme.GAP_S));
      }
      return this;
    }
  }
}
