package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import games.strategy.triplea.Constants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The politics, in two tabs: how each nation starts with each other nation, and the relationship
 * types, eg war, allied or neutral, with what they allow. Alliances are set in Nations.
 */
final class PoliticsEditor implements Editor {
  private static final String BY_ALLIANCE = "By alliance";
  private static final String ARCHETYPE = "archeType";
  private static final List<String> ARCHETYPES =
      List.of(
          Constants.RELATIONSHIP_ARCHETYPE_WAR,
          Constants.RELATIONSHIP_ARCHETYPE_ALLIED,
          Constants.RELATIONSHIP_ARCHETYPE_NEUTRAL);
  private static final List<String> HEART_LABELS = List.of("War", "Allied", "Neutral");
  private static final List<String> KIND_LABELS = List.of("At war", "Allied", "Neutral");
  // the cells' colors by kind, as the game's diplomacy overview has them: war red, allied green
  private static final List<Supplier<Color>> KIND_COLORS =
      List.of(Theme::getDangerColor, Theme::getSuccessColor, Theme::getSecondaryTextColor);
  private static final List<String> SETTINGS =
      List.of(
          Constants.RELATIONSHIP_PROPERTY_DEFAULT,
          Constants.RELATIONSHIP_PROPERTY_TRUE,
          Constants.RELATIONSHIP_PROPERTY_FALSE);
  private static final List<String> SETTING_LABELS = List.of("Like its kind", "Yes", "No");
  // the options of a relationship type that maps use most, in plain words
  private static final List<Map.Entry<String, String>> TYPE_RULES =
      List.of(
          Map.entry("canMoveLandUnitsOverOwnedLand", "Land units move through"),
          Map.entry("canMoveAirUnitsOverOwnedLand", "Planes fly over"),
          Map.entry("canLandAirUnitsOnOwnedLand", "Planes land"),
          Map.entry("canMoveIntoDuringCombatMove", "Move in during combat move"),
          Map.entry("canMoveThroughCanals", "Use canals"),
          Map.entry("canTakeOverOwnedTerritory", "Take land by moving in"),
          Map.entry("givesBackOriginalTerritories", "Give back freed land"),
          Map.entry("rocketsCanFlyOver", "Rockets fly over"));
  private static final int RULE_LABEL_WIDTH = 240;
  private static final int RULE_BOX_WIDTH = 160;
  private static final int OPTION_FIELD_WIDTH = 240;
  // a cell's color: strong for a type chosen, light where the pair goes by alliance
  private static final double SET = 0.4;
  private static final double BY_KIND = 0.15;
  // a name longer than this goes on two lines in the matrix
  private static final int SHORT_NAME = 10;

  private final MapProject project;
  private final Consumer<String> openTool;
  private final JTabbedPane page = new JTabbedPane();
  private final SearchList types;
  private final JPanel startPage = new JPanel(new BorderLayout(0, Theme.GAP_S));
  private final Form typeForm = new Form(420);
  private @Nullable String chosenType;
  private boolean startStale;
  // a change made in the type's details: they stay, so the field keeps its focus
  private boolean editingHere;
  // the option's text typed and not yet taken, for commit()
  private @Nullable Runnable typing;

  PoliticsEditor(final MapProject project) {
    this(project, tool -> {});
  }

  /**
   * @param openTool switches to the tool of this title, eg "Nations"
   */
  PoliticsEditor(final MapProject project, final Consumer<String> openTool) {
    this.project = project;
    this.openTool = openTool;
    types =
        new SearchList(
                "relationship type",
                "relationship types",
                () -> RulesXml.relationshipTypes(project.game()),
                t -> t,
                t -> KIND_LABELS.get(kindOf(project.game(), t)),
                t -> {
                  chosenType = t;
                  showType();
                })
            .withAdd("Add", "Add a relationship type", this::addType);
    // room below the tabs, as Production's lists have
    types.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_M, 0, 0, Theme.GAP_S));
    startPage.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_M, 0, 0, 0));
    page.addTab("How nations start", startPage);
    // back on this tab, the matrix as the game is now
    page.addChangeListener(e -> showing());
    page.addTab("", Panels.sideBySide(types, SwingComponents.newJScrollPane(typeForm)));
    project.onChange(
        () -> {
          types.refresh();
          refreshStart();
          if (!editingHere) {
            showType();
          }
          showTypeCount();
        });
    // built once the tool shows: 900 cells on a big map
    startStale = true;
    showType();
    showTypeCount();
  }

  @Override
  public String title() {
    return "Politics";
  }

  @Override
  public Glyph glyph() {
    return Glyph.POLITICS;
  }

  @Override
  public String hint() {
    return "Nations start allied or at war by their alliance (set in Nations). Choose a type here"
        + " only where a pair starts differently, eg neutral.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    if (typing != null) {
      typing.run();
    }
  }

  private void showTypeCount() {
    page.setTitleAt(
        1, "Relationship types (" + RulesXml.relationshipTypes(project.game()).size() + ")");
  }

  // ---- how nations start

  @Override
  public void showing() {
    if (startStale) {
      showStart();
    }
  }

  /** The matrix again now while it shows, else once the tool shows: it is big on a big map. */
  private void refreshStart() {
    if (startPage.isShowing()) {
      showStart();
    } else {
      startStale = true;
    }
  }

  /**
   * The diplomacy matrix, as the game shows it: a row and a column per nation, each cell how the
   * two start, in the color of its kind; a cell chooses it, a row's nation resets the row.
   */
  private void showStart() {
    startStale = false;
    startPage.removeAll();
    final Game game = project.game();
    final List<String> all = MapXml.nations(game);
    if (all.isEmpty()) {
      startPage.add(
          Panels.empty(
              Glyph.POLITICS,
              "No nations yet",
              "Add nations first: politics is about how they start with each other.",
              "Go to Nations",
              () -> openTool.accept("Nations")),
          BorderLayout.CENTER);
    } else {
      startPage.add(
          Theme.newSecondaryLabel(
              "Click a cell to choose how two nations start; each choice is mutual. Lighter cells"
                  + " go by alliance."),
          BorderLayout.NORTH);
      // the scroll bar and the legend right below the matrix, not at the page's foot
      final JScrollPane matrix = matrix(game, all);
      // room for the scroll bar, which shows below the cells when the matrix is wider
      matrix.setMaximumSize(
          new Dimension(
              Integer.MAX_VALUE,
              matrix.getPreferredSize().height
                  + matrix.getHorizontalScrollBar().getPreferredSize().height
                  + Theme.GAP_S));
      final JPanel below = new JPanel();
      below.setLayout(new BoxLayout(below, BoxLayout.Y_AXIS));
      final JComponent legend = legend();
      for (final JComponent part : List.of(matrix, legend)) {
        part.setAlignmentX(Component.LEFT_ALIGNMENT);
        below.add(part);
      }
      below.add(Box.createVerticalGlue());
      startPage.add(below, BorderLayout.CENTER);
    }
    startPage.revalidate();
    startPage.repaint();
  }

  /** The cells in a grid of one size, the nations' names as headers that stay while it scrolls. */
  private JScrollPane matrix(final Game game, final List<String> all) {
    final Map<String, List<String>> alliances = new HashMap<>();
    all.forEach(n -> alliances.put(n, MapXml.alliances(game, n)));
    final List<JButton> cells = new ArrayList<>();
    for (final String nation : all) {
      for (final String other : all) {
        cells.add(cell(game, nation, other, alliances));
      }
    }
    final List<JLabel> columnNames =
        all.stream().map(n -> new JLabel(twoLines(n), SwingConstants.CENTER)).toList();
    // a size smaller, so a game of 14 nations fits the width of a big screen
    concat(cells, columnNames).forEach(PoliticsEditor::smaller);
    final List<JButton> rowNames = all.stream().map(n -> rowName(game, n)).toList();
    // one size for every cell and column name, so the headers line up with the cells
    final Dimension size = new Dimension(0, 0);
    for (final JComponent part : concat(cells, columnNames)) {
      size.width = Math.max(size.width, part.getPreferredSize().width);
      size.height = Math.max(size.height, part.getPreferredSize().height);
    }
    concat(cells, columnNames).forEach(part -> part.setPreferredSize(size));
    final JPanel grid = new JPanel(new GridLayout(all.size(), all.size(), 1, 1));
    cells.forEach(grid::add);
    final JPanel columns = new JPanel(new GridLayout(1, all.size(), 1, 0));
    columnNames.forEach(columns::add);
    final JPanel rows = new JPanel(new GridLayout(all.size(), 1, 0, 1));
    rowNames.forEach(
        name -> {
          name.setPreferredSize(new Dimension(name.getPreferredSize().width, size.height));
          rows.add(name);
        });
    final JScrollPane scroll = new JScrollPane(topLeft(grid));
    // the corner above the row names as the page, not a lighter box
    scroll.setCorner(JScrollPane.UPPER_LEFT_CORNER, new JPanel());
    scroll.setColumnHeaderView(topLeft(columns));
    scroll.setRowHeaderView(topLeft(rows));
    scroll.setBorder(null);
    return scroll;
  }

  private static List<JComponent> concat(
      final List<? extends JComponent> first, final List<? extends JComponent> second) {
    final List<JComponent> both = new ArrayList<>(first);
    both.addAll(second);
    return both;
  }

  /** The component at its size in the top left, the rest of the room empty. */
  private static JComponent topLeft(final JComponent component) {
    final JPanel left = new JPanel(new BorderLayout());
    left.add(component, BorderLayout.WEST);
    final JPanel top = new JPanel(new BorderLayout());
    top.add(left, BorderLayout.NORTH);
    return top;
  }

  /** The row's nation: it offers to set all its pairs back to By alliance. */
  private JButton rowName(final Game game, final String nation) {
    final JButton name = new JButton(plain(nation));
    name.setHorizontalAlignment(SwingConstants.LEFT);
    name.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
    final boolean anySet =
        others(game, nation).stream()
            .anyMatch(o -> RulesXml.relationship(game, nation, o).isPresent());
    name.setToolTipText(anySet ? "Set all pairs of " + nation + " to By alliance" : null);
    name.addActionListener(
        e -> {
          final JPopupMenu menu = new JPopupMenu();
          final JMenuItem reset = new JMenuItem("Set all of " + nation + " to By alliance");
          // nothing to reset: the name stays, the choice is off
          reset.setEnabled(anySet);
          reset.addActionListener(a -> setAllByAlliance(nation));
          menu.add(reset);
          menu.show(name, 0, name.getHeight());
        });
    return name;
  }

  /** How the two start, in its kind's color, lighter when by alliance; it opens the choice. */
  JButton cell(final Game game, final String nation, final String other) {
    final Map<String, List<String>> alliances = new HashMap<>();
    List.of(nation, other).forEach(n -> alliances.put(n, MapXml.alliances(game, n)));
    return cell(game, nation, other, alliances);
  }

  private JButton cell(
      final Game game,
      final String nation,
      final String other,
      final Map<String, List<String>> alliances) {
    final JButton cell = new JButton();
    if (nation.equals(other)) {
      cell.setText("–");
      cell.putClientProperty(
          FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
      cell.setEnabled(false);
      return cell;
    }
    final Optional<String> type = RulesXml.relationship(game, nation, other);
    final int kind =
        type.map(t -> kindOf(game, t))
            .orElseGet(
                () ->
                    alliances.get(nation).stream().anyMatch(alliances.get(other)::contains)
                        ? 1
                        : 0);
    final String said = type.map(PoliticsEditor::plain).orElse(KIND_LABELS.get(kind));
    cell.setText(twoLines(said));
    cell.setBackground(cellColor(kind, type.isPresent() ? SET : BY_KIND));
    // flat squares in the kind's color, as the game's overview has them
    cell.putClientProperty(
        FlatClientProperties.STYLE,
        "arc: 0; borderWidth: 0; focusWidth: 0; innerFocusWidth: 2; margin: 2,3,2,3");
    if (type.isEmpty()) {
      cell.setForeground(Theme.getSecondaryTextColor());
    }
    final String how =
        type.map(t -> "start as " + t)
            .orElse("go by alliance: " + KIND_LABELS.get(kind).toLowerCase(Locale.ROOT));
    cell.setToolTipText(nation + " and " + other + " " + how);
    cell.getAccessibleContext().setAccessibleName(nation + " and " + other + ": " + said);
    cell.addActionListener(e -> startMenu(nation, other, type).show(cell, 0, cell.getHeight()));
    return cell;
  }

  private static void smaller(final JComponent part) {
    part.setFont(part.getFont().deriveFont(part.getFont().getSize2D() - 1));
  }

  /** A type's name in words, eg "Unfriendly Neutral" for Unfriendly_Neutral. */
  private static String plain(final String name) {
    return name.replace('_', ' ');
  }

  /** The words of a long name in two lines, so the columns stay narrow. */
  private static String twoLines(final String text) {
    final String words = plain(text);
    final int middle = words.indexOf(' ', words.length() / 2 - 2);
    return words.length() <= SHORT_NAME || middle < 0
        ? words
        : "<html><center>"
            + SearchList.escape(words.substring(0, middle))
            + "<br>"
            + SearchList.escape(words.substring(middle + 1))
            + "</center></html>";
  }

  /** The kind's color laid on the panel's background: solid, so a repaint does not darken it. */
  private static Color cellColor(final int kind, final double share) {
    final Color color = KIND_COLORS.get(kind).get();
    final Color under =
        Optional.ofNullable(UIManager.getColor("Panel.background")).orElse(Color.WHITE);
    return new Color(
        (int) Math.round(color.getRed() * share + under.getRed() * (1 - share)),
        (int) Math.round(color.getGreen() * share + under.getGreen() * (1 - share)),
        (int) Math.round(color.getBlue() * share + under.getBlue() * (1 - share)));
  }

  /** By alliance and every relationship type, the one now ticked; a choice is one step. */
  JPopupMenu startMenu(final String nation, final String other, final Optional<String> current) {
    final JPopupMenu menu = new JPopupMenu();
    final ButtonGroup choice = new ButtonGroup();
    final JRadioButtonMenuItem byAlliance =
        new JRadioButtonMenuItem(BY_ALLIANCE, current.isEmpty());
    byAlliance.addActionListener(
        e -> project.edit(g -> RulesXml.withoutRelationship(g, nation, other)));
    choice.add(byAlliance);
    menu.add(byAlliance);
    menu.addSeparator();
    final Game game = project.game();
    final List<String> types = RulesXml.relationshipTypes(game);
    for (final String type : types) {
      final JRadioButtonMenuItem item =
          new JRadioButtonMenuItem(plain(type), current.filter(type::equals).isPresent());
      item.addActionListener(
          e -> project.edit(g -> RulesXml.withRelationship(g, nation, other, type)));
      choice.add(item);
      menu.add(item);
    }
    // a kind the game has no type of yet, eg in a new map: chosen, its type is made too
    boolean apart = types.isEmpty();
    for (int kind = 0; kind < ARCHETYPES.size(); kind++) {
      final int wanted = kind;
      if (types.stream().noneMatch(t -> kindOf(game, t) == wanted)) {
        if (!apart) {
          menu.addSeparator();
          apart = true;
        }
        final String type = RulesXml.freeName(Set.copyOf(types), HEART_LABELS.get(kind));
        final JMenuItem item = new JMenuItem(KIND_LABELS.get(kind) + " (new type " + type + ")");
        item.addActionListener(
            e ->
                project.edit(
                    g ->
                        RulesXml.withRelationship(
                            RulesXml.withRelationshipType(g, type, ARCHETYPES.get(wanted)),
                            nation,
                            other,
                            type)));
        menu.add(item);
      }
    }
    return menu;
  }

  /** The kinds' colors, as the cells have them. */
  private static JComponent legend() {
    final JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_M, 0));
    for (int kind = 0; kind < KIND_LABELS.size(); kind++) {
      final JLabel label = new JLabel(KIND_LABELS.get(kind) + " at heart");
      label.setIcon(new MapLegend.Swatch(cellColor(kind, SET)));
      legend.add(label);
    }
    final JLabel light = new JLabel("Lighter: no type set, by alliance");
    light.setIcon(new MapLegend.Swatch(cellColor(1, BY_KIND)));
    legend.add(light);
    return legend;
  }

  private static List<String> others(final Game game, final String nation) {
    return MapXml.nations(game).stream().filter(n -> !n.equals(nation)).toList();
  }

  private static int kindOf(final Game game, final String type) {
    return Math.max(0, ARCHETYPES.indexOf(RulesXml.archetype(game, type)));
  }

  /** Every pair of the nation goes by alliance, as one step. */
  void setAllByAlliance(final String nation) {
    project.edit(
        g -> {
          Game changed = g;
          for (final String other : others(g, nation)) {
            if (RulesXml.relationship(changed, nation, other).isPresent()) {
              changed = RulesXml.withoutRelationship(changed, nation, other);
            }
          }
          return changed;
        });
  }

  // ---- relationship types

  private void showType() {
    typing = null;
    typeForm.clear();
    final Game game = project.game();
    final List<String> all = RulesXml.relationshipTypes(game);
    if (all.isEmpty()) {
      typeForm.row(
          Panels.empty(
              Glyph.POLITICS,
              "No relationship types",
              "TripleA uses Allied and War by alliance until you add one, eg Neutrality.",
              "Add type",
              this::addType));
      typeForm.end();
      return;
    }
    if (chosenType == null || !all.contains(chosenType)) {
      chosenType = all.getFirst();
      types.choose(chosenType);
    }
    final String type = chosenType;
    final int pairs = RulesXml.pairsOf(game, type);
    typeForm.title(type);
    typeForm.subtitle(
        HEART_LABELS.get(kindOf(game, type))
            + " at heart · "
            + switch (pairs) {
              case 0 -> "not used at the start";
              case 1 -> "used by 1 pair at the start";
              default -> "used by " + pairs + " pairs at the start";
            });
    typeForm.row(heartRow(game, type));
    typeForm.section("What nations in it may do in each other's land");
    TYPE_RULES.forEach(rule -> typeForm.row(ruleRow(type, rule.getKey(), rule.getValue())));
    final List<Option> others = otherOptions(game, type);
    if (!others.isEmpty()) {
      typeForm.more(
          "politics.type", others.size(), f -> others.forEach(o -> f.row(optionRow(type, o))));
    }
    typeForm.remove("Remove type", () -> removeType(type));
    typeForm.end();
  }

  /** "At heart ( ) War ( ) Allied ( ) Neutral". */
  private JComponent heartRow(final Game game, final String type) {
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    row.setOpaque(false);
    row.add(new JLabel("At heart"));
    final ButtonGroup group = new ButtonGroup();
    final int kind = kindOf(game, type);
    for (int i = 0; i < ARCHETYPES.size(); i++) {
      final String archetype = ARCHETYPES.get(i);
      final JRadioButton button = new JRadioButton(HEART_LABELS.get(i), i == kind);
      button.addActionListener(
          e -> {
            if (!archetype.equals(RulesXml.archetype(project.game(), type))) {
              project.edit(g -> RulesXml.withArchetype(g, type, archetype));
            }
          });
      group.add(button);
      row.add(button);
    }
    return row;
  }

  private JComponent ruleRow(final String type, final String option, final String label) {
    final String value = RulesXml.relationshipOption(project.game(), type, option);
    final JComboBox<String> box = new JComboBox<>(SETTING_LABELS.toArray(String[]::new));
    if (!SETTINGS.contains(value)) {
      // a value this editor does not know stays, unless another is chosen
      box.addItem(value);
    }
    box.setSelectedIndex(SETTINGS.contains(value) ? SETTINGS.indexOf(value) : SETTINGS.size());
    box.addActionListener(
        e -> {
          final int index = box.getSelectedIndex();
          if (index < SETTINGS.size()) {
            quietly(g -> RulesXml.withRelationshipOption(g, type, option, SETTINGS.get(index)));
          }
        });
    final JLabel name = new JLabel(label);
    name.setLabelFor(box);
    return line(fixed(name, RULE_LABEL_WIDTH), fixed(box, RULE_BOX_WIDTH));
  }

  private void quietly(final UnaryOperator<Game> change) {
    editingHere = true;
    try {
      project.edit(change);
    } finally {
      editingHere = false;
    }
  }

  /** The type's options the details have no words for: all but its kind and the 8 rules. */
  private static List<Option> otherOptions(final Game game, final String type) {
    return RulesXml.relationshipOptions(game, type).stream()
        .filter(o -> !ARCHETYPE.equals(o.getName()))
        .filter(o -> TYPE_RULES.stream().noneMatch(r -> r.getKey().equals(o.getName())))
        .toList();
  }

  /** The option by TripleA's name, its value to change, and × to take it out. */
  private JComponent optionRow(final String type, final Option option) {
    final String name = option.getName();
    final String value = Objects.toString(option.getValue(), "");
    final JTextField text = new JTextField(value);
    final Runnable take =
        () -> {
          typing = null;
          final String typed = text.getText().strip();
          if (!typed.isEmpty() && !typed.equals(value.strip())) {
            quietly(g -> RulesXml.withRelationshipOption(g, type, name, typed));
          }
        };
    text.addActionListener(e -> take.run());
    text.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            take.run();
          }
        });
    OnTyping.attach(text, () -> typing = take);
    final JButton remove =
        Theme.newIconButton(
            "×",
            "Remove " + name,
            () ->
                project.edit(
                    g ->
                        RulesXml.withRelationshipOption(
                            g, type, name, Constants.RELATIONSHIP_PROPERTY_DEFAULT)));
    final JLabel label = new JLabel(name);
    label.setLabelFor(text);
    return line(fixed(label, RULE_LABEL_WIDTH), fixed(text, OPTION_FIELD_WIDTH), remove);
  }

  private static JComponent fixed(final JComponent component, final int width) {
    final Dimension size = new Dimension(width, component.getPreferredSize().height);
    component.setPreferredSize(size);
    component.setMaximumSize(size);
    return component;
  }

  private static JPanel line(final JComponent... parts) {
    final JPanel line = new JPanel();
    line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
    line.setOpaque(false);
    final List<JComponent> spaced = new ArrayList<>();
    for (final JComponent part : parts) {
      if (!spaced.isEmpty()) {
        spaced.add((JComponent) Box.createHorizontalStrut(Theme.GAP_S));
      }
      spaced.add(part);
    }
    spaced.forEach(line::add);
    line.add(Box.createHorizontalGlue());
    return line;
  }

  private void addType() {
    Dialogs.askName(
            page,
            "Add relationship type",
            "Name",
            "",
            "Add",
            name ->
                RulesXml.relationshipTypes(project.game()).contains(name)
                    ? Optional.of("Already a relationship type")
                    // political actions list types joined by colons
                    : name.contains(":") ? Optional.of("Must not contain :") : Optional.empty())
        .ifPresent(
            type -> {
              project.edit(
                  g ->
                      RulesXml.withRelationshipType(
                          g, type, Constants.RELATIONSHIP_ARCHETYPE_WAR));
              chosenType = type;
              types.choose(type);
              showType();
            });
  }

  private void removeType(final String type) {
    final Game game = project.game();
    // its own attachment names it too; others, eg political actions, keep it
    final List<String> named =
        RulesXml.whereNamed(RulesXml.withoutRelationshipType(game, type), type);
    if (!named.isEmpty()) {
      Dialogs.warn(
          page,
          "Remove type",
          type + " is still used by:\n" + String.join("\n", named) + "\nChange those first.");
      return;
    }
    final int pairs = RulesXml.pairsOf(game, type);
    final String consequence =
        switch (pairs) {
          case 0 -> "";
          case 1 -> "\nThe 1 pair that starts as it goes by alliance instead.";
          default -> "\nThe " + pairs + " pairs that start as it go by alliance instead.";
        };
    if (Dialogs.confirmRemove(
        page, "Remove type", "Remove " + type + "?" + consequence, List.of(), "Remove")) {
      project.edit(g -> RulesXml.withoutRelationshipType(g, type));
    }
  }
}
