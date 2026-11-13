package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import games.strategy.triplea.Constants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;
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
import javax.swing.JSpinner;
import javax.swing.JTextField;
import org.triplea.map.data.elements.AttachmentList.Attachment;
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
  private static final String EMPTY = "empty";
  private static final String LIST = "list";
  // TripleA's politics and user actions delegates skip a text that is just this
  static final String NONE = "NONE";
  // the parts shown at once; the others go under "More texts"
  private static final List<String> MAIN_TEXTS =
      List.of("BUTTON", "DESCRIPTION", "NOTIFICATION_SUCCESS", "OTHER_NOTIFICATION_SUCCESS");
  // fires the trigger once, without testing its uses, conditions or chance
  private static final String FIRE_ONCE = ":1:false:false:false:false";

  private final MapProject project;
  private final JPanel page = new JPanel(new CardLayout());
  private final JComboBox<String> nationFilter = new JComboBox<>();
  private final SearchList list;
  private final JPanel details = new JPanel(new CardLayout());
  private final Form inspector = new Form(320);
  private final List<Runnable> pendingTexts = new ArrayList<>();
  private Map<String, Action> listed = Map.of();
  private @Nullable Action selected;
  private boolean editingHere;
  private boolean filling;

  ActionsEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList("action", "actions", this::listedNames, this::rowTitle, this::rowDetail,
                this::choose)
            .withAdd("Add action…", "Add an action", this::addAction);
    nationFilter.setRenderer(new NationRenderer());
    nationFilter.addActionListener(
        e -> {
          if (!filling) {
            list.refresh();
          }
        });
    final JPanel listColumn = new JPanel(new BorderLayout(0, Theme.GAP_S));
    listColumn.add(nationFilter, BorderLayout.NORTH);
    listColumn.add(list, BorderLayout.CENTER);
    details.add(
        Panels.empty(
            Glyph.ACTIONS,
            "Choose an action",
            "Choose an action to change its button, conditions and cost."),
        EMPTY);
    details.add(SwingComponents.newJScrollPane(inspector), LIST);
    page.add(
        Panels.empty(
            Glyph.ACTIONS,
            "No actions yet",
            "An action is a button a nation may press in its turn, eg to declare war.",
            "Add action…",
            this::addAction),
        EMPTY);
    page.add(Panels.sideBySide(listColumn, details), LIST);
    project.onChange(
        () -> {
          fillNationFilter();
          list.refresh();
          if (!editingHere) {
            showInspector();
          }
        });
    fillNationFilter();
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
        + " a chance. A nation uses them only with a politics or user actions step in Turn order."
        + " Only conditions and triggers declared before an action can be named by it.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    List.copyOf(pendingTexts).forEach(Runnable::run);
  }

  /** What a field shows of a stored text: NONE, which the game skips, as empty. */
  static String shown(final String stored) {
    return NONE.equals(stored) ? "" : stored;
  }

  /** What to store of a typed text: an empty message as NONE, as TripleA expects. */
  static String toStore(final String part, final String typed) {
    return typed.isEmpty() && isMessage(part) ? NONE : typed;
  }

  private static boolean isMessage(final String part) {
    return part.contains("NOTIFICATION") || "ACCEPT_QUESTION".equals(part);
  }

  /** The first nation that is not the given one, eg to change a relationship with. */
  static Optional<String> otherNation(final List<String> nations, final String nation) {
    return nations.stream().filter(n -> !n.equals(nation)).findFirst();
  }

  /** A war type when the button speaks of war, else the first type; "" without types. */
  static String defaultType(final Game game, final String button) {
    return button.toLowerCase(Locale.ROOT).contains("war")
        ? TriggerXml.warType(game)
        : RulesXml.relationshipTypes(game).stream().findFirst().orElse("");
  }

  /** What a button that sets the relationship might say, eg "Ally with Russians". */
  static String buttonExample(final Game game, final String type, final String other) {
    final String archetype = type.isEmpty() ? "" : RulesXml.archetype(game, type);
    if (Constants.RELATIONSHIP_ARCHETYPE_ALLIED.equalsIgnoreCase(archetype)) {
      return "Ally with " + other;
    }
    return Constants.RELATIONSHIP_ARCHETYPE_NEUTRAL.equalsIgnoreCase(archetype)
        ? "Make peace with " + other
        : "Declare war on " + other;
  }

  // ---- the list

  private void fillNationFilter() {
    final Object chosen = nationFilter.getSelectedItem();
    final List<Action> actions = ActionsXml.actions(project.game());
    filling = true;
    try {
      nationFilter.removeAllItems();
      nationFilter.addItem("");
      MapXml.nations(project.game()).stream()
          .filter(n -> actions.stream().anyMatch(a -> a.nation().equals(n)))
          .forEach(nationFilter::addItem);
      nationFilter.setSelectedItem(chosen == null ? "" : chosen);
    } finally {
      filling = false;
    }
    ((CardLayout) page.getLayout()).show(page, actions.isEmpty() ? EMPTY : LIST);
  }

  private String nation() {
    return Optional.ofNullable((String) nationFilter.getSelectedItem()).orElse("");
  }

  private List<String> listedNames() {
    final Map<String, Action> byName = new LinkedHashMap<>();
    ActionsXml.actions(project.game()).forEach(a -> byName.put(key(a), a));
    listed = byName;
    final String nation = nation();
    return byName.entrySet().stream()
        .filter(e -> nation.isEmpty() || nation.equals(e.getValue().nation()))
        .map(Map.Entry::getKey)
        .toList();
  }

  private static String key(final Action action) {
    return shortName(action) + " (" + action.nation() + ")";
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

  private String rowTitle(final String key) {
    return Optional.ofNullable(listed.get(key)).map(this::buttonText).orElse(key);
  }

  private String rowDetail(final String key) {
    return Optional.ofNullable(listed.get(key))
        .map(a -> a.nation() + " · " + shortName(a).replace('_', ' '))
        .orElse("");
  }

  /** What the player sees on the button, else the action's short name. */
  private String buttonText(final Action action) {
    return project
        .texts()
        .value(action.textKind(), ActionsXml.textKey(project.game(), action) + ".BUTTON")
        .map(String::strip)
        .filter(b -> !b.isEmpty() && !NONE.equals(b))
        .orElse(shortName(action).replace('_', ' '));
  }

  private void choose(final String key) {
    commit();
    selected = listed.get(key);
    showInspector();
  }

  // ---- adding

  /** Asks for the kind, nation and button of a new action; it and its button text are one step. */
  private void addAction() {
    final Game game = project.game();
    final List<String> nations = MapXml.nations(game);
    final List<String> types = RulesXml.relationshipTypes(game);
    if (nations.isEmpty()) {
      Dialogs.warn(page, "Add action", "Add nations first.");
      return;
    }
    final JRadioButton political = new JRadioButton("Political: changes relationships", true);
    final JRadioButton user = new JRadioButton("User action: fires triggers");
    final ButtonGroup kinds = new ButtonGroup();
    final JPanel kind = new JPanel();
    kind.setLayout(new BoxLayout(kind, BoxLayout.Y_AXIS));
    kind.setOpaque(false);
    for (final JRadioButton radio : List.of(political, user)) {
      kinds.add(radio);
      radio.setAlignmentX(Component.LEFT_ALIGNMENT);
      kind.add(radio);
    }
    final String firstNation = nation().isEmpty() ? nations.getFirst() : nation();
    final JComboBox<String> nation = new JComboBox<>(nations.toArray(String[]::new));
    nation.setSelectedItem(firstNation);
    final JTextField button = new JTextField(24);
    final JComboBox<String> other = new JComboBox<>(nations.toArray(String[]::new));
    otherNation(nations, firstNation).ifPresent(other::setSelectedItem);
    final JComboBox<String> type = new JComboBox<>(types.toArray(String[]::new));
    type.setSelectedItem(defaultType(game, ""));
    // the example on the button follows the relationship and the other nation chosen
    final Runnable example =
        () ->
            button.putClientProperty(
                FlatClientProperties.PLACEHOLDER_TEXT,
                buttonExample(
                    game,
                    Objects.toString(type.getSelectedItem(), ""),
                    Objects.toString(other.getSelectedItem(), "")));
    example.run();
    type.addActionListener(e -> example.run());
    other.addActionListener(e -> example.run());
    // the type follows the button's words until the map maker picks one
    final boolean[] typePicked = {false};
    final boolean[] setting = {false};
    type.addActionListener(e -> typePicked[0] |= !setting[0]);
    OnTyping.attach(
        button,
        () -> {
          if (!typePicked[0]) {
            setting[0] = true;
            type.setSelectedItem(defaultType(game, button.getText()));
            setting[0] = false;
          }
        });
    nation.addActionListener(
        e -> {
          final String chosen = (String) nation.getSelectedItem();
          if (chosen != null && chosen.equals(other.getSelectedItem())) {
            otherNation(nations, chosen).ifPresent(other::setSelectedItem);
          }
        });
    final Form politicalPart = new Form(320);
    politicalPart.setBorder(BorderFactory.createEmptyBorder());
    politicalPart.setOpaque(false);
    politicalPart.field("Changes its relationship with", other).field("to", type);
    final Form ask = new Form(320);
    ask.setBorder(BorderFactory.createEmptyBorder());
    ask.setOpaque(false);
    ask.field("Kind", kind).field("Nation", nation).field("Button", button).row(politicalPart);
    political.addActionListener(e -> politicalPart.setVisible(true));
    user.addActionListener(e -> politicalPart.setVisible(false));
    if (!Dialogs.ask(
        page,
        "Add action",
        ask,
        "Add action",
        () ->
            !button.getText().isBlank()
                && (user.isSelected()
                    || !types.isEmpty()
                        && type.getSelectedItem() != null
                        && !nation.getSelectedItem().equals(other.getSelectedItem())))) {
      return;
    }
    final boolean isPolitical = political.isSelected();
    final String owner = (String) nation.getSelectedItem();
    final String javaKind = isPolitical ? ActionsXml.POLITICAL : ActionsXml.USER;
    final String title = button.getText().strip();
    final String name = ActionsXml.newName(game, javaKind, title);
    final String key = name.substring(name.indexOf('_') + 1);
    final List<String> changes =
        isPolitical
            ? List.of(owner + ":" + other.getSelectedItem() + ":" + type.getSelectedItem())
            : List.of();
    final Action action = new Action(name, owner);
    final String buttonKey = key + ".BUTTON";
    project.edit(
        g -> ActionsXml.withAction(g, javaKind, name, owner, key, changes),
        texts ->
            texts.value(action.textKind(), buttonKey).isPresent()
                ? texts
                : texts.with(action.textKind(), buttonKey, title));
    selected = action;
    nationFilter.setSelectedItem("");
    showInspector();
    list.choose(key(action));
  }

  // ---- the details

  private void showInspector() {
    pendingTexts.clear();
    inspector.clear();
    final Game game = project.game();
    if (selected == null || !ActionsXml.actions(game).contains(selected)) {
      selected = null;
      inspector.end();
      ((CardLayout) details.getLayout()).show(details, EMPTY);
      return;
    }
    final Action action = selected;
    inspector
        .title(buttonText(action))
        .subtitle(
            (action.isPolitical() ? "Political action of " : "User action of ")
                + action.nation()
                + " · "
                + action.name());
    final Form sections = new Form(320);
    sections.setBorder(BorderFactory.createEmptyBorder());
    sections.setOpaque(false);
    sections.columns();
    showTexts(sections, action);
    showConditions(sections, action);
    if (action.isPolitical()) {
      showRelationshipChanges(sections, action);
    } else {
      showTriggers(sections, action);
    }
    showCostAndChance(sections, action);
    sections.end();
    inspector.row(sections);
    inspector.remove("Remove action", () -> removeAction(action));
    inspector.end();
    ((CardLayout) details.getLayout()).show(details, LIST);
  }

  private void showTexts(final Form form, final Action action) {
    final Game game = project.game();
    final MapTexts.Kind kind = action.textKind();
    form.section("Button and texts");
    if (project.texts().unreadable().contains(kind)) {
      form.note(kind.getFile() + " does not read: fix it in Files.");
      return;
    }
    final String key = ActionsXml.textKey(game, action);
    final List<String> more =
        action.textParts().stream().filter(p -> !MAIN_TEXTS.contains(p)).toList();
    MAIN_TEXTS.forEach(part -> textField(form, action, key, part));
    form.more(
        "action-texts",
        "More texts (" + (more.size() + 1) + ")",
        f -> {
          more.forEach(part -> textField(f, action, key, part));
          final JTextField keyField = new JTextField(key);
          commitOnLeave(
              keyField,
              () -> {
                final String typed = keyField.getText().strip();
                // the game refuses an action without a text key
                if (!typed.isEmpty()
                    && !typed.equals(ActionsXml.textKey(project.game(), action))) {
                  project.edit(
                      g -> ActionsXml.withValues(g, action, ActionsXml.TEXT, List.of(typed)));
                }
              });
          f.field("Text key", keyField)
              .note("Texts are kept in " + kind.getFile() + " by this key, eg " + key
                  + ".BUTTON. Actions may share a key and so their texts.");
        });
  }

  private void textField(final Form form, final Action action, final String key,
      final String part) {
    final MapTexts.Kind kind = action.textKind();
    final String entry = key + "." + part;
    final JTextField field =
        new JTextField(shown(project.texts().value(kind, entry).orElse("")));
    if (isMessage(part)) {
      field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "No message");
    }
    commitOnLeave(
        field,
        () -> {
          final String typed = field.getText();
          // unchanged as shown: a NONE stays NONE, a missing entry stays missing
          if (!typed.equals(shown(project.texts().value(kind, entry).orElse("")))) {
            editingHere = true;
            try {
              project.setText(kind, entry, toStore(part, typed));
            } finally {
              editingHere = false;
            }
          }
        });
    form.field(textLabel(part, action.nation()), field);
  }

  private static String textLabel(final String part, final String nation) {
    return switch (part) {
      case "BUTTON" -> "Button";
      case "DESCRIPTION" -> "Description";
      case "NOTIFICATION_SUCCESS" -> "Message to " + nation + " when it succeeds";
      case "OTHER_NOTIFICATION_SUCCESS" -> "Message to the others when it succeeds";
      case "NOTIFICATION_FAILURE" -> "Message to " + nation + " when it fails";
      case "TARGET_NOTIFICATION_SUCCESS" -> "Message to the target when it succeeds";
      case "TARGET_NOTIFICATION_FAILURE" -> "Message to the target when it fails";
      case "OTHER_NOTIFICATION_FAILURE" -> "Message to the others when it fails";
      case "ACCEPT_QUESTION" -> "Question to those who must accept";
      default -> part;
    };
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

  private void showConditions(final Form form, final Action action) {
    final Game game = project.game();
    form.section("When it can be used");
    final Map<String, Attachment> byName = new HashMap<>();
    TriggerXml.conditions(game).forEach(c -> byName.putIfAbsent(c.getName(), c));
    final Function<String, String> sentence =
        n -> Optional.ofNullable(byName.get(n)).map(TriggerXml::conditionSentence).orElse(n);
    final List<String> conditions = ActionsXml.names(game, action, ActionsXml.CONDITIONS);
    rows(
        form,
        conditions,
        sentence,
        "Remove condition",
        0,
        i -> {
          final List<String> rest = new ArrayList<>(conditions);
          rest.remove(i);
          project.edit(g -> ActionsXml.withNames(g, action, ActionsXml.CONDITIONS, rest));
        });
    if (conditions.isEmpty()) {
      form.note("Always, while it has attempts left.");
    }
    final List<String> offered =
        ActionsXml.conditionsBefore(game, action).stream()
            .filter(c -> !conditions.contains(c))
            .toList();
    if (!offered.isEmpty()) {
      form.row(
          leftAligned(
              Theme.newAddButton(
                  "Add condition…",
                  "Add a condition it needs",
                  () ->
                      Dialogs.pick(
                              page,
                              "Add condition",
                              "Conditions declared before this action:",
                              offered,
                              sentence,
                              "Add condition")
                          .ifPresent(
                              chosen -> {
                                final List<String> more = new ArrayList<>(conditions);
                                more.add(chosen);
                                project.edit(
                                    g ->
                                        ActionsXml.withNames(
                                            g, action, ActionsXml.CONDITIONS, more));
                              }))));
    }
  }

  private void showRelationshipChanges(final Form form, final Action action) {
    final Game game = project.game();
    form.section("What it does");
    final List<String> changes = ActionsXml.values(game, action, ActionsXml.RELATIONSHIP_CHANGE);
    // a political action needs one change at least to load
    rows(
        form,
        changes,
        ActionsEditor::changeText,
        "Remove change",
        1,
        i -> {
          final List<String> rest = new ArrayList<>(changes);
          rest.remove(i);
          project.edit(
              g -> ActionsXml.withValues(g, action, ActionsXml.RELATIONSHIP_CHANGE, rest));
        });
    final List<String> nationNames = MapXml.nations(game);
    final String[] nations = nationNames.toArray(String[]::new);
    final JComboBox<String> first = new JComboBox<>(nations);
    first.setSelectedItem(action.nation());
    final JComboBox<String> second = new JComboBox<>(nations);
    otherNation(nationNames, action.nation()).ifPresent(second::setSelectedItem);
    first.addActionListener(
        e -> {
          final String chosen = (String) first.getSelectedItem();
          if (chosen != null && chosen.equals(second.getSelectedItem())) {
            otherNation(nationNames, chosen).ifPresent(second::setSelectedItem);
          }
        });
    final JComboBox<String> type =
        new JComboBox<>(RulesXml.relationshipTypes(game).toArray(String[]::new));
    type.setSelectedItem(defaultType(game, buttonText(action)));
    // the button on a line of its own: the three combo boxes fill a column
    form.row(leftAligned(first, new JLabel("and"), second, new JLabel("→"), type));
    form.row(leftAligned(
        Theme.newAddButton(
            "Add change",
            "Add how these two stand when it succeeds",
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
            })));
  }

  /** "Germans and Russians → War" for Germans:Russians:War. */
  private static String changeText(final String change) {
    final String[] parts = change.split(":", 3);
    return parts.length < 3 ? change : parts[0] + " and " + parts[1] + " → " + parts[2];
  }

  private void showTriggers(final Form form, final Action action) {
    final Game game = project.game();
    form.section("What it does");
    final List<String> fired = ActionsXml.values(game, action, ActionsXml.ACTIVATE_TRIGGER);
    rows(
        form,
        fired,
        f -> TriggerXml.shortName(f.split(":")[0]).replace('_', ' '),
        "Remove trigger",
        0,
        i -> {
          final List<String> rest = new ArrayList<>(fired);
          rest.remove(i);
          project.edit(g -> ActionsXml.withValues(g, action, ActionsXml.ACTIVATE_TRIGGER, rest));
        });
    final List<String> triggers = ActionsXml.triggersBefore(game, action);
    if (triggers.isEmpty()) {
      form.note("No triggers before this action yet. Make one in Triggers, then come back.");
      return;
    }
    form.row(
        leftAligned(
            Theme.newAddButton(
                "Add trigger…",
                "Fire a trigger when it succeeds, once, without testing the trigger's conditions",
                () ->
                    Dialogs.pick(
                            page,
                            "Add trigger",
                            "Triggers declared before this action:",
                            triggers,
                            t -> TriggerXml.shortName(t).replace('_', ' '),
                            "Add trigger")
                        .ifPresent(
                            chosen -> {
                              final List<String> more = new ArrayList<>(fired);
                              more.add(chosen + FIRE_ONCE);
                              project.edit(
                                  g ->
                                      ActionsXml.withValues(
                                          g, action, ActionsXml.ACTIVATE_TRIGGER, more));
                            }))));
  }

  private void showCostAndChance(final Form form, final Action action) {
    final Game game = project.game();
    form.section("Cost and chance");
    final List<String> costs = ActionsXml.values(game, action, ActionsXml.COST_RESOURCES);
    form.row(new JLabel("Costs"));
    rows(
        form,
        costs,
        c -> c.replace(":", " "),
        "Remove cost",
        0,
        i -> {
          final List<String> rest = new ArrayList<>(costs);
          rest.remove(i);
          project.edit(g -> ActionsXml.withValues(g, action, ActionsXml.COST_RESOURCES, rest));
        });
    ActionsXml.value(game, action, "costPU")
        .ifPresent(pus -> form.note("Also costs " + pus + " PUs (costPU)."));
    final List<String> resources =
        Optional.ofNullable(game.getResourceList())
            .map(ResourceList::getResources)
            .orElse(List.of())
            .stream()
            .map(Resource::getName)
            .toList();
    if (!resources.isEmpty()) {
      final JSpinner amount = Form.newNumber(1, 1, 9999, n -> {});
      final JComboBox<String> resource = new JComboBox<>(resources.toArray(String[]::new));
      resource.setSelectedItem(MapXml.PUS);
      form.row(
          leftAligned(
              amount,
              resource,
              Theme.newAddButton(
                  "Add cost",
                  "Add what an attempt costs",
                  () -> {
                    final List<String> more = new ArrayList<>(costs);
                    more.add(amount.getValue() + ":" + resource.getSelectedItem());
                    project.edit(
                        g -> ActionsXml.withValues(g, action, ActionsXml.COST_RESOURCES, more));
                  })));
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
    form.field("Succeeds on a roll of", leftAligned(hits, new JLabel("of"), of));
    final int attempts =
        parse(ActionsXml.value(game, action, ActionsXml.ATTEMPTS_PER_TURN).orElse("1"), 1);
    // label above, as the costs' and the chance's: one column of numbers
    form.field(
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
            Dialogs.pickMany(
                    page,
                    "Must accept it",
                    MapXml.nations(project.game()).stream()
                        .filter(n -> !n.equals(action.nation()))
                        .toList(),
                    accept,
                    "Save")
                .ifPresent(
                    names ->
                        project.edit(
                            g ->
                                ActionsXml.withNames(
                                    g, action, ActionsXml.ACTION_ACCEPT, names))));
    form.field("Must be accepted by", leftAligned(acceptButton));
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

  /** The values as lines, each with a × that takes it out; the last few may have to stay. */
  private static void rows(
      final Form form,
      final List<String> values,
      final Function<String, String> shown,
      final String removeTip,
      final int keep,
      final IntConsumer remove) {
    for (int i = 0; i < values.size(); i++) {
      final int index = i;
      final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
      row.setOpaque(false);
      final JLabel label = new JLabel(shown.apply(values.get(i)));
      label.setToolTipText(values.get(i));
      row.add(label, BorderLayout.CENTER);
      final JButton removeButton = Theme.newIconButton("×", removeTip, () -> remove.accept(index));
      removeButton.setEnabled(values.size() > keep);
      row.add(removeButton, BorderLayout.EAST);
      form.row(row);
    }
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

  private void removeAction(final Action action) {
    final List<String> named = RulesXml.whereNamed(project.game(), action.name());
    if (!named.isEmpty()) {
      Dialogs.refuseNamed(
          page,
          "Remove action",
          action.name()
              + " stays: the game would not load without it, as these still name it:\n"
              + String.join("\n", named.subList(0, Math.min(10, named.size())))
              + "\nChange them in Attachments first.",
          named);
      return;
    }
    if (Dialogs.confirmRemove(
        page,
        "Remove action",
        "Remove “" + buttonText(action) + "” of " + action.nation() + "?\nIts texts stay in "
            + action.textKind().getFile() + ".",
        List.of(),
        "Remove")) {
      project.edit(g -> ActionsXml.withoutAction(g, action));
    }
  }

  /** "All nations (n)", else "Germans (n)". */
  private final class NationRenderer extends DefaultListCellRenderer {
    @Serial private static final long serialVersionUID = 1L;

    @Override
    public Component getListCellRendererComponent(
        final JList<?> owner,
        final Object value,
        final int index,
        final boolean isSelected,
        final boolean cellHasFocus) {
      final String nation = Optional.ofNullable((String) value).orElse("");
      final List<Action> actions = ActionsXml.actions(project.game());
      final long count =
          nation.isEmpty()
              ? actions.size()
              : actions.stream().filter(a -> a.nation().equals(nation)).count();
      final String text = (nation.isEmpty() ? "All nations" : nation) + " (" + count + ")";
      return super.getListCellRendererComponent(owner, text, index, isSelected, cellHasFocus);
    }
  }
}
