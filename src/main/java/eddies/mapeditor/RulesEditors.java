package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.text.JTextComponent;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PropertyList.Property;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.GameRulesXml.Kind;
import eddies.mapeditor.MakerIcon.Glyph;

/** The editor of the game's options, its dice and its variables. */
@UtilityClass
class RulesEditors {
  // after the dice; Victory before Bids, so the short sections pair with the long ones
  private static final List<String> GROUPS =
      List.of("Victory", "Bids", "Luck", "Politics & triggers");
  private static final Set<String> POLITICS_AND_TRIGGERS =
      Set.of(
          "Use Politics",
          "National Objectives",
          "Use Triggers",
          "Tech Development",
          "Selectable Tech Roll",
          "WW2V3 Tech Model");
  private static final int TEXT_WIDTH = 680;
  // the name and value of a row: the locks after it line up in a column
  private static final int CONTROL_WIDTH = 420;
  private static final int LABEL_WIDTH = 220;
  private static final int NUMBER_WIDTH = 96;
  // as wide as a "?" (Help.button)
  private static final int HELP_WIDTH = 24;
  // the variables' list at most this high, so the options above stay in sight
  private static final int VARIABLES_HEIGHT = 240;

  /**
   * The game's options, eg "Low Luck", each with the value the game starts with, of its kind; the
   * sides of the dice; and the variables, lists of names that attachments are made for each of.
   */
  static Editor newGameOptions(final MapProject project) {
    final OptionsPage options = new OptionsPage(project);
    return new Editor() {
      @Override
      public String title() {
        return "Game options";
      }

      @Override
      public String hint() {
        return "The options players see before a game, with the value they start with. The lock"
            + " says whether players may change it: open, they may; closed, the map fixes it.";
      }

      @Override
      public Glyph glyph() {
        return Glyph.OPTIONS;
      }

      @Override
      public JComponent page() {
        return options.page;
      }

      @Override
      public void commit() {
        options.commit();
      }
    };
  }

  /** The section an option shows in, eg "Bids"; "" for those under "More options". */
  static String group(final String option) {
    if (option.toLowerCase(Locale.ROOT).endsWith(" bid")) {
      return "Bids";
    }
    if (option.contains("Victory") || option.contains("Surrender")) {
      return "Victory";
    }
    if (POLITICS_AND_TRIGGERS.contains(option)) {
      return "Politics & triggers";
    }
    if (option.startsWith("Low Luck") || option.contains("Dice Rolls")) {
      return "Luck";
    }
    return "";
  }

  /** The options in sections, each a row of its own controls, and the variables below them. */
  private static final class OptionsPage {
    private final MapProject project;
    private final JPanel page = new JPanel(new BorderLayout(0, Theme.GAP_S));
    private final JTextField search = new JTextField(20);
    private final JCheckBox onlyChangeable = new JCheckBox("Only what players may change");
    private final Form form = new Form(TEXT_WIDTH);
    private final VariablesPage variables;
    private final JButton variablesToggle = Theme.newFoldButton();
    private final JButton addVariable;
    private static final String VARIABLES_FOLD = "rules.variables";
    // a change made by a control of this page: the page stays, so the focus does not jump
    private boolean editingHere;
    // the commit of a text being typed, made a step by commit()
    private Optional<Runnable> typing = Optional.empty();

    OptionsPage(final MapProject project) {
      this.project = project;
      variables = new VariablesPage(project);
      addVariable =
          Theme.newAddButton(
              "Add variable…",
              "A new list of names",
              () -> {
                // the new variable shows at once
                if (!Folds.isOpen(VARIABLES_FOLD)) {
                  Folds.toggle(VARIABLES_FOLD);
                }
                variables.add();
              });
      // the sections start where the search does
      form.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.GAP_L, Theme.GAP_S));
      search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search options");
      search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
      search.getAccessibleContext().setAccessibleName("Search options");
      OnTyping.attach(search, this::rebuild);
      onlyChangeable.addActionListener(e -> rebuild());
      final JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      filters.add(search);
      filters.add(Box.createHorizontalStrut(Theme.GAP_M));
      filters.add(onlyChangeable);
      final JPanel top = new JPanel(new BorderLayout(Theme.GAP_S, 0));
      top.add(filters, BorderLayout.CENTER);
      top.add(
          Theme.newAddButton(
              "Add option…",
              "An option TripleA knows, eg Low Luck, or one the map's triggers read",
              this::addOption),
          BorderLayout.EAST);
      page.add(top, BorderLayout.NORTH);
      page.add(SwingComponents.newJScrollPane(form), BorderLayout.CENTER);
      page.add(newVariablesBar(), BorderLayout.SOUTH);
      project.onChange(
          () -> {
            if (!editingHere) {
              rebuild();
            }
            showVariablesToggle();
          });
      rebuild();
    }

    void commit() {
      typing.ifPresent(Runnable::run);
      variables.commit();
    }

    private void editHere(final UnaryOperator<Game> change) {
      editingHere = true;
      try {
        project.edit(change);
      } finally {
        editingHere = false;
      }
    }

    /** The sections again, of the options the search and the tick let through. */
    private void rebuild() {
      typing = Optional.empty();
      form.clear();
      final Game game = project.game();
      final String filter = search.getText().strip().toLowerCase(Locale.ROOT);
      final List<Property> shown =
          GameRulesXml.options(game).stream()
              .filter(o -> o.getName().toLowerCase(Locale.ROOT).contains(filter))
              .filter(o -> !onlyChangeable.isSelected() || Boolean.TRUE.equals(o.getEditable()))
              .toList();
      // players never choose the dice
      final boolean dice = !onlyChangeable.isSelected() && "dice sides".contains(filter);
      final Form sections = new Form(TEXT_WIDTH);
      sections.setBorder(BorderFactory.createEmptyBorder());
      sections.setOpaque(false);
      sections.columns();
      if (dice) {
        sections.section("Dice").row(newDiceRow(game));
      }
      for (final String group : GROUPS) {
        final List<Property> rows = inGroup(shown, group);
        if (!rows.isEmpty()) {
          if ("Bids".equals(group)) {
            // one "?" for all of them, not one per nation
            sections.section(group, GameRulesXml.BID_HELP);
          } else {
            sections.section(group);
          }
          rows.forEach(o -> sections.row(newRow(o)));
        }
      }
      final List<Property> others = inGroup(shown, "");
      if (!filter.isEmpty() && !others.isEmpty()) {
        sections.section("Other options");
        others.forEach(o -> sections.row(newRow(o)));
      }
      form.row(sections);
      if (filter.isEmpty() && !others.isEmpty()) {
        form.more("game-options", others.size(), more -> others.forEach(o -> more.row(newRow(o))));
      }
      if (GameRulesXml.options(game).isEmpty()) {
        form.row(
            Panels.empty(
                Glyph.OPTIONS,
                "No options yet",
                "Add an option TripleA knows, eg Low luck.",
                "Add option…",
                this::addOption));
      } else if (shown.isEmpty() && !dice) {
        form.note("No option matches.");
      }
      form.end();
    }

    private static List<Property> inGroup(final List<Property> options, final String group) {
      return options.stream().filter(o -> group(o.getName()).equals(group)).toList();
    }

    private JComponent newDiceRow(final Game game) {
      final JSpinner sides =
          Form.newNumber(
              GameRulesXml.diceSides(game),
              2,
              100,
              value -> editHere(g -> GameRulesXml.withDiceSides(g, value)));
      return newLine(newControl(labelFor("Dice sides", sides), narrowed(sides)));
    }

    /** An option's row: its name and value, the range of a number, its lock and its "×". */
    private JComponent newRow(final Property option) {
      final String name = option.getName();
      final String value = GameRulesXml.value(option);
      final Kind kind = GameRulesXml.kind(option);
      final JButton range = newRangeButton(option);
      final JPanel control =
          switch (kind) {
            case YES_NO -> {
              final JCheckBox box = new JCheckBox(name, Boolean.parseBoolean(value.strip()));
              box.addActionListener(
                  e ->
                      editHere(
                          g ->
                              GameRulesXml.withOptionValue(
                                  g, name, Boolean.toString(box.isSelected()))));
              yield newControl(box);
            }
            case NUMBER -> {
              final JSpinner number =
                  Form.newNumber(
                      Integer.parseInt(value.strip()),
                      GameRulesXml.min(option).orElse(Integer.MIN_VALUE),
                      GameRulesXml.max(option).orElse(Integer.MAX_VALUE),
                      v -> editHere(g -> GameRulesXml.withOptionValue(g, name, Integer.toString(v))));
              yield newControl(
                  labelFor(name, number),
                  narrowed(number),
                  Box.createHorizontalStrut(Theme.GAP_S),
                  range);
            }
            case TEXT -> {
              final JTextField text = new JTextField(value);
              text.setPreferredSize(
                  new Dimension(
                      UIScale.scale(CONTROL_WIDTH - LABEL_WIDTH - Theme.GAP_S),
                      text.getPreferredSize().height));
              onCommit(
                  text,
                  typed -> {
                    if (!typed.equals(value)) {
                      editHere(g -> GameRulesXml.withOptionValue(g, name, typed));
                    }
                  });
              text.addActionListener(e -> typing.ifPresent(Runnable::run));
              yield newControl(labelFor(name, text), text);
            }
          };
      final JToggleButton lock = new JToggleButton(MakerIcon.small(Glyph.UNLOCK));
      lock.setSelectedIcon(MakerIcon.small(Glyph.LOCK));
      lock.putClientProperty(
          FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
      lock.setPreferredSize(new Dimension(UIScale.scale(28), UIScale.scale(28)));
      // closed and open alike a bare icon: a filled closed lock looked like another kind of button
      lock.setContentAreaFilled(false);
      lock.setSelected(!Boolean.TRUE.equals(option.getEditable()));
      final Runnable showLock =
          () -> {
            final String tip =
                lock.isSelected() ? "Fixed by the map" : "Players may change it before a game";
            lock.setToolTipText(tip);
            lock.getAccessibleContext().setAccessibleName(name + ": " + tip);
            // bounds only for numbers players may change, as only those have them in TripleA
            range.setVisible(kind == Kind.NUMBER && !lock.isSelected());
          };
      showLock.run();
      lock.addActionListener(
          e -> {
            editHere(
                g ->
                    lock.isSelected()
                        ? GameRulesXml.withFixed(g, name)
                        : GameRulesXml.withEditable(g, name));
            showLock.run();
          });
      final JButton remove =
          Theme.newIconButton(
              "×",
              "Remove option from the game",
              () -> project.edit(g -> GameRulesXml.withoutOption(g, name)));
      // a "?" for what TripleA's own options do; the others keep its place, so the "×" line up
      final JComponent help =
          GameRulesXml.optionHelp(name)
              .<JComponent>map(text -> Help.button(name, text))
              .orElseGet(() -> (JComponent) Box.createRigidArea(
                          new Dimension(UIScale.scale(HELP_WIDTH), UIScale.scale(HELP_WIDTH))));
      final JPanel row = newLine(control, lock, help, remove);
      row.setName(name);
      showNear(row, remove);
      return row;
    }

    private static JPanel newControl(final Component... parts) {
      final JPanel control = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      control.setOpaque(false);
      Arrays.stream(parts).forEach(control::add);
      final Dimension size = control.getPreferredSize();
      control.setPreferredSize(
          new Dimension(Math.max(UIScale.scale(CONTROL_WIDTH), size.width), size.height));
      return control;
    }

    private static JPanel newLine(final JComponent... parts) {
      final JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      line.setOpaque(false);
      Arrays.stream(parts).forEach(line::add);
      return line;
    }

    private static JLabel labelFor(final String text, final JComponent field) {
      final JLabel label = new JLabel(text);
      label.setLabelFor(field);
      // a long name is cut: the tool tip has it whole
      label.setToolTipText(text);
      label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, Theme.GAP_S));
      label.setPreferredSize(
          new Dimension(UIScale.scale(LABEL_WIDTH), label.getPreferredSize().height));
      return label;
    }

    private static JSpinner narrowed(final JSpinner spinner) {
      spinner.setPreferredSize(
          new Dimension(UIScale.scale(NUMBER_WIDTH), spinner.getPreferredSize().height));
      return spinner;
    }

    /** The "×" shows while the mouse is on the row or the "×" has the focus, keeping its place. */
    private static void showNear(final JComponent row, final JButton remove) {
      final Runnable show =
          () -> remove.setText(remove.hasFocus() || row.getMousePosition(true) != null ? "×" : "");
      final MouseAdapter hover =
          new MouseAdapter() {
            @Override
            public void mouseEntered(final MouseEvent e) {
              show.run();
            }

            @Override
            public void mouseExited(final MouseEvent e) {
              show.run();
            }
          };
      addToAll(row, hover);
      remove.addFocusListener(
          new FocusAdapter() {
            @Override
            public void focusGained(final FocusEvent e) {
              show.run();
            }

            @Override
            public void focusLost(final FocusEvent e) {
              show.run();
            }
          });
      remove.setText("");
    }

    // children that take the mouse hide it from the row: each tells the row
    private static void addToAll(final Component component, final MouseAdapter hover) {
      component.addMouseListener(hover);
      if (component instanceof Container container) {
        Arrays.stream(container.getComponents()).forEach(c -> addToAll(c, hover));
      }
    }

    /** The range players may set a number in, eg "11–19", and a click that changes it. */
    private JButton newRangeButton(final Property option) {
      final Optional<Integer> least = GameRulesXml.min(option);
      final Optional<Integer> most = GameRulesXml.max(option);
      final String text =
          least.isPresent() && most.isPresent()
              ? least.get() + "–" + most.get()
              : least.map(l -> "from " + l).or(() -> most.map(m -> "up to " + m)).orElse("Set range");
      final JButton range = new JButton(text);
      Theme.setTertiaryButtonStyle(range);
      range.setToolTipText("The least and most players may set");
      range.addActionListener(e -> askRange(option.getName()));
      return range;
    }

    private void askRange(final String name) {
      final Game game = project.game();
      final Optional<Property> option =
          GameRulesXml.options(game).stream().filter(o -> name.equals(o.getName())).findFirst();
      if (option.isEmpty()) {
        return;
      }
      final int value = Integer.parseInt(GameRulesXml.value(option.get()).strip());
      final JSpinner least =
          Form.newNumber(
              GameRulesXml.min(option.get()).orElse(value),
              Integer.MIN_VALUE,
              Integer.MAX_VALUE,
              v -> {});
      final JSpinner most =
          Form.newNumber(
              GameRulesXml.max(option.get()).orElse(value),
              Integer.MIN_VALUE,
              Integer.MAX_VALUE,
              v -> {});
      final Form content = new Form(320);
      content.setBorder(BorderFactory.createEmptyBorder());
      content.number("Least", least).number("Most", most);
      content.note("The value, " + value + ", must lie within them.");
      final UnaryOperator<Game> change =
          g ->
              GameRulesXml.withRange(
                  g,
                  name,
                  Optional.of((Integer) least.getValue()),
                  Optional.of((Integer) most.getValue()));
      if (Dialogs.ask(page, "Range of " + name, content, "Set range", () -> change.apply(game) != game)) {
        project.edit(change);
      }
    }

    /** Asks for the name, of TripleA's options or a new one, and the kind of a new option. */
    private void addOption() {
      final Game game = project.game();
      final List<String> taken =
          MapXml.properties(game).stream().map(Property::getName).toList();
      final JComboBox<String> name =
          new JComboBox<>(
              GameRulesXml.COMMON_OPTIONS.stream()
                  .filter(n -> !taken.contains(n))
                  .toArray(String[]::new));
      name.setEditable(true);
      name.setSelectedItem("");
      final JTextComponent typed = (JTextComponent) name.getEditor().getEditorComponent();
      final JRadioButton yesNo = new JRadioButton("Yes or no", true);
      final JRadioButton number = new JRadioButton("Number");
      final JRadioButton text = new JRadioButton("Text");
      final ButtonGroup kinds = new ButtonGroup();
      final JPanel kindRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      for (final JRadioButton kind : List.of(yesNo, number, text)) {
        kinds.add(kind);
        kindRow.add(kind);
        kindRow.add(Box.createHorizontalStrut(Theme.GAP_M));
      }
      final JCheckBox changeable = new JCheckBox("Players may change it", true);
      final Form content = new Form(320);
      content.setBorder(BorderFactory.createEmptyBorder());
      content.field("Option", name);
      final FieldError error = content.error(name);
      content.field("Its value is", kindRow).row(changeable);
      final Runnable check =
          () -> {
            final String option = typed.getText().strip();
            error.setProblem(
                taken.contains(option)
                    ? Optional.of("Choose a name no other option has.")
                    : Optional.empty());
          };
      OnTyping.attach(typed, check);
      if (!Dialogs.ask(
          page,
          "Add option",
          content,
          "Add option",
          () -> !typed.getText().isBlank() && !error.hasProblem())) {
        return;
      }
      final String option = typed.getText().strip();
      final Kind kind = yesNo.isSelected() ? Kind.YES_NO : number.isSelected() ? Kind.NUMBER : Kind.TEXT;
      project.edit(
          g -> {
            final Game added = GameRulesXml.withNewOption(g, option, kind);
            return changeable.isSelected() || added == g
                ? added
                : GameRulesXml.withFixed(added, option);
          });
      // the new one may be under "More options", closed: found, it shows
      search.setText(option);
    }

    /** The text becomes a step when it loses the focus, on Enter or before a save. */
    private void onCommit(final JTextComponent text, final Consumer<String> commit) {
      final Runnable run =
          () -> {
            typing = Optional.empty();
            commit.accept(text.getText());
          };
      OnTyping.attach(text, () -> typing = Optional.of(run));
      text.addFocusListener(
          new FocusAdapter() {
            @Override
            public void focusLost(final FocusEvent e) {
              typing.ifPresent(Runnable::run);
            }
          });
    }

    /**
     * "▸ Variables (n)" with a line, opening the variables below it; without variables the way to
     * add the first instead.
     */
    private JComponent newVariablesBar() {
      variablesToggle.addActionListener(
          e -> {
            Folds.toggle(VARIABLES_FOLD);
            showVariablesToggle();
          });
      final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
      bar.add(variablesToggle);
      bar.add(addVariable);
      bar.add(
          Theme.newSecondaryLabel(
              "Lists of names that attachments repeat for. Most maps need none."));
      final JPanel south = new JPanel(new BorderLayout(0, Theme.GAP_S));
      south.setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.getSeparatorColor()),
              BorderFactory.createEmptyBorder(Theme.GAP_S, 0, 0, 0)));
      south.add(bar, BorderLayout.NORTH);
      south.add(variables.page, BorderLayout.CENTER);
      showVariablesToggle();
      return south;
    }

    private void showVariablesToggle() {
      final int count = GameRulesXml.variableNames(project.game()).size();
      final boolean variablesOpen = Folds.isOpen(VARIABLES_FOLD);
      Theme.showFold(variablesToggle, "Variables (" + count + ")", variablesOpen);
      // a fold of nothing is not shown: the way to add the first variable instead
      variablesToggle.setVisible(count > 0);
      addVariable.setVisible(count == 0);
      variables.page.setVisible(variablesOpen && count > 0);
      page.revalidate();
      page.repaint();
    }
  }

  /** The variables: a list of them, and the names in the chosen one, one per line. */
  private static final class VariablesPage {
    private static final String LIST = "list";
    private static final String NONE = "none";
    private final MapProject project;
    private final CardLayout cards = new CardLayout();
    private final JPanel page = new JPanel(cards);
    private final SearchList list;
    private final JComponent none;
    private final Form inspector = new Form(TEXT_WIDTH);
    private @Nullable String selected;
    // the commit of names being typed, made a step by commit()
    private Optional<Runnable> typing = Optional.empty();
    // how many attachments use each variable, of the game they were counted in: big maps have
    // thousands of attachments, and the list asks on every paint
    private @Nullable Game countedIn;
    private final Map<String, Integer> uses = new HashMap<>();

    VariablesPage(final MapProject project) {
      this.project = project;
      list =
          new SearchList(
                  "variable",
                  "variables",
                  () -> GameRulesXml.variableNames(project.game()),
                  v -> v,
                  v ->
                      GameRulesXml.elements(project.game(), v).size()
                          + " names · used by "
                          + uses(v),
                  this::choose)
              .withAdd("Add variable…", "A new list of names", this::add);
      page.add(Panels.sideBySide(list, SwingComponents.newJScrollPane(inspector)), LIST);
      // one line in the strip below the options, which stay in sight; the bar above explains
      final JPanel noVariables =
          new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, Theme.GAP_S));
      noVariables.add(Theme.newSecondaryLabel("No variables."));
      noVariables.add(Theme.newAddButton("Add variable…", "A new list of names", this::add));
      none = noVariables;
      page.add(none, NONE);
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

    private int uses(final String variable) {
      final Game game = project.game();
      if (countedIn != game) {
        uses.clear();
        countedIn = game;
      }
      return uses.computeIfAbsent(variable, v -> GameRulesXml.whereUsed(game, v).size());
    }

    private void choose(final String variable) {
      commit();
      selected = variable;
      show();
    }

    private void add() {
      final List<String> taken = GameRulesXml.variableNames(project.game());
      final Optional<String> named =
          Dialogs.askName(
              page,
              "Add variable",
              "Name",
              "",
              "Add variable",
              name ->
                  name.contains("$") || name.contains("@")
                      ? Optional.of("A name has no $ or @.")
                      : taken.contains(name)
                          ? Optional.of("A variable of that name exists.")
                          : Optional.empty());
      named.ifPresent(
          variable -> {
            selected = variable;
            project.edit(g -> GameRulesXml.withVariable(g, variable, List.of()));
            list.choose(variable);
          });
    }

    private void show() {
      inspector.clear();
      typing = Optional.empty();
      final Game game = project.game();
      final List<String> all = GameRulesXml.variableNames(game);
      cards.show(page, all.isEmpty() ? NONE : LIST);
      // only as high as the empty state needs, else the list's room
      page.setPreferredSize(
          new Dimension(
              0,
              all.isEmpty()
                  ? none.getPreferredSize().height
                  : UIScale.scale(VARIABLES_HEIGHT)));
      if (selected == null || !all.contains(selected)) {
        inspector
            .row(
                Panels.empty(
                    Glyph.OPTIONS, "Choose a variable", "Choose a variable to see its names."))
            .end();
        return;
      }
      final String variable = selected;
      inspector.title(variable);
      final List<String> names = GameRulesXml.elements(game, variable);
      final JTextArea text =
          new JTextArea(String.join("\n", names), Math.clamp(names.size() + 1L, 4, 16), 20);
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
          "Names, one per line",
          "Eg territories, nations or unit types, or $another$ variable. An attachment with"
              + " foreach=\"$" + variable + "$\" is made once for each, with @" + variable
              + "@ in it standing for the name.",
          SwingComponents.newJScrollPane(text));
      final List<String> used = GameRulesXml.whereUsed(game, variable);
      inspector.note(
          used.isEmpty() ? "Not used yet." : "Used by: " + String.join(", ", used) + ".");
      inspector.remove(
          "Remove variable",
          () -> {
            final List<String> now = GameRulesXml.whereUsed(project.game(), variable);
            if (now.isEmpty()) {
              project.edit(g -> GameRulesXml.withoutVariable(g, variable));
            } else {
              Dialogs.warn(
                  page,
                  "Remove variable",
                  "It is used by " + String.join(", ", now) + ". Change it there first.");
            }
          });
      inspector.end();
    }

    /** The text becomes a step when it loses the focus or before a save. */
    private void onCommit(final JTextArea text, final Consumer<String> commit) {
      final Runnable run =
          () -> {
            typing = Optional.empty();
            commit.accept(text.getText());
          };
      OnTyping.attach(text, () -> typing = Optional.of(run));
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
