package eddies.mapeditor;

import games.strategy.triplea.Constants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
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
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
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
  private static final int TABLE_WIDTH = 560;
  private static final int NAME_COLUMN_WIDTH = 200;
  private static final int RULE_LABEL_WIDTH = 240;
  private static final int RULE_BOX_WIDTH = 160;
  private static final int OPTION_FIELD_WIDTH = 240;

  private final MapProject project;
  private final Consumer<String> openTool;
  private final JTabbedPane page = new JTabbedPane();
  private final SearchList nations;
  private final SearchList types;
  private final Form startForm = new Form(420);
  private final Form typeForm = new Form(420);
  private @Nullable String chosenNation;
  private @Nullable String chosenType;
  private Optional<JTable> startTable = Optional.empty();
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
    nations =
        new SearchList(
            "nation",
            "nations",
            () -> MapXml.nations(project.game()),
            n -> n,
            n -> NationsEditor.allianceLine(project.game(), n),
            n -> {
              chosenNation = n;
              showStart();
            });
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
    for (final SearchList list : List.of(nations, types)) {
      list.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_M, 0, 0, Theme.GAP_S));
    }
    page.addTab(
        "How nations start",
        Panels.sideBySide(nations, SwingComponents.newJScrollPane(startForm)));
    page.addTab("", Panels.sideBySide(types, SwingComponents.newJScrollPane(typeForm)));
    project.onChange(
        () -> {
          nations.refresh();
          types.refresh();
          showStart();
          if (!editingHere) {
            showType();
          }
          showTypeCount();
        });
    showStart();
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
    startTable.filter(JTable::isEditing).ifPresent(t -> t.getCellEditor().stopCellEditing());
    if (typing != null) {
      typing.run();
    }
  }

  private void showTypeCount() {
    page.setTitleAt(
        1, "Relationship types (" + RulesXml.relationshipTypes(project.game()).size() + ")");
  }

  // ---- how nations start

  private void showStart() {
    startForm.clear();
    startTable = Optional.empty();
    final Game game = project.game();
    final List<String> all = MapXml.nations(game);
    if (all.isEmpty()) {
      startForm.row(
          Panels.empty(
              Glyph.POLITICS,
              "No nations yet",
              "Add nations first: politics is about how they start with each other.",
              "Go to Nations",
              () -> openTool.accept("Nations")));
      startForm.end();
      return;
    }
    if (chosenNation == null || !all.contains(chosenNation)) {
      chosenNation = all.getFirst();
      nations.choose(chosenNation);
    }
    final String nation = chosenNation;
    final List<String> others = others(game, nation);
    startForm.title(nation);
    startForm.subtitle(startLine(game, nation, others));
    startForm.note(
        "Each choice is mutual: it also sets how the other nation starts with " + nation + ".");
    startForm.row(startTable(nation));
    final JButton reset = new JButton("Set all to By alliance");
    Theme.setTertiaryButtonStyle(reset);
    reset.setEnabled(
        others.stream().anyMatch(o -> RulesXml.relationship(game, nation, o).isPresent()));
    reset.addActionListener(e -> setAllByAlliance(nation));
    final JPanel resetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    resetRow.setOpaque(false);
    resetRow.add(reset);
    startForm.row(resetRow);
    startForm.end();
  }

  private static List<String> others(final Game game, final String nation) {
    return MapXml.nations(game).stream().filter(n -> !n.equals(nation)).toList();
  }

  /** "Axis · at war with 5 · allied with 2 · neutral with 6". */
  private static String startLine(final Game game, final String nation, final List<String> others) {
    final int[] counts = new int[ARCHETYPES.size()];
    others.forEach(o -> counts[startKind(game, nation, o)]++);
    return NationsEditor.allianceLine(game, nation)
        + " · at war with "
        + counts[0]
        + " · allied with "
        + counts[1]
        + " · neutral with "
        + counts[2];
  }

  /** The index in {@link #ARCHETYPES} of how the two start: their type's, else by alliance. */
  private static int startKind(final Game game, final String nation, final String other) {
    return RulesXml.relationship(game, nation, other)
        .map(type -> kindOf(game, type))
        .orElseGet(() -> isAllied(game, nation, other) ? 1 : 0);
  }

  private static int kindOf(final Game game, final String type) {
    return Math.max(0, ARCHETYPES.indexOf(RulesXml.archetype(game, type)));
  }

  private static boolean isAllied(final Game game, final String nation, final String other) {
    return MapXml.alliances(game, nation).stream()
        .anyMatch(MapXml.alliances(game, other)::contains);
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

  private JComponent startTable(final String nation) {
    final StartTable model = new StartTable(project, nation);
    final JTable table = new JTable(model);
    table.setRowHeight(table.getRowHeight() + Theme.GAP_S);
    table.putClientProperty("terminateEditOnFocusLost", true);
    final JComboBox<String> choices = new JComboBox<>();
    choices.addItem(BY_ALLIANCE);
    RulesXml.relationshipTypes(project.game()).forEach(choices::addItem);
    Details.comboColumn(table, 1, choices);
    final TableColumn startsAs = table.getColumnModel().getColumn(1);
    final TableCellRenderer combo = startsAs.getCellRenderer();
    // what By alliance comes to, in the secondary color, so a chosen type stands out
    startsAs.setCellRenderer(
        (t, value, selected, focused, row, column) -> {
          final Object shown =
              BY_ALLIANCE.equals(value)
                  ? byAllianceText(
                      BY_ALLIANCE
                          + (isAllied(project.game(), nation, model.others.get(row))
                              ? ": allied"
                              : ": at war"),
                      selected)
                  : value;
          return combo.getTableCellRendererComponent(t, shown, selected, focused, row, column);
        });
    table.getColumnModel().getColumn(0).setPreferredWidth(NAME_COLUMN_WIDTH);
    table.getColumnModel().getColumn(1).setPreferredWidth(TABLE_WIDTH - NAME_COLUMN_WIDTH);
    startTable = Optional.of(table);
    final JPanel withHeader = new JPanel(new BorderLayout());
    withHeader.add(table.getTableHeader(), BorderLayout.NORTH);
    withHeader.add(table, BorderLayout.CENTER);
    return capped(withHeader, TABLE_WIDTH);
  }

  private static String byAllianceText(final String text, final boolean selected) {
    return selected
        ? text
        : "<html><span style='color:"
            + Theme.convertColorToHex(Theme.getSecondaryTextColor())
            + "'>"
            + text
            + "</span></html>";
  }

  /** At most this wide, at the left, narrower in a narrow column. */
  private static JComponent capped(final JComponent component, final int width) {
    final Dimension size = new Dimension(width, component.getPreferredSize().height);
    component.setPreferredSize(size);
    component.setMaximumSize(size);
    final JPanel holder = new JPanel();
    holder.setOpaque(false);
    holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
    holder.add(component);
    holder.add(Box.createHorizontalGlue());
    return holder;
  }

  /** A row per other nation: how the nation starts with it. */
  private static final class StartTable extends AbstractTableModel {
    @Serial private static final long serialVersionUID = 1L;
    private final transient MapProject project;
    private final String nation;
    private final List<String> others;

    StartTable(final MapProject project, final String nation) {
      this.project = project;
      this.nation = nation;
      this.others = PoliticsEditor.others(project.game(), nation);
    }

    @Override
    public int getRowCount() {
      return others.size();
    }

    @Override
    public int getColumnCount() {
      return 2;
    }

    @Override
    public String getColumnName(final int column) {
      return column == 0 ? "With" : "Starts as";
    }

    @Override
    public boolean isCellEditable(final int row, final int column) {
      return column == 1;
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      final String other = others.get(row);
      return column == 0
          ? other
          : RulesXml.relationship(project.game(), nation, other).orElse(BY_ALLIANCE);
    }

    @Override
    public void setValueAt(final Object value, final int row, final int column) {
      final String other = others.get(row);
      if (value == null || value.equals(getValueAt(row, column))) {
        return;
      }
      project.edit(
          g ->
              BY_ALLIANCE.equals(value)
                  ? RulesXml.withoutRelationship(g, nation, other)
                  : RulesXml.withRelationship(g, nation, other, (String) value));
    }
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
