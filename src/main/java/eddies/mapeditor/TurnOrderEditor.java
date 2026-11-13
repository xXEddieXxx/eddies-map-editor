package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import games.strategy.engine.data.GameStep;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.Serial;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
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
import javax.swing.KeyStroke;
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
  // a round of this many steps starts with its bands folded
  private static final int FOLD_FROM = 40;
  private static final String ALL_NATIONS = "All nations";
  private static final String ALL_KINDS = "All kinds";
  private static final int FIELD_WIDTH = 320;

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(0, Theme.GAP_S));
  private final JLabel count = Theme.newSecondaryLabel("");
  private final StepsTable steps = new StepsTable();
  // gamePlay's offset plus one, as a map maker counts; a setValue to what the game has is no step
  private final JSpinner firstRound;
  private final JTable table;
  private final Form inspector = new Form(FIELD_WIDTH);
  private final JScrollPane inspectorScroll = SwingComponents.newJScrollPane(inspector);
  private final JPanel inspectorSlot = new JPanel(new BorderLayout());
  // the chosen step's index in the steps, -1 for none
  private int selected = -1;
  // the first step of the chosen band, -1 for none: ▲ and ▼ then move the whole band
  private int selectedBand = -1;
  // the bands showing their header only, by Row.key
  private final Set<String> folded = new HashSet<>();
  private final JComboBox<String> nationFilter = new JComboBox<>();
  private final JComboBox<String> kindFilter = new JComboBox<>();
  private final JButton up =
      Theme.newIconButton(
          "▲", "Move the step up; with a band chosen, the whole band", () -> move(-1));
  private final JButton down =
      Theme.newIconButton(
          "▼", "Move the step down; with a band chosen, the whole band", () -> move(1));
  private boolean filling;
  // this editor chose a step with its change, eg added one: its band opens if folded
  private boolean revealing;
  private boolean editingHere;
  // the commit of a text being typed, made a step by commit()
  private Optional<Runnable> typing = Optional.empty();

  TurnOrderEditor(final MapProject project) {
    this.project = project;
    fillFilters();
    refreshRows();
    if (steps.stepCount() >= FOLD_FROM) {
      // a long round starts as its bands, eg a nation's turn each
      steps.bands().forEach(band -> folded.add(band.key()));
      refreshRows();
    }
    table = new JTable(steps);
    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(final MouseEvent e) {
            steps.row(table.rowAtPoint(e.getPoint()))
                .filter(row -> row.step().isEmpty())
                .ifPresent(TurnOrderEditor.this::toggle);
          }
        });
    table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "fold");
    table
        .getActionMap()
        .put(
            "fold",
            new AbstractAction() {
              @Serial private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final ActionEvent e) {
                steps.row(table.getSelectedRow())
                    .filter(row -> row.step().isEmpty())
                    .ifPresent(TurnOrderEditor.this::toggle);
              }
            });
    final ActionListener filter =
        e -> {
          if (!filling) {
            refreshRows();
            steps.fireTableDataChanged();
            selectRow();
            showInspector();
          }
        };
    nationFilter.addActionListener(filter);
    kindFilter.addActionListener(filter);
    nationFilter.setToolTipText("Shows one nation's steps");
    kindFilter.setToolTipText("Shows the steps where this happens");
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
              final Optional<Row> row = steps.row(table.getSelectedRow());
              if (e.getValueIsAdjusting() || row.isEmpty()) {
                return;
              }
              final int band = row.get().step().isEmpty() ? row.get().first() : -1;
              final int step = row.get().step().orElse(-1);
              // a row chosen again after a change keeps the inspector, eg a spinner in use
              if (band != selectedBand || step != selected) {
                selectedBand = band;
                selected = step;
                showInspector();
              }
            });
    firstRound =
        Form.newNumber(
            RulesXml.roundOffset(project.game()) + 1,
            0,
            9999,
            round -> project.edit(g -> RulesXml.withRoundOffset(g, round - 1)));
    final JScrollPane tableScroll = SwingComponents.newJScrollPane(table);
    // the header also before the table is shown in a window, eg in an offscreen picture
    tableScroll.setColumnHeaderView(table.getTableHeader());
    final JPanel listColumn = new JPanel(new BorderLayout(0, Theme.GAP_S));
    listColumn.add(newButtons(), BorderLayout.NORTH);
    listColumn.add(tableScroll, BorderLayout.CENTER);
    final JSplitPane columns = Panels.sideBySide(listColumn, inspectorSlot);
    columns.setDividerLocation(STEPS_WIDTH);
    final JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
    top.add(count);
    top.add(Box.createHorizontalGlue());
    final JLabel firstRoundLabel = new JLabel("The first round is round");
    firstRoundLabel.setLabelFor(firstRound);
    top.add(firstRoundLabel);
    top.add(Box.createHorizontalStrut(Theme.GAP_S));
    firstRound.setMaximumSize(firstRound.getPreferredSize());
    top.add(firstRound);
    top.add(
        Help.button(
            "The first round's number",
            "Rounds are counted from here, in the game's history and for conditions and triggers"
                + " that name rounds. Eg 66 for a game that goes on where another ended. Written"
                + " as the game's round offset, one less."));
    page.add(top, BorderLayout.NORTH);
    page.add(columns, BorderLayout.CENTER);
    project.onChange(
        () -> {
          fillFilters();
          refreshRows();
          firstRound.setValue(RulesXml.roundOffset(project.game()) + 1);
          if (selected >= steps.stepCount()) {
            selected = -1;
          }
          if (selectedBand >= steps.stepCount()) {
            selectedBand = -1;
          }
          final boolean reveal = revealing;
          revealing = false;
          if (reveal && selected >= 0 && steps.rowOf(selected) < 0 && !filtering()) {
            // the step this editor just chose is in a folded band, eg one added there: it opens
            final int step = selected;
            rows(project.game()).stream()
                .filter(row -> row.step().filter(s -> s == step).isPresent())
                .forEach(row -> folded.remove(row.key()));
            refreshRows();
          }
          steps.fireTableDataChanged();
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
    buttons.add(Theme.newAddButton("Add step…", "Add a step after the chosen one", this::addStep));
    buttons.add(Box.createHorizontalStrut(Theme.GAP_S));
    buttons.add(up);
    buttons.add(down);
    buttons.add(Box.createHorizontalStrut(Theme.GAP_S));
    buttons.add(nationFilter);
    buttons.add(kindFilter);
    return buttons;
  }

  /** The filters' choices: the nations that have steps, and what happens in the game's steps. */
  private void fillFilters() {
    final Game game = project.game();
    final List<Step> all = RulesXml.steps(game);
    filling = true;
    fill(
        nationFilter,
        ALL_NATIONS,
        all.stream().map(Step::getPlayer).filter(Objects::nonNull).distinct().toList());
    fill(kindFilter, ALL_KINDS, all.stream().map(step -> what(game, step)).distinct().toList());
    filling = false;
  }

  /** The choices after "All …", keeping the chosen one while the game still has it. */
  private static void fill(
      final JComboBox<String> filter, final String all, final List<String> choices) {
    final Object chosen = filter.getSelectedItem();
    filter.removeAllItems();
    filter.addItem(all);
    choices.forEach(filter::addItem);
    filter.setSelectedItem(choices.contains(chosen) ? chosen : all);
  }

  private boolean filtering() {
    return nationFilter.getSelectedIndex() > 0 || kindFilter.getSelectedIndex() > 0;
  }

  /** The rows anew: the bands folded as chosen; while a filter is set, what it finds in all. */
  private void refreshRows() {
    final Game game = project.game();
    final Object nation = nationFilter.getSelectedItem();
    final Object kind = kindFilter.getSelectedItem();
    final boolean anyNation = nationFilter.getSelectedIndex() <= 0;
    final boolean anyKind = kindFilter.getSelectedIndex() <= 0;
    steps.refresh(
        game,
        filtering() ? key -> false : folded::contains,
        step ->
            (anyNation || nation.equals(step.getPlayer()))
                && (anyKind || kind.equals(what(game, step))));
    // among filtered steps a move would not go where it seems to
    up.setEnabled(!filtering());
    down.setEnabled(!filtering());
  }

  /** Folds the band, or opens it; a filter shows every band open. */
  private void toggle(final Row band) {
    if (filtering()) {
      return;
    }
    if (!folded.remove(band.key())) {
      folded.add(band.key());
    }
    refreshRows();
    steps.fireTableDataChanged();
    selectRow();
  }

  /** Asks what happens in the new step and whose it is, and adds it after the chosen one. */
  private void addStep() {
    final Game game = project.game();
    final List<Step> all = RulesXml.steps(game);
    final int after = stepBeforeNew();
    final Optional<Step> like = after >= 0 ? Optional.of(all.get(after)) : Optional.empty();
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
                            + (after + 1)
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
    final int at = after >= 0 ? after + 1 : all.size();
    // the new step is chosen and shown, also in a folded band
    selectedBand = -1;
    selected = at;
    revealing = true;
    project.edit(g -> GameRulesXml.withDelegateStep(g, at, kind, player));
  }

  /**
   * The step a new one goes after: the chosen step; with a band chosen, the band's last step, not
   * the round's; -1 for the end of the round.
   */
  int stepBeforeNew() {
    return selectedBand < 0
        ? selected
        : steps.row(steps.rowOfBand(selectedBand))
            .map(band -> band.first() + band.size() - 1)
            .orElse(-1);
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
    if (filtering()) {
      return;
    }
    if (selectedBand >= 0) {
      moveBand(by);
      return;
    }
    final int to = selected + by;
    if (selected >= 0 && to >= 0 && to < steps.stepCount()) {
      final int from = selected;
      selected = to;
      revealing = true;
      project.edit(g -> RulesXml.withStepMoved(g, from, to));
    }
  }

  /** The chosen band's steps past the band before or after it, as one step to undo. */
  private void moveBand(final int by) {
    final List<Row> bands = steps.bands();
    final int at =
        IntStream.range(0, bands.size())
            .filter(i -> bands.get(i).first() == selectedBand)
            .findFirst()
            .orElse(-1);
    if (at < 0 || at + by < 0 || at + by >= bands.size()) {
      return;
    }
    final Row band = bands.get(at);
    // before the band above it; or behind the band below, which moves up by this band's size
    final int to = by < 0 ? bands.get(at - 1).first() : band.first() + bands.get(at + 1).size();
    selectedBand = to;
    project.edit(g -> RulesXml.withStepsMoved(g, band.first(), band.size(), to));
  }

  private void selectRow() {
    final int row = selectedBand >= 0 ? steps.rowOfBand(selectedBand) : steps.rowOf(selected);
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
    final int found = steps.bands().stream().mapToInt(Row::count).sum();
    count.setText(
        filtering()
            ? found + " of " + all.size() + " steps"
            : all.size() + " steps · a round runs top to bottom");
    final Optional<Row> band =
        selectedBand < 0 ? Optional.empty() : steps.row(steps.rowOfBand(selectedBand));
    if (band.isPresent()) {
      inspectorSlot.add(
          Panels.empty(
              Glyph.TURN_ORDER,
              band.get().band(),
              SearchList.count(band.get().size(), band.get().size(), "step", "steps")
                  + ". ▲ and ▼ move them together."),
          BorderLayout.CENTER);
      inspectorSlot.revalidate();
      inspectorSlot.repaint();
      return;
    }
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
    // with no property, the way to add one alone
    inspector.more("step-properties", properties.size(), more -> showProperties(more, index, step));
    inspector.row(newAddProperty(index, step));
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
      Dialogs.refuseNamed(
          page,
          "Change what happens",
          "TripleA tells by a step's name what happens in it, so the name "
              + name
              + " would change. These still name it: "
              + String.join(", ", naming)
              + ". Change them first.",
          naming);
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

  /** The step's properties, each with its value. */
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
  }

  /** "+ Add property…": a property the engine knows and the step does not have yet. */
  private JComponent newAddProperty(final int index, final AtomicReference<Step> step) {
    final List<String> names =
        RulesXml.stepProperties(step.get()).stream().map(StepProperty::getName).toList();
    final List<String> addable = STEP_PROPERTIES.stream().filter(p -> !names.contains(p)).toList();
    final JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    addRow.setOpaque(false);
    addRow.add(
        Theme.newAddButton(
            "Add property…",
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
    return addRow;
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

  /**
   * A row of the table: a step by its index, or the header of a band, the steps of one nation.
   *
   * @param key the band among bands of the same name, its name and first step's, which its fold
   *     goes by
   * @param first the index of the band's first step
   * @param size how many steps the band has
   * @param count how many of them the filters let through, which the header says
   */
  record Row(Optional<Integer> step, String band, String key, int first, int size, int count) {}

  /** The rows with every band open and no filter. */
  static List<Row> rows(final Game game) {
    return rows(game, key -> false, step -> true);
  }

  /**
   * The rows: before each run of steps of one nation a band with its name; bids in one band, as
   * nations take turns there; steps of no nation are "Game start" before the first nation's,
   * "End of round" after the last, else "Whole round".
   *
   * @param folded the bands, by their key, that show their header only
   * @param shown the steps the filters let through; a band with none of them is left out
   */
  static List<Row> rows(
      final Game game, final Predicate<String> folded, final Predicate<Step> shown) {
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
    int first = 0;
    while (first < all.size()) {
      final String band = bandOf(game, all, first, firstOfNation, lastOfNation);
      int end = first + 1;
      while (end < all.size()
          && bandOf(game, all, end, firstOfNation, lastOfNation).equals(band)) {
        end++;
      }
      // by its first step, so a band keeps its fold when bands of its name move around it
      final String key = band + " · " + all.get(first).getName();
      final List<Integer> steps =
          IntStream.range(first, end).filter(i -> shown.test(all.get(i))).boxed().toList();
      if (!steps.isEmpty()) {
        final int from = first;
        final int size = end - first;
        rows.add(new Row(Optional.empty(), band, key, from, size, steps.size()));
        if (!folded.test(key)) {
          steps.forEach(
              i -> rows.add(new Row(Optional.of(i), band, key, from, size, steps.size())));
        }
      }
      first = end;
    }
    return List.copyOf(rows);
  }

  private static String bandOf(
      final Game game,
      final List<Step> all,
      final int index,
      final int firstOfNation,
      final int lastOfNation) {
    final Step step = all.get(index);
    final String ending = GameRulesXml.kindOf(game, step).map(DelegateKind::ending).orElse("");
    if (ending.equals("Bid") || ending.equals("BidPlace")) {
      return "Bids";
    } else if (step.getPlayer() != null) {
      return step.getPlayer();
    } else if (index < firstOfNation) {
      return "Game start";
    } else if (index > lastOfNation) {
      return "End of round";
    }
    return "Whole round";
  }
  private static final class StepsTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private transient Game game;
    private transient List<Step> all = List.of();
    private transient List<Row> rows = List.of();
    private transient Predicate<String> folded = key -> false;

    void refresh(final Game game, final Predicate<String> folded, final Predicate<Step> shown) {
      this.game = game;
      this.folded = folded;
      all = RulesXml.steps(game);
      rows = rows(game, folded, shown);
    }

    Optional<Row> row(final int row) {
      return row >= 0 && row < rows.size() ? Optional.of(rows.get(row)) : Optional.empty();
    }

    /** The header row of the band the step is in, -1 when the filters leave the band out. */
    int rowOfBand(final int step) {
      for (int row = 0; row < rows.size(); row++) {
        final Row shown = rows.get(row);
        if (shown.step().isEmpty()
            && step >= shown.first()
            && step < shown.first() + shown.size()) {
          return row;
        }
      }
      return -1;
    }

    /** The bands' headers, in their order. */
    List<Row> bands() {
      return rows.stream().filter(r -> r.step().isEmpty()).toList();
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
        // as the editor's fold rows: "▸ Bids (24)"
        return column == 1
            ? (folded.test(shown.key()) ? "▸ " : "▾ ")
                + shown.band()
                + " ("
                + shown.count()
                + ")"
            : "";
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
   * Steps as usual; a band's header in the secondary color, semibold, with a line above across
   * the row that parts it from the group before.
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
      super.getTableCellRendererComponent(table, value, isSelected, hasFocus && !band, row, column);
      setHorizontalAlignment(column == 0 ? RIGHT : LEFT);
      if (band) {
        setFont(getFont().deriveFont(Font.BOLD));
        if (!isSelected) {
          setForeground(Theme.getSecondaryTextColor());
        }
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
