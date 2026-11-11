package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapFiles.UnitPicture;
import eddies.mapeditor.MapFiles.UnitPicture.Coloring;
import eddies.mapeditor.UnitOptions.Kind;

/** The unit types: what they cost and how they fight and move. */
final class UnitsEditor implements Editor {
  private static final String SUPPORT = "Gives support";
  private static final String MORE = "More options";
  private static final Map<String, String> SIDES =
      orderedMap("offence", "attacking", "defence", "defending");
  private static final Map<String, String> FACTIONS =
      orderedMap("allied", "own and allied", "enemy", "enemy");
  private static final Map<String, String> DICE =
      orderedMap(
          "strength",
          "strength",
          "roll",
          "dice",
          "AAstrength",
          "anti-air",
          "AAroll",
          "anti-air dice");

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final SearchList list;
  private final Form inspector = new Form(280);
  private final Set<String> open = new HashSet<>(Set.of("Combat"));
  private @Nullable String selected;
  private @Nullable String openSupport;
  private boolean editingHere;
  // the text field typed in and not yet taken, for commit()
  private @Nullable Runnable typing;

  UnitsEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
            "Search unit", () -> MapXml.unitTypes(project.game()), this::detail, this::choose);
    final JComponent inspectorScroll = SwingComponents.newJScrollPane(inspector);
    final JButton add = new JButton("Add unit...");
    add.addActionListener(e -> addUnit());
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(
        Help.with(
            add,
            "A new kind of unit, starting with the stats and cost of the chosen one, or of"
                + " infantry. The list shows attack-defense-movement and cost."),
        BorderLayout.SOUTH);
    page.add(Panels.sideBySide(listColumn, inspectorScroll), BorderLayout.CENTER);
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
    return "Units";
  }

  @Override
  public Glyph glyph() {
    return Glyph.UNITS;
  }

  @Override
  public String hint() {
    return "The kinds of units. Add or remove them; choose one to change its picture, its cost,"
        + " how it fights and moves, and the support it gives.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  private String detail(final String unit) {
    final Game game = project.game();
    return MapXml.unitStat(game, unit, "attack", 0)
        + "-"
        + MapXml.unitStat(game, unit, "defense", 0)
        + "-"
        + MapXml.unitStat(game, unit, "movement", 0)
        + MapXml.unitCost(game, unit).map(cost -> " · " + cost + " PUs").orElse("");
  }

  private static Map<String, String> orderedMap(final String... wordsAndLabels) {
    final Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < wordsAndLabels.length; i += 2) {
      map.put(wordsAndLabels[i], wordsAndLabels[i + 1]);
    }
    return Collections.unmodifiableMap(map);
  }

  private void choose(final String unit) {
    commit();
    selected = unit;
    openSupport = null;
    showInspector();
  }

  /** Asks a name and adds a unit type with the stats and cost of the chosen one, or infantry. */
  private void addUnit() {
    final List<String> units = MapXml.unitTypes(project.game());
    final String like =
        selected != null && units.contains(selected)
            ? selected
            : units.contains("infantry") || units.isEmpty() ? "infantry" : units.get(0);
    final String name =
        JOptionPane.showInputDialog(
            page,
            "Name of the new unit (it starts with the stats and cost of " + like + "):",
            "Add unit",
            JOptionPane.PLAIN_MESSAGE);
    if (name == null) {
      return;
    }
    final String unit = name.strip();
    if (project.addUnitType(unit, like)) {
      choose(unit);
      list.choose(unit);
      if (JOptionPane.showConfirmDialog(
              page,
              "Upload a picture for " + unit + " now? Until then it looks like " + like + ".",
              "Add unit",
              JOptionPane.YES_NO_OPTION)
          == JOptionPane.YES_OPTION) {
        uploadPicture(unit);
      }
    } else {
      JOptionPane.showMessageDialog(
          page,
          "<html>The name must not be empty or be a territory's, a nation's or a unit's, and"
              + " must not hold : &lt; or (.</html>",
          "Add unit",
          JOptionPane.WARNING_MESSAGE);
    }
  }

  /** Removes the unit type as one step, unless attachments, eg triggers, still name it. */
  private void removeUnit(final String unit) {
    final Game without = MapXml.withoutUnitType(project.game(), unit);
    if (isStillNamed(without, unit, "Remove unit")) {
      return;
    }
    final int answer =
        JOptionPane.showConfirmDialog(
            page,
            "Remove "
                + unit
                + " with its stats, the rules that buy it and its units at the start?"
                + "\nUndo takes it back.",
            "Remove unit",
            JOptionPane.OK_CANCEL_OPTION);
    if (answer == JOptionPane.OK_OPTION) {
      project.edit(g -> MapXml.withoutUnitType(g, unit));
    }
  }

  /** Tells where the game still names it, as it would not load then; whether it does. */
  private boolean isStillNamed(final Game without, final String name, final String title) {
    final List<String> named = RulesXml.whereNamed(without, name);
    if (named.isEmpty()) {
      return false;
    }
    JOptionPane.showMessageDialog(
        page,
        name
            + " is still named in:\n"
            + String.join("\n", named.subList(0, Math.min(named.size(), 12)))
            + (named.size() > 12 ? "\n..." : "")
            + "\nChange those first.",
        title,
        JOptionPane.WARNING_MESSAGE);
    return true;
  }

  private void showInspector() {
    typing = null;
    inspector.clear();
    final Game game = project.game();
    if (selected == null || !MapXml.unitTypes(game).contains(selected)) {
      inspector.note(MapXml.unitTypes(game).size() + " kinds of units in this game.").end();
      return;
    }
    final String unit = selected;
    inspector.title(unit);
    showPicture(unit);
    inspector.section("Cost");
    final Optional<Integer> cost = MapXml.unitCost(game, unit);
    if (cost.isPresent()) {
      inspector.field(
          "PUs to buy one",
          Form.newNumber(
              cost.get(), 999, value -> quietly(g -> MapXml.withUnitCost(g, unit, value))));
    } else {
      inspector.note("No nation can buy it: it has no rule that sells one.");
    }
    for (final UnitOptions.Section section : UnitOptions.SECTIONS) {
      if (showHeading(section.title())) {
        section.options().stream()
            .filter(o -> UnitsXml.isPlain(game, unit, o.name()))
            .forEach(o -> showOption(game, unit, o));
      }
    }
    if (showHeading(SUPPORT)) {
      showSupports(game, unit);
    }
    if (showHeading(MORE)) {
      showMoreOptions(game, unit);
    }
    final JButton remove = new JButton("Remove unit");
    remove.addActionListener(e -> removeUnit(unit));
    inspector.row(leftAligned(remove));
    inspector.end();
  }

  /** A heading that opens or closes what is below it; whether it is open. */
  private boolean showHeading(final String title) {
    final boolean isOpen = open.contains(title);
    final JLabel look = Theme.newSectionLabel(title);
    final JButton heading =
        newHeading(
            (isOpen ? "▾ " : "▸ ") + look.getText(),
            (isOpen ? "Hide " : "Show ") + title.toLowerCase(Locale.ROOT),
            () -> {
              if (!open.remove(title)) {
                open.add(title);
              }
            });
    heading.setFont(look.getFont());
    inspector.row(heading);
    return isOpen;
  }

  /** A line as wide as the form that opens or closes what is below it. */
  private JButton newHeading(final String text, final String tip, final Runnable toggle) {
    final JButton heading = new JButton(text);
    Theme.setBorderlessButtonStyle(heading);
    heading.setHorizontalAlignment(SwingConstants.LEADING);
    heading.putClientProperty(FlatClientProperties.STYLE, "minimumWidth: 0; margin: 2,0,2,0");
    heading.setToolTipText(tip);
    heading.getAccessibleContext().setAccessibleName(tip);
    heading.addActionListener(
        e -> {
          commit();
          toggle.run();
          showInspector();
        });
    return heading;
  }

  /** eg "infantry, mech_infantry · attacking · +1". */
  private static String supportSummary(final Game game, final String unit, final String support) {
    final Function<String, String> value =
        option -> UnitsXml.supportOption(game, unit, support, option).orElse("");
    final List<String> whom = words(value.apply("unitType"));
    final int bonus = parseInt(value.apply("bonus")).orElse(0);
    return (whom.size() <= 2
            ? String.join(", ", whom)
            : whom.getFirst() + " and " + (whom.size() - 1) + " more")
        + " · "
        + value.apply("side").replace("offence", "attacking").replace("defence", "defending")
        + " · "
        + (bonus > 0 ? "+" : "")
        + bonus;
  }

  /**
   * The option's field; one an old option, eg isTwoHit, sets is changed there instead, as leaving
   * the new one out would not undo the old one.
   */
  private void showOption(final Game game, final String unit, final UnitOptions.UnitOption option) {
    final Optional<String> setBy = UnitsXml.setBy(game, unit, option.name());
    if (setBy.isPresent()) {
      final JLabel note = new JLabel("Set by " + setBy.get() + " in More options");
      note.setForeground(Theme.getSecondaryTextColor());
      inspector.field(option.label(), note);
      return;
    }
    final String value = UnitsXml.value(game, unit, option);
    final Consumer<String> set = v -> quietly(g -> UnitsXml.withValue(g, unit, option, v));
    final Optional<Integer> number = parseInt(value);
    if (option.kind() == Kind.FLAG) {
      final JCheckBox box = new JCheckBox(option.label(), Boolean.parseBoolean(value));
      box.addActionListener(e -> set.accept(String.valueOf(box.isSelected())));
      inspector.row(option.help().isEmpty() ? box : Help.with(box, option.help()));
      final int supports = UnitsXml.supports(game, unit).size();
      if ("artillery".equals(option.name()) && supports > 0) {
        inspector.note(
            "It also gives "
                + (supports == 1 ? "one support" : supports + " supports")
                + " of its own: see Gives support.");
      }
    } else if (option.kind() == Kind.NUMBER && number.isPresent()) {
      field(
          option.label(),
          option.help(),
          Form.newNumber(
              number.get(), option.minimum(), 9999, v -> set.accept(String.valueOf(v))));
    } else {
      field(
          option.label(),
          option.help(),
          newListField(
              () -> UnitsXml.value(project.game(), unit, option), choices(option.kind()), set));
    }
  }

  private static Optional<Integer> parseInt(final String value) {
    try {
      return Optional.of(Integer.parseInt(value.strip()));
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }
  }

  private void field(final String label, final String help, final JComponent field) {
    if (help.isEmpty()) {
      inspector.field(label, field);
    } else {
      inspector.field(label, help, field);
    }
  }

  /** The names to pick from for a list of the kind, none for a text. */
  private List<String> choices(final Kind kind) {
    final Game game = project.game();
    return switch (kind) {
      case UNITS -> MapXml.unitTypes(game);
      case NATIONS -> MapXml.nations(game);
      case TERRITORIES -> MapXml.territories(game);
      default -> List.of();
    };
  }

  /** A text field, or for a list of names a short summary and "Choose...". */
  private JComponent newListField(
      final Supplier<String> value, final List<String> choices, final Consumer<String> onChange) {
    return choices.isEmpty() ? newTextField(value, onChange) : newNames(value, choices, onChange);
  }

  /**
   * A text field, taken on Enter or when it loses the focus.
   *
   * @param value the game's value now, as each change taken from the field is a step of its own
   */
  private JTextField newTextField(final Supplier<String> value, final Consumer<String> onChange) {
    final JTextField text = new JTextField(value.get());
    final Runnable take =
        () -> {
          typing = null;
          if (!text.getText().strip().equals(value.get().strip())) {
            onChange.accept(text.getText());
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
    // what is typed and not yet taken, for commit()
    onEdit(text, () -> typing = take);
    return text;
  }

  /**
   * Names joined by colons, shown short with all of them in the tool tip; "Choose..." changes
   * them.
   */
  private JComponent newNames(
      final Supplier<String> value, final List<String> choices, final Consumer<String> onChange) {
    final JLabel summary = new JLabel();
    showNames(summary, value.get());
    final JButton choose = new JButton("Choose...");
    choose.addActionListener(
        e ->
            pick(value.get(), choices)
                .filter(picked -> !picked.equals(value.get().strip()))
                .ifPresent(
                    picked -> {
                      onChange.accept(picked);
                      showNames(summary, value.get());
                    }));
    final JPanel row = new JPanel(new BorderLayout(8, 0));
    row.add(summary, BorderLayout.CENTER);
    row.add(choose, BorderLayout.EAST);
    return row;
  }

  private static void showNames(final JLabel summary, final String value) {
    final List<String> names = words(value);
    summary.setText(
        names.isEmpty()
            ? "None"
            : names.size() <= 3
                ? String.join(", ", names)
                : String.join(", ", names.subList(0, 2)) + " and " + (names.size() - 2) + " more");
    summary.setToolTipText(
        names.size() <= 3
            ? null
            : "<html><div style='width:300px'>"
                + SearchList.escape(String.join(", ", names))
                + "</div></html>");
  }

  private static List<String> words(final String value) {
    return Arrays.stream(value.split(":")).map(String::strip).filter(w -> !w.isEmpty()).toList();
  }

  /**
   * Asks which of the names to take, with those of the value ticked; the value's words that are
   * no name, eg "all", stay in front.
   */
  private Optional<String> pick(final String value, final List<String> names) {
    final List<String> words = words(value);
    return Panels.pick(page, "Choose", names, words)
        .map(
            picked ->
                Stream.concat(words.stream().filter(w -> !names.contains(w)), picked.stream())
                    .collect(Collectors.joining(":")));
  }

  // ---- the support it gives

  /** One line for each support, the chosen one's form below it. */
  private void showSupports(final Game game, final String unit) {
    for (final String support : UnitsXml.supports(game, unit)) {
      final boolean isOpen = support.equals(openSupport);
      final JButton line =
          newHeading(
              (isOpen ? "▾ " : "▸ ") + supportSummary(game, unit, support),
              (isOpen ? "Hide " : "Show ") + support,
              () -> openSupport = isOpen ? null : support);
      line.setFont(line.getFont().deriveFont(Font.PLAIN));
      inspector.row(line);
      if (isOpen) {
        showSupport(game, unit, support);
      }
    }
    final JButton add = new JButton("Add support");
    add.setToolTipText("A new support " + unit + " gives");
    add.addActionListener(e -> addSupport(unit));
    inspector.row(
        Help.with(
            add,
            "Support like artillery gives: a bonus to other units in the same battle. It starts"
                + " as +1 attack to one allied infantry."));
  }

  private void showSupport(final Game game, final String unit, final String support) {
    final BiConsumer<String, String> set =
        (option, value) ->
            quietly(g -> UnitsXml.withSupportOption(g, unit, support, option, value));
    final Function<String, String> value =
        option -> UnitsXml.supportOption(game, unit, support, option).orElse("");
    final Function<String, Supplier<String>> now =
        option -> () -> UnitsXml.supportOption(project.game(), unit, support, option).orElse("");
    inspector.field(
        "Supports",
        newListField(
            now.apply("unitType"), MapXml.unitTypes(game), v -> set.accept("unitType", v)));
    inspector.field(
        "When",
        newChecks(value.apply("side"), SIDES, v -> set.accept("side", v)));
    inspector.field(
        "Whose units",
        newChecks(value.apply("faction"), FACTIONS, v -> set.accept("faction", v)));
    inspector.field(
        "Bonus",
        "Added to each supported unit's dice, eg 1, or -1 against enemies.",
        Form.newNumber(
            parseInt(value.apply("bonus")).orElse(0),
            -99,
            99,
            v -> set.accept("bonus", String.valueOf(v))));
    inspector.field(
        "To",
        "What the bonus changes.",
        newChecks(value.apply("dice"), DICE, v -> set.accept("dice", v)));
    inspector.field(
        "Units one supports",
        Form.newNumber(
            parseInt(value.apply("number")).orElse(0),
            999,
            v -> set.accept("number", String.valueOf(v))));
    inspector.field(
        "Kind of bonus",
        "Bonuses of one kind do not add up on a unit; 2:name lets two add up.",
        newListField(now.apply("bonusType"), List.of(), v -> set.accept("bonusType", v)));
    inspector.field(
        "For nations",
        newListField(now.apply("players"), MapXml.nations(game), v -> set.accept("players", v)));
    final JCheckBox tech =
        new JCheckBox(
            "Better with improved artillery", Boolean.parseBoolean(value.apply("impArtTech")));
    tech.addActionListener(
        e -> set.accept("impArtTech", tech.isSelected() ? "true" : ""));
    inspector.row(
        Help.with(tech, "The nation's improved artillery technology doubles this support."));
    final JButton remove = new JButton("Remove support");
    remove.setToolTipText("Remove " + support);
    remove.getAccessibleContext().setAccessibleName("Remove " + support);
    remove.addActionListener(e -> removeSupport(unit, support));
    inspector.row(leftAligned(remove));
  }

  /**
   * Check boxes for the words of a value joined by colons, eg "offence:defence"; words they do
   * not show stay.
   */
  private static JComponent newChecks(
      final String value, final Map<String, String> labels, final Consumer<String> onChange) {
    final List<String> words =
        Arrays.stream(value.split(":")).map(String::strip).filter(w -> !w.isEmpty()).toList();
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    final Map<String, JCheckBox> boxes = new LinkedHashMap<>();
    labels.forEach(
        (word, label) ->
            boxes.put(
                word,
                new JCheckBox(label, words.stream().anyMatch(word::equalsIgnoreCase))));
    boxes.values()
        .forEach(
            box -> {
              row.add(box);
              box.addActionListener(
                  e ->
                      onChange.accept(
                          Stream.concat(
                                  words.stream()
                                      .filter(
                                          w ->
                                              boxes.keySet().stream()
                                                  .noneMatch(w::equalsIgnoreCase)),
                                  boxes.entrySet().stream()
                                      .filter(b -> b.getValue().isSelected())
                                      .map(Map.Entry::getKey))
                              .collect(Collectors.joining(":"))));
            });
    return row;
  }

  private void addSupport(final String unit) {
    commit();
    final String name = AttachmentsXml.newName(project.game(), UnitsXml.SUPPORT_KIND);
    project.edit(g -> UnitsXml.withNewSupport(g, unit, name));
  }

  private void removeSupport(final String unit, final String support) {
    commit();
    final Game without = UnitsXml.withoutSupport(project.game(), unit, support);
    if (!isStillNamed(without, support, "Remove support")) {
      project.edit(g -> UnitsXml.withoutSupport(g, unit, support));
    }
  }

  // ---- every other option

  /** The unit's options the sections above do not show, each with its value and count. */
  private void showMoreOptions(final Game game, final String unit) {
    final Map<String, Integer> seen = new HashMap<>();
    for (final Option option : UnitsXml.options(game, unit)) {
      final int occurrence = seen.merge(option.getName(), 1, Integer::sum) - 1;
      if (!UnitOptions.NAMES.contains(option.getName())
          || !UnitsXml.isPlain(game, unit, option.getName())) {
        showMoreOption(unit, option, occurrence);
      }
    }
    final JButton add = new JButton("Add option...");
    add.setToolTipText("Add any option of " + unit + " by its name");
    add.addActionListener(e -> addOption(unit));
    inspector.row(
        Help.with(
            add,
            "Any option TripleA's unitAttachment takes, by its name in the game's XML; see the"
                + " map making guide."));
  }

  /**
   * The option as the n-th of its name, which stays so while the sections add or remove options of
   * other names.
   */
  private void showMoreOption(final String unit, final Option option, final int occurrence) {
    final String name = option.getName();
    final JComponent value =
        newListField(
            () ->
                UnitsXml.optionAt(project.game(), unit, name, occurrence)
                    .map(o -> Objects.toString(o.getValue(), ""))
                    .orElse(""),
            List.of(),
            v ->
                quietly(
                    g ->
                        UnitsXml.withOptionAt(
                            g,
                            unit,
                            name,
                            occurrence,
                            Option.builder()
                                .name(option.getName())
                                .value(v.strip())
                                .count(option.getCount())
                                .build())));
    final JButton remove = new JButton("×");
    remove.setToolTipText("Remove " + option.getName());
    remove.getAccessibleContext().setAccessibleName("Remove " + option.getName());
    remove.addActionListener(
        e -> {
          commit();
          project.edit(g -> UnitsXml.withoutOptionAt(g, unit, name, occurrence));
        });
    final JPanel line = new JPanel(new BorderLayout(4, 0));
    line.add(value, BorderLayout.CENTER);
    line.add(remove, BorderLayout.EAST);
    inspector.field(
        option.getName() + (option.getCount() == null ? "" : " (count " + option.getCount() + ")"),
        line);
  }

  private void addOption(final String unit) {
    commit();
    final JComboBox<String> name =
        new JComboBox<>(
            AttachmentsXml.knownOptions(project.game(), "UnitAttachment").toArray(String[]::new));
    name.setEditable(true);
    final JTextField value = new JTextField(24);
    final JPanel ask = new JPanel(new GridLayout(0, 1, 0, 4));
    Stream.of(new JLabel("Option"), name, new JLabel("Value"), value).forEach(ask::add);
    if (Panels.ask(page, ask, "Add option")) {
      final String chosen = Objects.toString(name.getSelectedItem(), "").strip();
      if (!chosen.isEmpty()) {
        project.edit(
            g ->
                UnitsXml.withNewOption(
                    g,
                    unit,
                    Option.builder().name(chosen).value(value.getText().strip()).build()));
      }
    }
  }

  @Override
  public void commit() {
    if (typing != null) {
      typing.run();
    }
  }

  private static JPanel leftAligned(final JComponent component) {
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    row.add(component);
    return row;
  }

  /** Runs the action after each change of the field's text. */
  private static void onEdit(final JTextField field, final Runnable action) {
    field.getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(final DocumentEvent e) {
                action.run();
              }

              @Override
              public void removeUpdate(final DocumentEvent e) {
                action.run();
              }

              @Override
              public void changedUpdate(final DocumentEvent e) {
                action.run();
              }
            });
  }

  private void quietly(final UnaryOperator<Game> change) {
    editingHere = true;
    try {
      project.edit(change);
    } finally {
      editingHere = false;
    }
  }

  /**
   * The unit's picture, twice its size, with the buttons to upload one or go back to the game's.
   */
  private void showPicture(final String unit) {
    project.unitPicture(unit)
        .ifPresent(
            image ->
                inspector.row(
                    new JLabel(
                        new ImageIcon(
                            image.getScaledInstance(
                                image.getWidth(null) * 2,
                                image.getHeight(null) * 2,
                                Image.SCALE_SMOOTH)),
                        SwingConstants.LEADING)));
    final Optional<UnitPicture> uploaded =
        Optional.ofNullable(project.getFiles().unitPictures().get(unit));
    final JButton upload = new JButton("Upload picture...");
    upload.addActionListener(e -> uploadPicture(unit));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    buttons.add(
        Help.with(
            upload,
            "A png, gif or jpg picture, shrunk to 48x48 if larger. Saving writes it for every"
                + " nation, over the unit pictures the map has; the first ones stay as .bak."));
    if (uploaded.isEmpty()) {
      inspector.row(buttons);
      return;
    }
    final JButton gamePicture = new JButton("Use the game's picture");
    gamePicture.addActionListener(e -> project.removeUnitPicture(unit));
    buttons.add(Box.createHorizontalStrut(6));
    buttons.add(gamePicture);
    inspector.row(buttons);
    final JRadioButton perNation =
        new JRadioButton(
            "Color it for each nation", uploaded.get().coloring() == Coloring.PER_NATION);
    final JRadioButton asIs =
        new JRadioButton("Same for all nations", uploaded.get().coloring() == Coloring.AS_IS);
    final ButtonGroup coloring = new ButtonGroup();
    coloring.add(perNation);
    coloring.add(asIs);
    perNation.addActionListener(e -> setColoring(unit, uploaded.get(), Coloring.PER_NATION));
    asIs.addActionListener(e -> setColoring(unit, uploaded.get(), Coloring.AS_IS));
    final JPanel colorings = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    colorings.add(perNation);
    colorings.add(asIs);
    inspector.row(colorings);
  }

  private void setColoring(final String unit, final UnitPicture uploaded, final Coloring coloring) {
    if (uploaded.coloring() != coloring) {
      project.setUnitPicture(unit, new UnitPicture(uploaded.image(), coloring));
    }
  }

  /** Asks for a picture and makes it the unit type's, colored for each nation, as one step. */
  private void uploadPicture(final String unit) {
    final Optional<Path> file =
        MapMakerWindow.chooseFileToOpen(
            page,
            "Choose a picture for " + unit,
            Path.of(System.getProperty("user.home")),
            "png",
            "gif",
            "jpg",
            "jpeg");
    if (file.isEmpty()) {
      return;
    }
    final BufferedImage image;
    try {
      image = ImageIO.read(file.get().toFile());
    } catch (final IOException e) {
      showNoPicture(file.get(), e.getMessage());
      return;
    }
    if (image == null) {
      showNoPicture(file.get(), "It is no png, gif or jpg picture.");
      return;
    }
    project.setUnitPicture(unit, new UnitPicture(MapFolder.toUnitSize(image), Coloring.PER_NATION));
  }

  private void showNoPicture(final Path file, final String why) {
    JOptionPane.showMessageDialog(
        page,
        "Could not read " + file.getFileName() + " as a picture:\n" + why,
        "Upload picture",
        JOptionPane.WARNING_MESSAGE);
  }
}
