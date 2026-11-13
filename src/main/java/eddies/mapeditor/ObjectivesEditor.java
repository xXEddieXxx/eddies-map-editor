package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.ObjectiveXml.Objective;
import eddies.mapeditor.Theme.Status;
import eddies.mapeditor.TriggerAssistant.Checks;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import lombok.experimental.ExtensionMethod;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;

/**
 * The national objectives: what each nation gets PUs for at the end of its turn. A list by nation,
 * each with its text as the players read it; the chosen one's nation, PUs, text, checks (the
 * trigger assistant's fields) and how often it pays. Each change is a step.
 */
// toBuilder() also with TripleA versions whose model has none
@ExtensionMethod(Builders.class)
final class ObjectivesEditor implements Editor {
  static final String TITLE = "Objectives";
  private static final String SEPARATOR = " · ";
  private static final String EMPTY = "empty";
  private static final String LIST = "list";
  private static final String ADD = "Add objective…";
  private static final MapTexts.Kind TEXTS = MapTexts.Kind.OBJECTIVES;

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(0, Theme.GAP_S));
  private final JPanel cards = new JPanel(new CardLayout());
  private final JPanel off = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
  private final JComboBox<String> nationFilter = new JComboBox<>();
  private final SearchList list;
  private final JPanel details = new JPanel(new CardLayout());
  private final Form inspector = new Form(560);
  // the chosen one's title, id line and card, shown again after each change made here
  private final JPanel summary = new JPanel(new BorderLayout(0, Theme.GAP_XS));
  private final JLabel noText =
      Theme.newSecondaryLabel("Without a text the game's Objectives tab does not show it.");
  private final JLabel noChecks = Theme.newSecondaryLabel("No checks yet: it pays every round.");
  private final JLabel checkProblem = Theme.newStatusLabel(Status.PROBLEM, "");
  private final List<Runnable> pendingTexts = new ArrayList<>();
  private Listing listing;
  private @Nullable String selected;
  private @Nullable Checks checks;
  private boolean editingHere;
  private boolean filling;

  /**
   * The objectives by their key in the list, nation by nation in the game's order.
   *
   * @param game the game and
   * @param from the texts listed, to list again only after a change
   * @param texts each key's text in objectives.properties, if it has one
   * @param conditions the game's conditions by name, for the plain words
   * @param nations the nations that have objectives, the filter's choices after "All nations"
   */
  record Listing(
      Game game,
      MapTexts from,
      Map<String, Objective> objectives,
      Map<String, String> texts,
      Map<String, Attachment> conditions,
      List<String> nations) {}

  ObjectivesEditor(final MapProject project) {
    this.project = project;
    listing = listing(project.game(), project.texts());
    list =
        new SearchList(
                "objective", "objectives", this::shownKeys, this::rowTitle, this::rowLine,
                this::choose)
            .withAdd(ADD, "Add an objective", this::add);
    // under each nation's heading, which folds
    list.groupBy(
        Optional.of(
            key ->
                Optional.ofNullable(listing().objectives().get(key))
                    .map(Objective::nation)
                    .orElse("")));
    nationFilter.setToolTipText("Shows one nation's objectives");
    nationFilter.getAccessibleContext().setAccessibleName("Objectives of which nation");
    nationFilter.addActionListener(
        e -> {
          if (!filling) {
            list.refresh();
          }
        });
    off.setOpaque(false);
    off.add(
        Help.link(
            "National Objectives is off: no objective pays. Turn it on here, or in",
            "Game options"));
    off.add(Box.createHorizontalStrut(Theme.GAP_M));
    final JButton turnOn = new JButton("Turn on");
    turnOn.setToolTipText("Turns on the game option National Objectives");
    // the fields stay, as a check being filled in is only in them
    turnOn.addActionListener(e -> editHere(() -> project.edit(ObjectiveXml::withOn)));
    off.add(turnOn);
    final JPanel listColumn = new JPanel(new BorderLayout(0, Theme.GAP_S));
    listColumn.add(nationFilter, BorderLayout.NORTH);
    listColumn.add(list, BorderLayout.CENTER);
    details.add(
        Panels.empty(
            Glyph.OBJECTIVES,
            "Choose an objective",
            "Choose an objective to change what it pays, its text and its checks."),
        EMPTY);
    details.add(SwingComponents.newJScrollPane(inspector), LIST);
    cards.add(
        Panels.empty(
            Glyph.OBJECTIVES,
            "No objectives yet",
            "An objective gives a nation PUs at the end of its turn while something holds, eg"
                + " while it owns a territory.",
            ADD,
            this::add),
        EMPTY);
    cards.add(Panels.sideBySide(listColumn, details), LIST);
    page.add(off, BorderLayout.NORTH);
    page.add(cards, BorderLayout.CENTER);
    summary.setOpaque(false);
    project.onChange(this::refresh);
    refresh();
  }

  @Override
  public String title() {
    return TITLE;
  }

  @Override
  public Glyph glyph() {
    return Glyph.OBJECTIVES;
  }

  @Override
  public String hint() {
    return "National objectives: PUs a nation gets at the end of its turn while its checks hold,"
        + " eg while it owns certain territories. Each has a text the players read in the game's"
        + " Objectives tab. They pay only with the game option National Objectives.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  /**
   * Takes the typed text and the checks the game can read, eg before a save; a check not finished
   * stays in its fields with its red line, as the game could not load it.
   */
  @Override
  public void commit() {
    List.copyOf(pendingTexts).forEach(Runnable::run);
    // also a check typed within the pause that follows typing
    checksChanged();
  }

  /** Chooses the objective of this attachment name, eg from Attachments. */
  @Override
  public void show(final String name) {
    listing().objectives().entrySet().stream()
        .filter(e -> e.getValue().name().equals(name))
        .map(Map.Entry::getKey)
        .findFirst()
        .filter(key -> !key.equals(selected))
        .filter(key -> mayLeave())
        .ifPresent(
            key -> {
              nationFilter.setSelectedIndex(0);
              list.choose(key);
              selected = key;
              showInspector();
            });
  }

  // ---- the list

  private void refresh() {
    off.setVisible(!ObjectiveXml.isOn(project.game()));
    // read before the listing changes under the filter's index
    final String chosen = nation();
    final Listing now = listing();
    filling = true;
    final List<String> items = new ArrayList<>();
    items.add("All nations (" + now.objectives().size() + ")");
    now.nations().forEach(n -> items.add(n + " (" + count(now, n) + ")"));
    nationFilter.setModel(new DefaultComboBoxModel<>(items.toArray(String[]::new)));
    nationFilter.setSelectedIndex(now.nations().indexOf(chosen) + 1);
    filling = false;
    list.refresh();
    ((CardLayout) cards.getLayout()).show(cards, now.objectives().isEmpty() ? EMPTY : LIST);
    if (editingHere) {
      showSummary();
    } else {
      showInspector();
    }
  }

  private static long count(final Listing listing, final String nation) {
    return listing.objectives().values().stream().filter(o -> o.nation().equals(nation)).count();
  }

  /** The listing of the game as it is now, made once per change. */
  private Listing listing() {
    if (listing.game() != project.game() || listing.from() != project.texts()) {
      listing = listing(project.game(), project.texts());
    }
    return listing;
  }

  /**
   * The game's objectives by a key, nation by nation in the game's order of nations: the name and
   * the nation, as names may repeat; a key taken gets a number, so every objective is listed.
   */
  static Listing listing(final Game game, final MapTexts texts) {
    final List<Objective> all = ObjectiveXml.objectives(game);
    final List<String> nations = new ArrayList<>(MapXml.nations(game));
    all.stream().map(Objective::nation).filter(n -> !nations.contains(n)).forEach(nations::add);
    final Map<String, String> byTextKey = new HashMap<>();
    texts.entries(TEXTS).forEach(e -> byTextKey.put(e.key(), e.value()));
    final Map<String, Objective> objectives = new LinkedHashMap<>();
    final Map<String, String> shown = new HashMap<>();
    for (final String nation : nations) {
      for (final Objective objective : all) {
        if (!objective.nation().equals(nation)) {
          continue;
        }
        String key = key(objective.name(), nation);
        for (int i = 2; objectives.containsKey(key); i++) {
          key = key(objective.name(), nation) + " (" + i + ")";
        }
        objectives.put(key, objective);
        final String text = byTextKey.get(MapTexts.objectiveKey(game, nation, objective.name()));
        if (text != null && !text.isBlank()) {
          shown.put(key, text);
        }
      }
    }
    nations.removeIf(n -> all.stream().noneMatch(o -> o.nation().equals(n)));
    return new Listing(
        game, texts, objectives, shown, TriggerXml.conditionsByName(game), List.copyOf(nations));
  }

  private static String key(final String name, final String nation) {
    return name + SEPARATOR + nation;
  }

  /** The nation the filter shows, "" for all. */
  private String nation() {
    final int index = nationFilter.getSelectedIndex();
    return index <= 0 || index > listing.nations().size() ? "" : listing.nations().get(index - 1);
  }

  private List<String> shownKeys() {
    final String nation = nation();
    return listing().objectives().entrySet().stream()
        .filter(e -> nation.isEmpty() || nation.equals(e.getValue().nation()))
        .map(Map.Entry::getKey)
        .toList();
  }

  private String rowTitle(final String key) {
    return title(listing(), key);
  }

  /** The text the players read, without its HTML, else what the objective checks in words. */
  static String title(final Listing listing, final String key) {
    final Objective objective = listing.objectives().get(key);
    if (objective == null) {
      return key;
    }
    final String text =
        listing.texts().getOrDefault(key, "").replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ");
    return text.isBlank()
        ? RulesXml.upperFirst(
            ObjectiveXml.summary(listing.game(), objective.attachment(), listing.conditions()))
        : text.strip();
  }

  private String rowLine(final String key) {
    return line(listing(), key);
  }

  /** Eg "Germans · +5 PUs". */
  static String line(final Listing listing, final String key) {
    return Optional.ofNullable(listing.objectives().get(key))
        .map(o -> o.nation() + SEPARATOR + ObjectiveXml.reward(o.attachment()))
        .orElse("");
  }

  private void choose(@Nullable final String key) {
    if (Objects.equals(key, selected)) {
      return;
    }
    if (!mayLeave()) {
      // back to the one whose check is being filled in
      list.choose(selected);
      return;
    }
    selected = key;
    showInspector();
  }

  private Optional<Objective> chosen() {
    return Optional.ofNullable(selected).map(listing().objectives()::get);
  }

  /**
   * Whether the chosen one's fields may be built again, eg for another objective: what reads is
   * taken first; a check not finished that has something filled in is dropped only if the map
   * maker says so.
   */
  private boolean mayLeave() {
    commit();
    final List<Option> held = heldChecks();
    return held.stream().allMatch(o -> TriggerXml.joined(o).replace(":", "").isBlank())
        || Dialogs.confirm(
            page,
            "Unfinished check",
            "A check of this objective is not finished.\n"
                + TriggerXml.checkProblem(project.game(), held.getFirst()).orElse("")
                + " Going on discards it.",
            "Keep editing",
            "Discard check");
  }

  /** The checks in the fields the game could not read, which are not written. */
  List<Option> heldChecks() {
    final Optional<Objective> chosen = chosen();
    return chosen.isEmpty() || checks == null
        ? List.of()
        : ObjectiveXml.unreadableChecks(
            project.game(), chosen.get().attachment(), checks.attachment());
  }

  // ---- adding and removing

  /** Asks for the nation, the PUs and the text of a new objective. */
  private void add() {
    final Game game = project.game();
    final List<String> nations = MapXml.nations(game);
    if (nations.isEmpty()) {
      Dialogs.warn(page, "Add objective", "Add nations first.");
      return;
    }
    if (!mayLeave()) {
      return;
    }
    final JComboBox<String> nation = new JComboBox<>(nations.toArray(String[]::new));
    nation.setSelectedItem(nation().isEmpty() ? nations.getFirst() : nation());
    final JSpinner pus = Form.newNumber(5, -999, 999, n -> {});
    final JTextField text = new JTextField(28);
    text.putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT, "eg 5 PUs while we hold Paris");
    final Form ask = new Form(360);
    ask.setBorder(BorderFactory.createEmptyBorder());
    ask.setOpaque(false);
    ask.field("Nation", nation).number("PUs each round", pus);
    if (project.texts().unreadable().contains(TEXTS)) {
      // a text typed here could not be written
      ask.note(TEXTS.getFile() + " does not read: fix it in Files, then give it a text.");
    } else {
      ask.field("Text the players read", text);
    }
    ask.note(
            "It pays every round until you add what it checks."
                + (ObjectiveXml.isOn(game)
                    ? ""
                    : " This also turns on the game option National Objectives."))
        .end();
    if (Dialogs.ask(page, "Add objective", ask, "Add objective")) {
      addNew(
          Objects.toString(nation.getSelectedItem(), nations.getFirst()),
          (Integer) pus.getValue(),
          text.getText().strip());
    }
  }

  /**
   * Adds an objective of the nation as one step: the attachment, its text if there is one, and
   * the game option National Objectives, without which it would not pay.
   */
  void addNew(final String nation, final int pus, final String text) {
    final Game game = project.game();
    final String name = ObjectiveXml.newName(game, nation);
    selected = key(name, nation);
    project.edit(
        g -> ObjectiveXml.withOn(ObjectiveXml.withNew(g, name, nation, pus)),
        texts -> text.isEmpty() ? texts : texts.withObjectiveText(game, nation, name, text));
    reveal();
  }

  /** Shows the chosen one in the list too, with all nations if the filter hides it. */
  private void reveal() {
    if (selected != null && !shownKeys().contains(selected)) {
      nationFilter.setSelectedIndex(0);
    }
    list.choose(selected);
  }

  /** A copy of the chosen one with its text, as one step; the copy is chosen. */
  void duplicate() {
    if (!mayLeave()) {
      return;
    }
    chosen()
        .ifPresent(
            objective -> {
              final Game game = project.game();
              final String name = ObjectiveXml.copyName(game, objective);
              final Optional<String> text = text(objective);
              selected = key(name, objective.nation());
              project.edit(
                  g -> ObjectiveXml.withDuplicate(g, objective, name),
                  texts ->
                      text.map(t -> texts.withObjectiveText(game, objective.nation(), name, t))
                          .orElse(texts));
              reveal();
            });
  }

  private void remove() {
    final Optional<Objective> chosen = chosen();
    if (chosen.isEmpty()) {
      return;
    }
    final Game game = project.game();
    final String title = rowTitle(Objects.toString(selected, ""));
    final List<String> named = RulesXml.whereNamed(game, chosen.get().name());
    if (!named.isEmpty()) {
      Dialogs.refuseNamed(
          page,
          "Remove objective",
          "“" + title + "” stays.\nThese still name it, so the game would not load without it: "
              + String.join(", ", named),
          named);
    } else if (Dialogs.confirmRemove(
        page,
        "Remove objective",
        "Remove the objective “" + title + "”?\nIts text goes too.",
        List.of(),
        "Remove")) {
      removeChosen();
    }
  }

  /**
   * Removes the chosen one with its text and its place in its group, as one step; not while
   * something names it, as the game would not load. Whether it was removed.
   */
  boolean removeChosen() {
    final Game game = project.game();
    final Optional<Objective> chosen = chosen();
    final Optional<Game> without = chosen.flatMap(o -> ObjectiveXml.without(game, o));
    if (without.isEmpty()) {
      return false;
    }
    final Objective objective = chosen.get();
    selected = null;
    project.edit(
        g -> without.get(),
        texts -> texts.withoutObjectiveText(game, objective.nation(), objective.name()));
    return true;
  }

  // ---- the chosen one

  /** The objective's text in objectives.properties, if it has one. */
  private Optional<String> text(final Objective objective) {
    return project
        .texts()
        .value(TEXTS, MapTexts.objectiveKey(project.game(), objective.nation(), objective.name()));
  }

  /**
   * A change of the chosen one made by a field here, to the objective as it is now, not as it was
   * when the fields were built: the fields stay, so typing goes on.
   */
  private void write(final UnaryOperator<Attachment> change) {
    chosen()
        .ifPresent(
            now -> {
              final Attachment changed = change.apply(now.attachment());
              if (changed != now.attachment()) {
                editHere(
                    () ->
                        project.edit(
                            g -> AttachmentsXml.withAttachment(g, now.index(), changed)));
              }
            });
  }

  /** A step after which the fields are not built again, only the title and the card. */
  private void editHere(final Runnable edit) {
    editingHere = true;
    try {
      edit.run();
    } finally {
      editingHere = false;
    }
  }

  private void showInspector() {
    pendingTexts.clear();
    inspector.clear();
    checks = null;
    final Optional<Objective> chosen = chosen();
    ((CardLayout) details.getLayout()).show(details, chosen.isEmpty() ? EMPTY : LIST);
    if (chosen.isEmpty()) {
      selected = null;
      inspector.end();
      return;
    }
    final Game game = project.game();
    final Objective objective = chosen.get();
    final Attachment attachment = objective.attachment();
    inspector.row(summary);
    ObjectiveXml.whyNotPaid(game, objective)
        .forEach(reason -> inspector.row(Theme.newStatusLabel(Status.PROBLEM, reason)));

    final JComboBox<String> nation =
        new JComboBox<>(MapXml.nations(game).toArray(String[]::new));
    nation.setSelectedItem(objective.nation());
    nation.addActionListener(e -> moveTo(nation));
    inspector
        .section("Reward")
        .field("Nation", nation)
        .number(
            "PUs each round",
            "Objectives pay PUs only. For another resource make a trigger that gives it.",
            Form.newNumber(
                ObjectiveXml.value(attachment),
                -999,
                999,
                pus -> write(a -> ObjectiveXml.with(a, ObjectiveXml.VALUE, String.valueOf(pus)))));
    showText(objective);

    final Checks shown = TriggerAssistant.checksOf(project, attachment, this::checksChanged);
    checks = shown;
    checkProblem.setVisible(false);
    inspector
        .section(
            "Pays while",
            "All checks must hold at the end of the nation's turn. A check that counts “each”"
                + " pays the PUs once per territory that counts.")
        .row(shown.rows())
        .row(checkProblem)
        .row(noChecks)
        .section("How often")
        .number(
            "Times it pays, at most",
            Form.newBlankNumber(
                ObjectiveXml.uses(attachment).orElse(-1),
                -1,
                "no limit",
                uses ->
                    write(
                        a ->
                            uses < 0
                                ? ObjectiveXml.without(a, ObjectiveXml.USES)
                                : ObjectiveXml.with(
                                    a, ObjectiveXml.USES, String.valueOf(uses)))));
    final List<Option> others = ObjectiveXml.otherOptions(attachment);
    inspector.more(
        "objectives.more", 3 + others.size(), more -> showMore(more, objective, shown, others));
    inspector.remove("Remove objective", this::remove).end();
    showSummary();
  }

  /**
   * The title, the line with the internal name, the card in plain words and the notes on a
   * missing text and missing checks, for the chosen one as it is now.
   */
  private void showSummary() {
    summary.removeAll();
    final Optional<Objective> chosen = chosen();
    if (chosen.isEmpty() || selected == null) {
      return;
    }
    final Objective objective = chosen.get();
    final JButton duplicate = new JButton("Duplicate");
    Theme.setSecondaryButtonStyle(duplicate);
    duplicate.setToolTipText("Makes a copy with the same checks and text");
    duplicate.addActionListener(e -> duplicate());
    final JPanel header = new JPanel(new BorderLayout(Theme.GAP_M, 0));
    header.setOpaque(false);
    final JLabel title = Theme.newTitle(rowTitle(selected));
    title.setToolTipText(objective.name());
    header.add(title, BorderLayout.CENTER);
    header.add(duplicate, BorderLayout.EAST);
    final JLabel id =
        Theme.newSecondaryLabel(rowLine(selected) + SEPARATOR + "id " + objective.name());
    id.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.GAP_S, 0));
    summary.add(header, BorderLayout.NORTH);
    summary.add(id, BorderLayout.CENTER);
    final String checked =
        ObjectiveXml.summary(project.game(), objective.attachment(), listing().conditions());
    summary.add(
        TriggersEditor.capped(
            TriggerAssistant.clauseCard(
                ObjectiveXml.clauses(
                    project.game(), objective.attachment(), listing().conditions()),
                1)),
        BorderLayout.SOUTH);
    noText.setVisible(
        text(objective).isEmpty() && !project.texts().unreadable().contains(TEXTS));
    noChecks.setVisible(checked.equals("always"));
    summary.revalidate();
    summary.repaint();
  }

  /** The chosen one with the nation of the box; its text goes with it, as its key names it. */
  private void moveTo(final JComboBox<String> box) {
    final Optional<Objective> chosen = chosen();
    final String nation = Objects.toString(box.getSelectedItem(), "");
    if (chosen.isEmpty() || nation.isEmpty() || nation.equals(chosen.get().nation())) {
      return;
    }
    if (!mayLeave()) {
      box.setSelectedItem(chosen.get().nation());
      return;
    }
    // as it is after what the fields had was taken
    final Objective objective = chosen().orElseThrow();
    final Game game = project.game();
    final Optional<String> text = text(objective);
    selected = key(objective.name(), nation);
    project.edit(
        g ->
            AttachmentsXml.withAttachment(
                g, objective.index(), objective.attachment().toBuilder().attachTo(nation).build()),
        texts ->
            text.map(
                    t ->
                        texts
                            .withoutObjectiveText(game, objective.nation(), objective.name())
                            .withObjectiveText(game, nation, objective.name(), t))
                .orElse(texts));
    reveal();
  }

  private void showText(final Objective objective) {
    inspector.section(
        "Text the players read",
        "Shown in the game's Objectives tab, in the group of its nation. HTML may be used, eg"
            + " <b>5 PUs</b>.");
    if (project.texts().unreadable().contains(TEXTS)) {
      inspector.note(TEXTS.getFile() + " does not read: fix it in Files.");
      return;
    }
    final JTextArea area = new JTextArea(text(objective).orElse(""), 3, 20);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.getAccessibleContext().setAccessibleName("Text the players read");
    final Runnable keep =
        () -> {
          // a line break would end the entry in the file
          final String typed = area.getText().strip().replaceAll("\\s*\\R\\s*", " ");
          if (chosen().isEmpty() || typed.equals(text(objective).orElse("").strip())) {
            return;
          }
          final Game game = project.game();
          editHere(
              () ->
                  project.edit(
                      g -> g,
                      texts ->
                          typed.isEmpty()
                              ? texts.without(
                                  TEXTS,
                                  MapTexts.objectiveKey(
                                      game, objective.nation(), objective.name()))
                              : texts.withObjectiveText(
                                  game, objective.nation(), objective.name(), typed)));
        };
    area.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            keep.run();
          }
        });
    pendingTexts.add(keep);
    inspector.row(SwingComponents.newJScrollPane(area)).row(noText);
  }

  /**
   * Takes the checks, the nations and "invert" as the fields have them. A check the game could
   * not read yet is held back with a red line, alone: the check it changes stays as it was.
   */
  private void checksChanged() {
    final Optional<Objective> chosen = chosen();
    final Checks shown = checks;
    if (chosen.isEmpty() || shown == null) {
      return;
    }
    final Game game = project.game();
    final Attachment fields = shown.attachment();
    final Optional<String> problem =
        ObjectiveXml.unreadableChecks(game, chosen.get().attachment(), fields).stream()
            .findFirst()
            .flatMap(o -> TriggerXml.checkProblem(game, o));
    checkProblem.setText(
        problem.map(p -> Status.PROBLEM.mark() + " Not taken yet: " + p).orElse(""));
    checkProblem.setVisible(problem.isPresent());
    write(a -> ObjectiveXml.withChecksOf(a, ObjectiveXml.withReadableChecks(game, a, fields)));
  }

  private void showMore(
      final Form more, final Objective objective, final Checks shown, final List<Option> others) {
    final JCheckBox inverted = shown.inverted();
    inverted.setText("Pays while its checks do not hold");
    more.field("Counts the land and units of", shown.forNations())
        .note("None: of its own nation.")
        .row(inverted);
    final JLabel names = new JLabel();
    showConditions(names, ObjectiveXml.conditionNames(objective.attachment()));
    final JButton choose = new JButton("Choose…");
    Theme.setTertiaryButtonStyle(choose);
    choose.addActionListener(e -> chooseConditions(names));
    final JPanel conditions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    conditions.setOpaque(false);
    conditions.add(names);
    conditions.add(Box.createHorizontalStrut(Theme.GAP_S));
    conditions.add(choose);
    more.field("Also needs the conditions", conditions);
    if (!others.isEmpty()) {
      others.forEach(
          o ->
              more.row(
                  Theme.newSecondaryLabel(
                      o.getName() + ": " + TriggerXml.joined(o).replace(":", ", "))));
      more.row(Help.link("Kept as they are. Change them in", "Attachments", objective.name()));
    }
  }

  private static void showConditions(final JLabel names, final List<String> named) {
    names.setText(
        NationsEditor.shortList(named.stream().map(TriggersEditor::conditionTitle).toList()));
    names.setToolTipText(named.isEmpty() ? null : String.join(", ", named));
  }

  /**
   * The conditions before the chosen one in the file to tick; names the game does not have stay.
   * Written to the objective as it is now, the label following.
   */
  private void chooseConditions(final JLabel names) {
    final Optional<Objective> chosen = chosen();
    if (chosen.isEmpty()) {
      return;
    }
    final List<String> named = ObjectiveXml.conditionNames(chosen.get().attachment());
    final List<String> offered =
        AttachmentsXml.attachments(project.game()).subList(0, chosen.get().index()).stream()
            .filter(a -> TriggerXml.CONDITION.equals(AttachmentsXml.kind(a)))
            .map(Attachment::getName)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Dialogs.pickMany(page, "Conditions it also needs", offered, named, "Choose")
        .ifPresent(
            ticked -> {
              final List<String> result =
                  new ArrayList<>(
                      named.stream()
                          .filter(n -> !offered.contains(n) || ticked.contains(n))
                          .distinct()
                          .toList());
              ticked.stream().filter(n -> !result.contains(n)).forEach(result::add);
              write(a -> ObjectiveXml.withConditions(a, result));
              chosen()
                  .map(o -> ObjectiveXml.conditionNames(o.attachment()))
                  .ifPresent(now -> showConditions(names, now));
            });
  }
}
