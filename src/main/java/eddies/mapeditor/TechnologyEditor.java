package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ToolTipManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Technology.Technologies.TechName;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.TechXml.AbilityNumber;
import eddies.mapeditor.TechXml.UnitNumber;

/**
 * The technologies: adding and removing them, the research chart each nation researches one in,
 * what a research roll costs it, the built-in ones a nation has at the start, and what a
 * technology gives (its techAbilityAttachment, in plain words). A game without a list shows
 * TripleA's standard technologies; the first change writes them into the game with it, as one
 * step.
 */
final class TechnologyEditor implements Editor {
  private static final String NOT_RESEARCHED = "Not researched";
  private static final String AT_START_TIP =
      "Only TripleA's built-in technologies can be given at the start.";
  private static final String COST_TIP =
      "What one research roll costs this nation, in PUs, for every technology. 5 when not set.";
  private static final int TABLE_WIDTH = 620;
  // what TripleA gives a built-in technology that has no techAbilityAttachment
  // (TechAbilityAttachment.setDefaultTechnologyAttachments)
  private static final Map<String, String> STANDARD_EFFECTS =
      Map.of(
          "longRangeAir", "+2 movement for every air unit",
          "aARadar", "+1 anti-air attack for every anti-air unit",
          "superSub", "+1 attack for every first-strike unit, eg submarines",
          "jetPower",
              "+1 defence for every air unit but strategic bombers (+1 attack with the WW2V3"
                  + " Tech Model)",
          "increasedFactoryProduction",
              "2 more units produced by every factory in territories worth 3 or more, and"
                  + " repairs at half price",
          "warBonds", "one die of PUs each turn",
          "rocket", "one rocket die for every rocket unit, 3 territories far, one attack per"
              + " territory",
          "destroyerBombard", "destroyers can bombard",
          "heavyBomber",
              "more attack rolls for strategic bombers (game option Heavy Bomber Dice Rolls)");
  private static final int DETAILS_WIDTH = 760;

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(Theme.GAP_S, 0));
  private final SearchList list;
  private final JLabel standardNote =
      Theme.newSecondaryLabel(
          "<html><div style='width:" + Theme.htmlWidth(260) + "'>This game uses TripleA's standard technologies,"
              + " the WW2V3 Tech Model option says which. Changing one writes the list into the"
              + " game.</div></html>");
  // short: a long link line widens the list where fonts are wider, eg on Linux
  private final JComponent standardLink = Help.link("Chosen in", "Game options");
  private final Form details = new Form(420);
  private @Nullable String selected;
  private Optional<JTable> nationsShown = Optional.empty();
  // a number changed in its own field: the details stay, so the field keeps the focus
  private boolean editingHere;
  // withStandardTechs builds a GameData: once per game, not per row
  private @Nullable Game listedFor;
  private @Nullable Game listed;

  TechnologyEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
                "technology",
                "technologies",
                this::names,
                t -> t,
                this::rowDetail,
                this::choose)
            .withAdd("Add technology…", "Add a technology", this::addTech)
            .withEmpty(
                Panels.empty(
                    Glyph.TECHNOLOGY,
                    "No technologies",
                    "Nations can research nothing in this game.",
                    "Add technology…",
                    this::addTech));
    standardNote.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.GAP_S, Theme.GAP_S));
    final JPanel listColumn = new JPanel(new BorderLayout());
    final JPanel standard = new JPanel(new BorderLayout());
    standard.add(standardNote, BorderLayout.CENTER);
    standard.add(standardLink, BorderLayout.SOUTH);
    listColumn.add(standard, BorderLayout.NORTH);
    listColumn.add(list, BorderLayout.CENTER);
    details.addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(final ComponentEvent e) {
            // the content at most 760 px wide, at the left: the rest a right margin
            details.setBorder(
                BorderFactory.createEmptyBorder(
                    Theme.GAP_XS,
                    Theme.GAP_L,
                    Theme.GAP_L,
                    Math.max(Theme.GAP_L, details.getWidth() - Theme.GAP_L - DETAILS_WIDTH)));
          }
        });
    page.add(
        Panels.sideBySide(listColumn, SwingComponents.newJScrollPane(details)),
        BorderLayout.CENTER);
    project.onChange(
        () -> {
          list.refresh();
          if (!editingHere) {
            showDetails();
          }
        });
    // the first technology chosen on open
    showDetails();
  }

  @Override
  public String title() {
    return "Technology";
  }

  @Override
  public Glyph glyph() {
    return Glyph.TECHNOLOGY;
  }

  @Override
  public String hint() {
    return "Technologies nations can research. Give each nation a research chart for it;"
        + " leave Not researched where a nation should not get it.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    nationsShown.filter(JTable::isEditing).ifPresent(t -> t.getCellEditor().stopCellEditing());
  }

  /** The game's technologies, or TripleA's standard ones when it has no list; nothing written. */
  private Game listed() {
    final Game game = project.game();
    if (game != listedFor || listed == null) {
      listedFor = game;
      listed = TechXml.withStandardTechs(game);
    }
    return listed;
  }

  List<String> names() {
    return TechXml.techNames(listed());
  }

  private boolean isStandard() {
    return project.game().getTechnology() == null;
  }

  private String rowDetail(final String name) {
    if (isStandard()) {
      return "Standard";
    }
    return techOf(name)
        .flatMap(TechXml::builtIn)
        .map(b -> b.equals(name) ? "Built-in" : "Built-in as " + b)
        .orElse("Own");
  }

  private Optional<TechName> techOf(final String name) {
    return TechXml.techs(listed()).stream().filter(t -> name.equals(t.getName())).findFirst();
  }

  private void choose(final String tech) {
    selected = tech;
    showDetails();
  }

  /**
   * The nation researches the technology in the chart, a new one if the nation has none of the
   * name; not at all for "Not researched" or blank. A game without a list gets the standard one
   * with it, as one step.
   */
  void setChart(final String nation, final String tech, final String chart) {
    final String name = chart.strip();
    project.edit(
        g ->
            name.isEmpty() || NOT_RESEARCHED.equals(name)
                ? TechXml.withoutTechIn(TechXml.withStandardTechs(g), nation, tech)
                : TechXml.withTechIn(TechXml.withStandardTechs(g), nation, tech, name));
  }

  private void addTech() {
    final JRadioButton builtInKind = new JRadioButton("TripleA built-in", true);
    final JRadioButton ownKind = new JRadioButton("Own (its effects come from attachments)");
    final ButtonGroup kinds = new ButtonGroup();
    kinds.add(builtInKind);
    kinds.add(ownKind);
    final JComboBox<String> builtIn = new JComboBox<>(TechXml.BUILT_IN.toArray(String[]::new));
    final JTextField name = new JTextField(20);
    final FieldError error = new FieldError(name);
    final Runnable fill =
        () -> {
          builtIn.setEnabled(builtInKind.isSelected());
          if (name.getText().isBlank() && builtInKind.isSelected()) {
            name.setText((String) builtIn.getSelectedItem());
          }
        };
    builtIn.addActionListener(e -> fill.run());
    builtInKind.addActionListener(e -> fill.run());
    ownKind.addActionListener(e -> fill.run());
    OnTyping.attach(
        name,
        () ->
            error.setProblem(
                names().contains(name.getText().strip())
                    ? Optional.of("Already a technology")
                    : Optional.empty()));
    fill.run();
    final JPanel builtInRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    builtInRow.add(builtInKind);
    builtInRow.add(builtIn);
    final JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    for (final JComponent part :
        List.<JComponent>of(
            new JLabel("Kind"), builtInRow, ownKind, new JLabel("Name"), name, error)) {
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      content.add(part);
      content.add(Box.createVerticalStrut(Theme.GAP_XS));
    }
    name.setMaximumSize(new Dimension(Integer.MAX_VALUE, name.getPreferredSize().height));
    if (!Dialogs.ask(
        page,
        "Add technology",
        content,
        "Add",
        () -> !name.getText().isBlank() && !names().contains(name.getText().strip()))) {
      return;
    }
    final String tech = name.getText().strip();
    final String engineTech =
        builtInKind.isSelected() ? (String) builtIn.getSelectedItem() : null;
    project.edit(g -> TechXml.withTech(g, tech, engineTech));
    choose(tech);
    list.choose(tech);
  }

  private void showDetails() {
    standardNote.setVisible(isStandard());
    standardLink.setVisible(isStandard());
    details.clear();
    nationsShown = Optional.empty();
    if (selected == null || techOf(selected).isEmpty()) {
      selected = names().stream().findFirst().orElse(null);
      list.choose(selected);
    }
    final Optional<TechName> tech = Optional.ofNullable(selected).flatMap(this::techOf);
    if (tech.isEmpty()) {
      details.end();
      details.revalidate();
      details.repaint();
      return;
    }
    final String name = tech.get().getName();
    final Optional<String> builtIn = TechXml.builtIn(tech.get());
    final List<String> nations = MapXml.nations(project.game());
    final long researching =
        nations.stream().filter(n -> TechXml.categoryOf(listed(), n, name).isPresent()).count();
    details.title(name);
    details.subtitle(
        builtIn
            .map(
                b ->
                    "TripleA built-in · researched by "
                        + researching
                        + " nations · "
                        + nations.stream()
                            .filter(n -> TechXml.hasAtStart(project.game(), n, b))
                            .count()
                        + " have it at the start")
            .orElse("Own technology · researched by " + researching + " nations"));
    details.row(nationsTable(name, builtIn));
    details.section(
        "What it gives",
        "What a nation gets once it has researched the technology. Saved as the technology's"
            + " techAbilityAttachment.");
    showGives(tech.get(), builtIn);
    details.remove("Remove technology", () -> removeTech(name));
    details.end();
    details.revalidate();
    details.repaint();
  }

  private JComponent nationsTable(final String tech, final Optional<String> builtIn) {
    final JTable table = new JTable(new NationsTable(this, tech, builtIn));
    table.setTableHeader(new AtStartHeader(table));
    table.setRowHeight(table.getRowHeight() + Theme.GAP_S);
    table.putClientProperty("terminateEditOnFocusLost", true);
    final JComboBox<String> charts = new JComboBox<>();
    charts.setEditable(true);
    charts.setToolTipText("Type a name for a new chart.");
    charts.addItem(NOT_RESEARCHED);
    MapXml.nations(project.game()).stream()
        .flatMap(n -> TechXml.categories(listed(), n).stream())
        .distinct()
        .forEach(charts::addItem);
    Details.comboColumn(table, 1, charts);
    // a tick or a number needs little room, the chart more
    for (int column = 2; column < table.getColumnCount(); column++) {
      table.getColumnModel().getColumn(column).setPreferredWidth(120);
      table.getColumnModel().getColumn(column).setMaxWidth(150);
    }
    nationsShown = Optional.of(table);
    final JPanel nations = new JPanel(new BorderLayout());
    nations.add(table.getTableHeader(), BorderLayout.NORTH);
    nations.add(table, BorderLayout.CENTER);
    // at most 620 px at the left, narrower in a narrow window
    final int height = nations.getPreferredSize().height;
    nations.setPreferredSize(new Dimension(TABLE_WIDTH, height));
    nations.setMaximumSize(new Dimension(TABLE_WIDTH, height));
    final JPanel holder = new JPanel();
    holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
    holder.add(nations);
    holder.add(Box.createHorizontalGlue());
    return holder;
  }

  // ---- what it gives: the technology's techAbilityAttachment

  private void showGives(final TechName technology, final Optional<String> builtIn) {
    final String tech = technology.getName();
    final Game game = project.game();
    if (builtIn.isPresent() && !builtIn.get().equals(tech)) {
      details.note(
          "It is TripleA's "
              + builtIn.get()
              + " under a name of its own. TripleA lets such a technology have no effects of its"
              + " own: it gives what "
              + builtIn.get()
              + " gives.");
      return;
    }
    final boolean set = TechXml.ability(game, tech).isPresent();
    if (builtIn.isPresent() && !set) {
      details.note(
          STANDARD_EFFECTS.containsKey(tech)
              ? "Nothing is set here, so TripleA gives it its standard effect: "
                  + STANDARD_EFFECTS.get(tech)
                  + ". The first value set here takes the place of all of it."
              : "Built into TripleA: unit options and game options say what it does. What is"
                  + " set here comes on top.");
    }
    final List<Option> options = TechXml.abilityOptions(game, tech);
    final List<JLabel> labels = new ArrayList<>();
    for (int row = 0; row < options.size(); row++) {
      final int at = row;
      TechXml.unitNumber(options.get(row))
          .ifPresent(number -> details.row(newUnitNumberRow(tech, at, number, labels)));
    }
    // the numbers line up behind the longest label
    final int width = labels.stream().mapToInt(l -> l.getPreferredSize().width).max().orElse(0);
    for (final JLabel label : labels) {
      final Dimension size = new Dimension(width, label.getPreferredSize().height);
      label.setPreferredSize(size);
      label.setMaximumSize(size);
    }
    final JButton add =
        Theme.newAddButton(
            "Add bonus…",
            "A number for a unit type, eg +1 attack for fighters",
            () -> addBonus(tech));
    details.row(leftAligned(add));
    details.fold(
        "technology.more",
        "Rockets, war bonds, factories, airborne forces",
        moreSet(game, tech),
        f -> showMoreGives(f, tech));
    final List<Option> others = TechXml.otherAbilityOptions(game, tech);
    if (!others.isEmpty()) {
      final String names =
          String.join(", ", others.stream().map(Option::getName).distinct().toList());
      details.row(
          Help.link(
              others.size() + " more it sets (" + names + ") in",
              "Attachments",
              // as Attachments finds one: by the name the map writes it with
              TechXml.ability(game, tech).orElseThrow().getValue().getName()
                  + " of "
                  + tech
                  + ": "
                  + others.getFirst().getName()));
    }
    if (builtIn.isPresent() && set && STANDARD_EFFECTS.containsKey(tech)) {
      final JButton standard = new JButton("Use TripleA's standard effect");
      Theme.setTertiaryButtonStyle(standard);
      standard.setToolTipText("Removes what is set here: " + STANDARD_EFFECTS.get(tech));
      standard.addActionListener(e -> useStandard(tech));
      details.row(leftAligned(standard));
    }
  }

  /** How many of the rarer effects are set: the numbers, airborne forces and the unit lists. */
  private static int moreSet(final Game game, final String tech) {
    return (int)
        TechXml.abilityOptions(game, tech).stream()
            .filter(o -> TechXml.unitNumber(o).isEmpty())
            .count()
        - TechXml.otherAbilityOptions(game, tech).size();
  }

  private void useStandard(final String tech) {
    if (Dialogs.confirmRemove(
        page,
        "Use the standard effect",
        "Remove what "
            + tech
            + " gives here?\nTripleA then gives it: "
            + STANDARD_EFFECTS.get(tech),
        List.of(),
        "Remove")) {
      project.edit(g -> TechXml.withoutAbility(g, tech));
    }
  }

  /** "fighter · Attack [1] ×": a number the technology gives a unit type. */
  private JComponent newUnitNumberRow(
      final String tech, final int row, final UnitNumber number, final List<JLabel> labels) {
    final String what = TechXml.UNIT_NUMBERS.get(number.option());
    final JLabel label = new JLabel(number.unit() + " · " + what);
    label.setToolTipText(number.option());
    labels.add(label);
    final JSpinner amount =
        Form.newNumber(
            number.number(),
            -99,
            999,
            chosen ->
                editHere(
                    g ->
                        TechXml.withUnitNumberAt(
                            g, tech, row, new UnitNumber(number.option(), chosen, number.unit()))));
    amount.setMaximumSize(amount.getPreferredSize());
    label.setLabelFor(amount);
    final JPanel line = new JPanel();
    line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
    line.setOpaque(false);
    line.add(label);
    line.add(Box.createHorizontalStrut(Theme.GAP_S));
    line.add(amount);
    line.add(Box.createHorizontalStrut(Theme.GAP_XS));
    line.add(
        Theme.newIconButton(
            "×",
            "Remove " + what + " for " + number.unit(),
            () -> project.edit(g -> TechXml.withoutUnitNumberAt(g, tech, row, number))));
    line.add(Box.createHorizontalGlue());
    return line;
  }

  /** A step made by a field that keeps the focus: the details are not built again. */
  private void editHere(final UnaryOperator<Game> change) {
    editingHere = true;
    try {
      project.edit(change);
    } finally {
      editingHere = false;
    }
  }

  private void addBonus(final String tech) {
    final List<String> options = List.copyOf(TechXml.UNIT_NUMBERS.keySet());
    final JComboBox<String> what =
        new JComboBox<>(TechXml.UNIT_NUMBERS.values().toArray(String[]::new));
    final JComboBox<String> unit =
        new JComboBox<>(MapXml.unitTypes(project.game()).toArray(String[]::new));
    final JSpinner amount = Form.newNumber(1, -99, 999, n -> {});
    final Form form = new Form(320);
    form.field("What", what);
    form.field("Unit type", unit);
    form.number("By how much", amount);
    form.end();
    if (Dialogs.ask(
        page, "Add a bonus of " + tech, form, "Add", () -> unit.getSelectedItem() != null)) {
      final UnitNumber added =
          new UnitNumber(
              options.get(what.getSelectedIndex()),
              (Integer) amount.getValue(),
              (String) unit.getSelectedItem());
      project.edit(g -> TechXml.withUnitNumberAdded(g, tech, added));
    }
  }

  private void showMoreGives(final Form f, final String tech) {
    f.section("Rockets");
    f.note("Which units fire them is a bonus above: Rocket dice.");
    number(f, tech, TechXml.ROCKET_DISTANCE);
    number(f, tech, TechXml.ROCKETS_PER_TERRITORY);
    f.section("War bonds");
    number(f, tech, TechXml.WAR_BOND_DICE);
    number(f, tech, TechXml.WAR_BOND_SIDES);
    f.section("Factories");
    f.note("How many more units a factory produces is a bonus above: Units it can produce.");
    number(f, tech, TechXml.MIN_TERRITORY_VALUE);
    number(f, tech, TechXml.REPAIR_DISCOUNT);
    f.section("Units gain");
    f.field("May blitz", unitsLine(tech, "May blitz", TechXml.CAN_BLITZ));
    f.field("May bombard", unitsLine(tech, "May bombard", TechXml.CAN_BOMBARD));
    f.section("Airborne forces");
    final JCheckBox airborne =
        new JCheckBox(
            "Units can drop from a base as airborne forces",
            TechXml.abilityFlag(project.game(), tech, TechXml.AIRBORNE_FORCES));
    airborne.setOpaque(false);
    airborne.addActionListener(
        e ->
            project.edit(
                g ->
                    airborne.isSelected()
                        ? TechXml.withAbilityFlag(g, tech, TechXml.AIRBORNE_FORCES)
                        : TechXml.withoutAbilityFlag(g, tech, TechXml.AIRBORNE_FORCES)));
    f.row(airborne);
    f.note("How many a base launches each turn is a bonus above: Airborne units it launches.");
    number(f, tech, TechXml.AIRBORNE_DISTANCE);
    f.field("Units that can drop", unitsLine(tech, "Units that can drop", TechXml.AIRBORNE_TYPES));
    f.field(
        "Bases they start from", unitsLine(tech, "Bases they start from", TechXml.AIRBORNE_BASES));
    TechXml.shotAtBy(project.game(), tech)
        .forEach(
            (aaType, units) ->
                f.field(
                    "Anti-air of the kind " + aaType + " shoots at",
                    chooseLine(
                        units,
                        "Anti-air of the kind " + aaType + " shoots at",
                        picked ->
                            project.edit(g -> TechXml.withShotAtBy(g, tech, aaType, picked)))));
    final JButton addAa =
        Theme.newAddButton(
            "Add anti-air kind…",
            "A kind of anti-air that shoots at the units dropping",
            () -> addShotAtBy(tech));
    f.row(leftAligned(addAa));
  }

  private void number(final Form f, final String tech, final AbilityNumber number) {
    final int value = TechXml.abilityNumber(project.game(), tech, number);
    final JSpinner spinner =
        number.blank().isEmpty()
            ? Form.newNumber(
                value,
                0,
                number.max(),
                chosen -> editHere(g -> TechXml.withAbilityNumber(g, tech, number, chosen)))
            : Form.newBlankNumber(
                value,
                -1,
                number.max(),
                number.blank(),
                chosen -> editHere(g -> TechXml.withAbilityNumber(g, tech, number, chosen)));
    spinner.setToolTipText(number.option());
    f.number(number.label(), spinner);
  }

  /** The unit types an option lists, or that gain an ability, short, with "Choose…". */
  private JComponent unitsLine(final String tech, final String title, final String option) {
    final boolean ability = option.equals(TechXml.CAN_BLITZ) || option.equals(TechXml.CAN_BOMBARD);
    final Game game = project.game();
    return chooseLine(
        ability ? TechXml.gaining(game, tech, option) : TechXml.abilityUnits(game, tech, option),
        title,
        picked ->
            project.edit(
                g ->
                    ability
                        ? TechXml.withGaining(g, tech, option, picked)
                        : TechXml.withAbilityUnits(g, tech, option, picked)));
  }

  private JComponent chooseLine(
      final List<String> chosen, final String title, final Consumer<List<String>> onPick) {
    final JLabel names = new JLabel(NationsEditor.shortList(chosen));
    names.setToolTipText(chosen.isEmpty() ? null : String.join(", ", chosen));
    final JButton choose = new JButton("Choose…");
    Theme.setTertiaryButtonStyle(choose);
    choose.getAccessibleContext().setAccessibleName("Choose: " + title);
    choose.addActionListener(
        e ->
            Dialogs.pickMany(
                    page,
                    title,
                    // a unit type the game no longer has stays listed, to be unticked
                    Stream.concat(MapXml.unitTypes(project.game()).stream(), chosen.stream())
                        .distinct()
                        .toList(),
                    chosen,
                    "Choose")
                .ifPresent(onPick));
    return leftAligned(names, choose);
  }

  private void addShotAtBy(final String tech) {
    final Game game = project.game();
    // the kinds the game's anti-air units have, typeAA; "AA" is a unit's kind when it names none
    final List<String> kinds =
        Stream.concat(
                Stream.of("AA"),
                AttachmentsXml.attachments(game).stream()
                    .flatMap(a -> AttachmentsXml.options(a).stream())
                    .filter(o -> AttachmentsXml.isOption(o, "typeAA"))
                    .map(Option::getValue)
                    .filter(Objects::nonNull))
            .distinct()
            .filter(kind -> !TechXml.shotAtBy(game, tech).containsKey(kind))
            .toList();
    if (kinds.isEmpty()) {
      Dialogs.warn(page, "Add anti-air kind", "Every kind of anti-air of the game is listed.");
      return;
    }
    Dialogs.pick(page, "Add anti-air kind", "The kind of anti-air", kinds, kind -> kind, "Next")
        .flatMap(
            kind ->
                Dialogs.pickMany(
                        page,
                        "Anti-air of the kind " + kind + " shoots at",
                        MapXml.unitTypes(game),
                        List.of(),
                        "Add")
                    .map(units -> Map.entry(kind, units)))
        .ifPresent(
            shot ->
                project.edit(
                    g -> TechXml.withShotAtBy(g, tech, shot.getKey(), shot.getValue())));
  }

  private static JPanel leftAligned(final JComponent... parts) {
    final JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    line.setOpaque(false);
    for (final JComponent part : parts) {
      if (line.getComponentCount() > 0) {
        line.add(Box.createHorizontalStrut(Theme.GAP_S));
      }
      line.add(part);
    }
    return line;
  }

  private void removeTech(final String tech) {
    if (RulesXml.isNamedInAttachments(project.game(), tech)) {
      Dialogs.warn(
          page,
          "Remove technology",
          tech + " is named in an attachment, eg its techAbilityAttachment; it stays.");
    } else if (Dialogs.confirmRemove(
        page,
        "Remove technology",
        "Remove " + tech + "?\nIt is also taken out of every nation's research chart.",
        List.of(),
        "Remove")) {
      project.edit(g -> TechXml.withoutTech(TechXml.withStandardTechs(g), tech));
    }
  }

  /** The header: why only built-in ones have "Has it at start", and what "PUs per roll" is. */
  private static final class AtStartHeader extends JTableHeader {
    @Serial private static final long serialVersionUID = 1L;

    AtStartHeader(final JTable table) {
      super(table.getColumnModel());
      ToolTipManager.sharedInstance().registerComponent(this);
    }

    @Override
    public String getToolTipText(final MouseEvent event) {
      final int column = columnAtPoint(event.getPoint());
      if (column == getColumnModel().getColumnCount() - 1) {
        return COST_TIP;
      }
      return column == 2 ? AT_START_TIP : null;
    }
  }

  /**
   * A row per nation: the chart it researches the technology in, whether it has it, and what a
   * research roll costs it (the same for every technology).
   */
  private static final class NationsTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient TechnologyEditor editor;
    private final String tech;
    private final transient Optional<String> builtIn;

    NationsTable(
        final TechnologyEditor editor, final String tech, final Optional<String> builtIn) {
      this.editor = editor;
      this.tech = tech;
      this.builtIn = builtIn;
    }

    private List<String> nations() {
      return MapXml.nations(editor.project.game());
    }

    @Override
    public int getRowCount() {
      return nations().size();
    }

    @Override
    public int getColumnCount() {
      return builtIn.isPresent() ? 4 : 3;
    }

    // the last column, after "Has it at start" where there is one
    private boolean isCost(final int column) {
      return column == getColumnCount() - 1;
    }

    @Override
    public String getColumnName(final int column) {
      return isCost(column)
          ? "PUs per roll"
          : List.of("Nation", "Research chart", "Has it at start").get(column);
    }

    @Override
    public Class<?> getColumnClass(final int column) {
      return isCost(column) ? Integer.class : column == 2 ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
      return column > 0;
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final String nation = nations().get(row);
      if (isCost(column)) {
        return TechXml.techCost(editor.project.game(), nation);
      }
      return switch (column) {
        case 0 -> nation;
        case 1 -> TechXml.categoryOf(editor.listed(), nation, tech).orElse(NOT_RESEARCHED);
        default -> TechXml.hasAtStart(editor.project.game(), nation, builtIn.orElseThrow());
      };
    }

    @Override
    public void setValueAt(final Object value, final int row, final int column) {
      if (value == null || value.equals(getValueAt(row, column))) {
        return;
      }
      final String nation = nations().get(row);
      if (isCost(column)) {
        // the game refuses a cost below 0
        editor.project.edit(g -> TechXml.withTechCost(g, nation, Math.max(0, (Integer) value)));
      } else if (column == 1) {
        editor.setChart(nation, tech, String.valueOf(value));
      } else {
        final String engineTech = builtIn.orElseThrow();
        editor.project.edit(
            g ->
                (Boolean) value
                    ? TechXml.withAtStart(g, nation, engineTech)
                    : TechXml.withoutAtStart(g, nation, engineTech));
      }
    }
  }
}
