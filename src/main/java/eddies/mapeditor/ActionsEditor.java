package eddies.mapeditor;

import games.strategy.triplea.Constants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.ResourceList;
import org.triplea.map.data.elements.ResourceList.Resource;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.ActionsXml.Action;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The political and user actions: the buttons a nation may press in its politics or user actions
 * step. For the chosen one its texts (kept in politicstext.properties or actionstext.properties),
 * the conditions it needs, what it does, its cost and chance, and who must accept it.
 */
final class ActionsEditor implements Editor {
  private static final String POLITICAL_LABEL = "Political action";
  private static final String USER_LABEL = "User action";
  private static final Map<String, String> TEXT_LABELS =
      Map.of(
          "BUTTON", "Button",
          "DESCRIPTION", "Description",
          "NOTIFICATION_SUCCESS", "To the nation when it succeeds",
          "TARGET_NOTIFICATION_SUCCESS", "To the target when it succeeds",
          "OTHER_NOTIFICATION_SUCCESS", "To the others when it succeeds",
          "NOTIFICATION_FAILURE", "To the nation when it fails",
          "TARGET_NOTIFICATION_FAILURE", "To the target when it fails",
          "OTHER_NOTIFICATION_FAILURE", "To the others when it fails",
          "ACCEPT_QUESTION", "Question to those who must accept");
  // fires the trigger once, without testing its uses, conditions or chance
  private static final String FIRE_ONCE = ":1:false:false:false:false";

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final SearchList list;
  private final Form inspector = new Form(320);
  private final List<Runnable> pendingTexts = new ArrayList<>();
  private Map<String, Action> listed = Map.of();
  private @Nullable Action selected;
  private boolean editingHere;

  ActionsEditor(final MapProject project) {
    this.project = project;
    list = new SearchList("Search action", this::listedNames, this::detail, this::choose);
    final JButton add = new JButton("Add action...");
    add.addActionListener(e -> addAction());
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(add, BorderLayout.SOUTH);
    page.add(
        Panels.sideBySide(listColumn, SwingComponents.newJScrollPane(inspector)),
        BorderLayout.CENTER);
    project.onChange(
        () -> {
          list.refresh();
          if (!editingHere) {
            showInspector();
          }
        });
    showInspector();
  }

  @Override
  public String title() {
    return "Actions";
  }

  @Override
  public Glyph glyph() {
    return Glyph.ACTIONS;
  }

  @Override
  public String hint() {
    return "The buttons a nation may press in its politics step, eg to declare war, and its user"
        + " actions, which fire triggers. Each has its texts, the conditions it needs, a cost and"
        + " a chance. A nation uses them only with a politics or user actions step in Turn order.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    List.copyOf(pendingTexts).forEach(Runnable::run);
  }

  private List<String> listedNames() {
    final Map<String, Action> byName = new LinkedHashMap<>();
    ActionsXml.actions(project.game())
        .forEach(a -> byName.put(shortName(a) + " (" + a.nation() + ")", a));
    listed = byName;
    return List.copyOf(byName.keySet());
  }

  private static String shortName(final Action action) {
    final String name = action.name();
    final String prefix =
        action.isPolitical()
            ? Constants.POLITICALACTION_ATTACHMENT_PREFIX
            : Constants.USERACTION_ATTACHMENT_PREFIX;
    final String rest = name.substring(prefix.length()).replaceFirst("^_", "");
    return rest.isEmpty() ? name : rest;
  }

  private String detail(final String shown) {
    final Action action = listed.get(shown);
    if (action == null) {
      return "";
    }
    final Game game = project.game();
    return project
        .texts()
        .value(action.textKind(), ActionsXml.textKey(game, action) + ".BUTTON")
        .orElse(action.isPolitical() ? POLITICAL_LABEL : USER_LABEL);
  }

  private void choose(final String shown) {
    commit();
    selected = listed.get(shown);
    showInspector();
  }

  /** Asks for the kind, nation and name of a new action; it and its button text are one step. */
  private void addAction() {
    final Game game = project.game();
    final List<String> nations = MapXml.nations(game);
    final List<String> types = RulesXml.relationshipTypes(game);
    if (nations.isEmpty()) {
      warn("Add nations first.", "Add action");
      return;
    }
    final JComboBox<String> kind = new JComboBox<>(new String[] {POLITICAL_LABEL, USER_LABEL});
    final JComboBox<String> nation = new JComboBox<>(nations.toArray(String[]::new));
    final JTextField title = new JTextField(24);
    final JComboBox<String> other = new JComboBox<>(nations.toArray(String[]::new));
    final JComboBox<String> type = new JComboBox<>(types.toArray(String[]::new));
    final JPanel ask = new JPanel(new GridLayout(0, 1, 0, 4));
    ask.add(kind);
    ask.add(new JLabel("Of the nation"));
    ask.add(nation);
    ask.add(new JLabel("Its button, eg Declare war on Russia"));
    ask.add(title);
    ask.add(new JLabel("A political action changes how it stands with"));
    ask.add(other);
    ask.add(new JLabel("to"));
    ask.add(type);
    if (!Panels.ask(page, ask, "Add action") || title.getText().isBlank()) {
      return;
    }
    final boolean political = kind.getSelectedIndex() == 0;
    final String owner = (String) nation.getSelectedItem();
    if (political && (types.isEmpty() || owner.equals(other.getSelectedItem()))) {
      warn(
          "A political action changes how its nation stands with another one: choose another"
              + " nation and a relationship type (Politics).",
          "Add action");
      return;
    }
    final String javaKind = political ? ActionsXml.POLITICAL : ActionsXml.USER;
    final String name = ActionsXml.newName(game, javaKind, title.getText());
    final String key = name.substring(name.indexOf('_') + 1);
    final List<String> changes =
        political
            ? List.of(owner + ":" + other.getSelectedItem() + ":" + type.getSelectedItem())
            : List.of();
    final Action action = new Action(name, owner);
    final String button = key + ".BUTTON";
    project.edit(
        g -> ActionsXml.withAction(g, javaKind, name, owner, key, changes),
        texts ->
            texts.value(action.textKind(), button).isPresent()
                ? texts
                : texts.with(action.textKind(), button, title.getText().strip()));
    selected = action;
    showInspector();
    list.choose(shortName(action) + " (" + owner + ")");
  }

  private void showInspector() {
    pendingTexts.clear();
    inspector.clear();
    final Game game = project.game();
    if (selected == null || !ActionsXml.actions(game).contains(selected)) {
      selected = null;
      final int count = ActionsXml.actions(game).size();
      inspector
          .note(
              count == 0
                  ? "No actions yet. Add one with Add action."
                  : count + " actions in this game.")
          .end();
      return;
    }
    final Action action = selected;
    inspector.title(
        shortName(action),
        (action.isPolitical() ? POLITICAL_LABEL : USER_LABEL)
            + " of "
            + action.nation()
            + ", named "
            + action.name()
            + " in the game.");
    final JButton remove = new JButton("Remove action");
    remove.addActionListener(e -> removeAction(action));
    final JPanel removeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    removeRow.add(remove);
    inspector.row(removeRow);
    showTexts(action);
    showConditions(action);
    if (action.isPolitical()) {
      showRelationshipChanges(action);
    } else {
      showTriggers(action);
    }
    showCostAndChance(action);
    inspector.end();
  }

  private void showTexts(final Action action) {
    final Game game = project.game();
    final MapTexts.Kind kind = action.textKind();
    inspector.section(
        "Texts",
        "Kept in " + kind.getFile() + " by the text key and the part, eg Key.BUTTON. Actions of"
            + " several nations may share a key and so their texts.");
    final String key = ActionsXml.textKey(game, action);
    final JTextField keyField = new JTextField(key);
    commitOnLeave(
        keyField,
        () -> {
          final String typed = keyField.getText().strip();
          // the game refuses an action without a text key
          if (!typed.isEmpty() && !typed.equals(ActionsXml.textKey(project.game(), action))) {
            project.edit(g -> ActionsXml.withValues(g, action, ActionsXml.TEXT, List.of(typed)));
          }
        });
    inspector.field("Text key", keyField);
    if (project.texts().unreadable().contains(kind)) {
      inspector.note(kind.getFile() + " does not read: fix it in Files.");
      return;
    }
    for (final String part : action.textParts()) {
      final String entry = key + "." + part;
      final JTextField field =
          new JTextField(project.texts().value(kind, entry).orElse(""));
      commitOnLeave(
          field,
          () -> {
            final String typed = field.getText();
            if (!typed.equals(project.texts().value(kind, entry).orElse(""))) {
              editingHere = true;
              try {
                project.setText(kind, entry, typed);
              } finally {
                editingHere = false;
              }
            }
          });
      inspector.field(TEXT_LABELS.getOrDefault(part, part), field);
    }
  }

  /** Keeps the typed text when Enter is pressed, the field is left or another tool is chosen. */
  private void commitOnLeave(final JTextField field, final Runnable keep) {
    field.addActionListener(e -> keep.run());
    field.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            keep.run();
          }
        });
    pendingTexts.add(keep);
  }

  private void showConditions(final Action action) {
    final Game game = project.game();
    inspector.section(
        "When it can be used",
        "The conditions that must be met, all of them unless the action's conditionType says"
            + " otherwise. Only conditions declared before the action can be named.");
    final List<String> conditions = ActionsXml.names(game, action, ActionsXml.CONDITIONS);
    rows(
        conditions,
        Function.identity(),
        0,
        i -> {
          final List<String> rest = new ArrayList<>(conditions);
          rest.remove(i);
          project.edit(g -> ActionsXml.withNames(g, action, ActionsXml.CONDITIONS, rest));
        });
    if (conditions.isEmpty()) {
      inspector.note("Always, while it has attempts left.");
    }
    final List<String> offered =
        ActionsXml.conditionsBefore(game, action).stream()
            .filter(c -> !conditions.contains(c))
            .toList();
    if (!offered.isEmpty()) {
      final JComboBox<String> pick = new JComboBox<>(offered.toArray(String[]::new));
      addRow(
          List.of(pick),
          () -> {
            final List<String> more = new ArrayList<>(conditions);
            more.add((String) pick.getSelectedItem());
            project.edit(g -> ActionsXml.withNames(g, action, ActionsXml.CONDITIONS, more));
          });
    }
  }

  private void showRelationshipChanges(final Action action) {
    final Game game = project.game();
    inspector.section(
        "What it does",
        "How nations stand to each other when it succeeds: each line a nation, another and the"
            + " relationship type they are in then. It needs one at least.");
    final List<String> changes = ActionsXml.values(game, action, ActionsXml.RELATIONSHIP_CHANGE);
    rows(
        changes,
        c -> c.replaceFirst(":", " and ").replaceFirst(":", ": "),
        1,
        i -> {
          final List<String> rest = new ArrayList<>(changes);
          rest.remove(i);
          project.edit(
              g -> ActionsXml.withValues(g, action, ActionsXml.RELATIONSHIP_CHANGE, rest));
        });
    final String[] nations = MapXml.nations(game).toArray(String[]::new);
    final JComboBox<String> first = new JComboBox<>(nations);
    first.setSelectedItem(action.nation());
    final JComboBox<String> second = new JComboBox<>(nations);
    MapXml.nations(game).stream()
        .filter(n -> !n.equals(action.nation()))
        .findFirst()
        .ifPresent(second::setSelectedItem);
    final JComboBox<String> type =
        new JComboBox<>(RulesXml.relationshipTypes(game).toArray(String[]::new));
    addRow(
        List.of(first, second, type),
        () -> {
          if (first.getSelectedItem() == null
              || first.getSelectedItem().equals(second.getSelectedItem())
              || type.getSelectedItem() == null) {
            return;
          }
          final List<String> more = new ArrayList<>(changes);
          more.add(
              first.getSelectedItem()
                  + ":"
                  + second.getSelectedItem()
                  + ":"
                  + type.getSelectedItem());
          project.edit(
              g -> ActionsXml.withValues(g, action, ActionsXml.RELATIONSHIP_CHANGE, more));
        });
  }

  private void showTriggers(final Action action) {
    final Game game = project.game();
    inspector.section(
        "What it does",
        "The triggers it fires when it succeeds. Only triggers declared before the action can be"
            + " named; a new one fires once, without testing the trigger's conditions.");
    final List<String> fired = ActionsXml.values(game, action, ActionsXml.ACTIVATE_TRIGGER);
    rows(
        fired,
        f -> f.split(":")[0],
        0,
        i -> {
          final List<String> rest = new ArrayList<>(fired);
          rest.remove(i);
          project.edit(g -> ActionsXml.withValues(g, action, ActionsXml.ACTIVATE_TRIGGER, rest));
        });
    final List<String> triggers = ActionsXml.triggersBefore(game, action);
    if (triggers.isEmpty()) {
      inspector.note("No triggers before it yet: add them in Attachments.");
      return;
    }
    final JComboBox<String> pick = new JComboBox<>(triggers.toArray(String[]::new));
    addRow(
        List.of(pick),
        () -> {
          final List<String> more = new ArrayList<>(fired);
          more.add(pick.getSelectedItem() + FIRE_ONCE);
          project.edit(g -> ActionsXml.withValues(g, action, ActionsXml.ACTIVATE_TRIGGER, more));
        });
  }

  private void showCostAndChance(final Action action) {
    final Game game = project.game();
    inspector.section(
        "Cost and chance",
        "What an attempt costs, how likely it succeeds, how often a turn it may be tried, and"
            + " which nations must accept it before it happens.");
    final List<String> costs = ActionsXml.values(game, action, ActionsXml.COST_RESOURCES);
    rows(
        costs,
        c -> c.replace(":", " "),
        0,
        i -> {
          final List<String> rest = new ArrayList<>(costs);
          rest.remove(i);
          project.edit(g -> ActionsXml.withValues(g, action, ActionsXml.COST_RESOURCES, rest));
        });
    ActionsXml.value(game, action, "costPU")
        .ifPresent(pus -> inspector.note("Also costs " + pus + " PUs (costPU)."));
    final List<String> resources =
        Optional.ofNullable(game.getResourceList())
            .map(ResourceList::getResources)
            .orElse(List.of())
            .stream()
            .map(Resource::getName)
            .toList();
    if (!resources.isEmpty()) {
      final JSpinner amount = Form.newNumber(1, 9999, n -> {});
      final JComboBox<String> resource = new JComboBox<>(resources.toArray(String[]::new));
      resource.setSelectedItem(MapXml.PUS);
      addRow(
          List.of(amount, resource),
          () -> {
            final List<String> more = new ArrayList<>(costs);
            more.add(amount.getValue() + ":" + resource.getSelectedItem());
            project.edit(
                g -> ActionsXml.withValues(g, action, ActionsXml.COST_RESOURCES, more));
          });
    }
    final String[] chance =
        ActionsXml.value(game, action, ActionsXml.CHANCE).orElse("1:1").split(":");
    final int[] odds = {parse(chance[0], 1), parse(chance.length > 1 ? chance[1] : "1", 1)};
    final IntConsumer setChance =
        ignored ->
            project.edit(
                g ->
                    ActionsXml.withValues(
                        g,
                        action,
                        ActionsXml.CHANCE,
                        odds[0] == odds[1] ? List.of() : List.of(odds[0] + ":" + odds[1])));
    final JSpinner hits = Form.newNumber(odds[0], 0, 120, n -> {});
    // a roll above the dice is shown as what is saved: the dice
    hits.addChangeListener(
        e -> {
          final int n = (Integer) hits.getValue();
          if (n > odds[1]) {
            hits.setValue(odds[1]);
            return;
          }
          odds[0] = n;
          numberEdit(setChance, n);
        });
    final JSpinner of =
        Form.newNumber(
            odds[1],
            1,
            120,
            n -> {
              odds[1] = n;
              if (odds[0] > n) {
                hits.setValue(n);
              } else {
                numberEdit(setChance, n);
              }
            });
    final JPanel chanceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    chanceRow.add(hits);
    chanceRow.add(new JLabel("of"));
    chanceRow.add(of);
    inspector.field("Succeeds on a roll of", chanceRow);
    final int attempts =
        parse(ActionsXml.value(game, action, ActionsXml.ATTEMPTS_PER_TURN).orElse("1"), 1);
    inspector.field(
        "Attempts per turn",
        Form.newNumber(
            attempts,
            99,
            n ->
                numberEdit(
                    ignored ->
                        project.edit(
                            g ->
                                ActionsXml.withValues(
                                    g,
                                    action,
                                    ActionsXml.ATTEMPTS_PER_TURN,
                                    n == 1 ? List.of() : List.of(String.valueOf(n)))),
                    n)));
    final List<String> accept = ActionsXml.names(game, action, ActionsXml.ACTION_ACCEPT);
    final JButton acceptButton =
        new JButton(accept.isEmpty() ? "Nobody" : String.join(", ", accept));
    acceptButton.addActionListener(
        e ->
            NationsEditor.chooseNations(
                    page,
                    "Must accept it",
                    MapXml.nations(project.game()).stream()
                        .filter(n -> !n.equals(action.nation()))
                        .toList(),
                    accept)
                .ifPresent(
                    names ->
                        project.edit(
                            g ->
                                ActionsXml.withNames(
                                    g, action, ActionsXml.ACTION_ACCEPT, names))));
    inspector.field("Must be accepted by", acceptButton);
  }

  /** A spinner's change, as one step, without building the form again under the spinner. */
  private void numberEdit(final IntConsumer edit, final int value) {
    editingHere = true;
    try {
      edit.accept(value);
    } finally {
      editingHere = false;
    }
  }

  private static int parse(final String value, final int otherwise) {
    try {
      return Integer.parseInt(value.strip());
    } catch (final NumberFormatException e) {
      return otherwise;
    }
  }

  /** The values as lines, each with a button to take it out; the last few may have to stay. */
  private void rows(
      final List<String> values,
      final Function<String, String> shown,
      final int keep,
      final IntConsumer remove) {
    for (int i = 0; i < values.size(); i++) {
      final int index = i;
      final JPanel row = new JPanel(new BorderLayout(8, 0));
      row.add(new JLabel(shown.apply(values.get(i))), BorderLayout.CENTER);
      final JButton removeButton = new JButton("Remove");
      removeButton.setEnabled(values.size() > keep);
      removeButton.addActionListener(e -> remove.accept(index));
      row.add(removeButton, BorderLayout.EAST);
      inspector.row(row);
    }
  }

  /** The fields of a new value and its Add button. */
  private void addRow(final List<JComponent> fields, final Runnable add) {
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    fields.forEach(row::add);
    final JButton addButton = new JButton("Add");
    addButton.addActionListener(e -> add.run());
    row.add(addButton);
    inspector.row(row);
  }

  private void removeAction(final Action action) {
    final List<String> named = RulesXml.whereNamed(project.game(), action.name());
    if (!named.isEmpty()) {
      warn(
          action.name()
              + " stays: the game would not load without it, as these still name it:\n"
              + String.join("\n", named.subList(0, Math.min(10, named.size())))
              + "\nChange them in Attachments first.",
          "Remove action");
      return;
    }
    final int answer =
        JOptionPane.showConfirmDialog(
            page,
            "Remove " + shortName(action) + " of " + action.nation() + "? Its texts stay in "
                + action.textKind().getFile() + ".\nUndo takes it back.",
            "Remove action",
            JOptionPane.OK_CANCEL_OPTION);
    if (answer == JOptionPane.OK_OPTION) {
      project.edit(g -> ActionsXml.withoutAction(g, action));
    }
  }

  private void warn(final String message, final String title) {
    JOptionPane.showMessageDialog(page, message, title, JOptionPane.WARNING_MESSAGE);
  }
}
