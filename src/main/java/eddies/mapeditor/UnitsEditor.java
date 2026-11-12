package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.ArrayList;
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
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.NumberFormatter;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapFiles.UnitPicture;
import eddies.mapeditor.MapFiles.UnitPicture.Coloring;
import eddies.mapeditor.UnitOptions.Kind;
import eddies.mapeditor.UnitOptions.UnitKind;

/** The unit types: what they cost and how they fight and move. */
final class UnitsEditor implements Editor {
  private static final String SUPPORT = "Gives support";
  private static final int PICTURE_SIZE = 48;
  private static final int NUMBER_WIDTH = 96;
  private static final int MORE_NAME_WIDTH = 220;
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
  // the sections, side by side when wide enough
  private final Form grid = new Form(280);
  // the sections showing all their options, for the session
  private final Set<String> open = new HashSet<>();
  private @Nullable String selected;
  private @Nullable String openSupport;
  private @Nullable JLabel headerLine;
  private boolean editingHere;
  // the text field typed in and not yet taken, for commit()
  private @Nullable Runnable typing;

  UnitsEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
                "unit",
                "units",
                () -> MapXml.unitTypes(project.game()),
                unit -> unit,
                this::detail,
                this::choose)
            .withAdd("Add", "Add a unit", this::addUnit)
            .withEmpty(
                Panels.empty(
                    Glyph.UNITS,
                    "No units yet",
                    "Units are what nations buy and fight with. Start from a copy of infantry.",
                    "Add unit",
                    this::addUnit));
    grid.setBorder(BorderFactory.createEmptyBorder());
    grid.setOpaque(false);
    // the grid's columns follow its own width, known only after a layout: lay out again then
    grid.addComponentListener(
        new ComponentAdapter() {
          private int width = -1;

          @Override
          public void componentResized(final ComponentEvent e) {
            if (grid.getWidth() != width) {
              width = grid.getWidth();
              SwingUtilities.invokeLater(grid::revalidate);
            }
          }
        });
    page.add(
        Panels.sideBySide(list, SwingComponents.newJScrollPane(inspector)), BorderLayout.CENTER);
    project.onChange(
        () -> {
          list.refresh();
          if (!editingHere) {
            showInspector();
          } else if (headerLine != null && selected != null) {
            headerLine.setText(line(project.game(), selected));
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
    return "The kinds of units. 1-2-1 means attack 1, defense 2, movement 1.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  /** eg "1-2-1 · 3 PUs". */
  private String detail(final String unit) {
    final Game game = project.game();
    return MapXml.unitStat(game, unit, "attack", 0)
        + "-"
        + MapXml.unitStat(game, unit, "defense", 0)
        + "-"
        + MapXml.unitStat(game, unit, "movement", 0)
        + MapXml.unitCost(game, unit)
            .map(cost -> " · " + cost + " PUs")
            .orElse(" · not for sale");
  }

  /** eg "Land unit · 1-2-1 · 3 PUs". */
  private String line(final Game game, final String unit) {
    final String kind =
        UnitsXml.kind(game, unit)
            .map(
                k ->
                    switch (k) {
                      case LAND -> "Land unit";
                      case SEA -> "Sea unit";
                      case AIR -> "Air unit";
                    })
            .orElse("Unit");
    return kind + " · " + detail(unit);
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

  /**
   * Asks a name, the unit to start from and maybe a picture; adds the unit, then its picture as a
   * second step.
   */
  private void addUnit() {
    commit();
    final List<String> units = MapXml.unitTypes(project.game());
    final JTextField name = new JTextField(24);
    name.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "eg elite_infantry");
    final FieldError error = new FieldError(name);
    OnTyping.attach(name, () -> error.setProblem(project.nameProblem(name.getText().strip())));
    final JComboBox<String> like = new JComboBox<>(units.toArray(String[]::new));
    like.setSelectedItem(
        selected != null && units.contains(selected)
            ? selected
            : units.contains("infantry") || units.isEmpty() ? "infantry" : units.getFirst());
    final List<BufferedImage> picture = new ArrayList<>();
    final JLabel file = Theme.newSecondaryLabel("");
    final JButton choosePicture = new JButton("Choose…");
    Theme.setTertiaryButtonStyle(choosePicture);
    choosePicture.addActionListener(
        e ->
            readPicture("Choose a picture for the new unit")
                .ifPresent(
                    chosen -> {
                      picture.clear();
                      picture.add(chosen.image());
                      file.setText(chosen.file().getFileName().toString());
                    }));
    final JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setOpaque(false);
    final List<JComponent> parts = new ArrayList<>(List.of(new JLabel("Name"), name, error));
    if (!units.isEmpty()) {
      parts.addAll(List.of(new JLabel("Start from"), like));
    }
    parts.addAll(List.of(new JLabel("Picture (optional)"), leftAligned(choosePicture, file)));
    for (final JComponent part : parts) {
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      part.setMaximumSize(new Dimension(Integer.MAX_VALUE, part.getPreferredSize().height));
      content.add(part);
      content.add(Box.createVerticalStrut(Theme.GAP_XS));
    }
    if (!Dialogs.ask(
        page,
        "Add unit",
        content,
        "Add",
        () -> !name.getText().isBlank() && project.nameProblem(name.getText().strip()).isEmpty())) {
      return;
    }
    final String unit = name.getText().strip();
    if (project.addUnitType(unit, Objects.toString(like.getSelectedItem(), "infantry"))) {
      choose(unit);
      list.choose(unit);
      picture.forEach(
          image ->
              project.setUnitPicture(
                  unit, new UnitPicture(MapFolder.toUnitSize(image), Coloring.PER_NATION)));
    }
  }

  /** Removes the unit type as one step, unless attachments, eg triggers, still name it. */
  private void removeUnit(final String unit) {
    commit();
    final Game without = MapXml.withoutUnitType(project.game(), unit);
    if (isStillNamed(without, unit, "Remove unit")) {
      return;
    }
    if (Dialogs.confirmRemove(
        page,
        "Remove unit",
        "Remove "
            + unit
            + "?\nIts stats, the rules that buy it and its units at the start go too."
            + " Undo brings them back.",
        List.of(),
        "Remove")) {
      project.edit(g -> MapXml.withoutUnitType(g, unit));
    }
  }

  /** Tells where the game still names it, as it would not load then; whether it does. */
  private boolean isStillNamed(final Game without, final String name, final String title) {
    final List<String> named = RulesXml.whereNamed(without, name);
    if (named.isEmpty()) {
      return false;
    }
    Dialogs.refuseNamed(
        page,
        title,
        name
            + " is still named in:\n"
            + String.join("\n", named.subList(0, Math.min(named.size(), 12)))
            + (named.size() > 12 ? "\n..." : "")
            + "\nChange those first.",
        named);
    return true;
  }

  private void showInspector() {
    typing = null;
    headerLine = null;
    inspector.clear();
    grid.clear();
    final Game game = project.game();
    final List<String> units = MapXml.unitTypes(game);
    if (units.isEmpty()) {
      inspector.end();
      return;
    }
    if (selected == null || !units.contains(selected)) {
      selected = units.getFirst();
      openSupport = null;
      list.choose(selected);
    }
    final String unit = selected;
    showHeader(game, unit);
    showEssentials(game, unit);
    showKind(game, unit);
    showSections(game, unit);
    final List<Option> more = moreOptions(game, unit);
    inspector.more("units.more", more.size(), form -> showMoreOptions(form, game, unit, more));
    inspector.remove("Remove unit", () -> removeUnit(unit));
    inspector.end();
  }

  // ---- the top: picture, name, kind and the numbers of the list's line

  /** The picture, the name and what it is, with the links to change the picture. */
  private void showHeader(final Game game, final String unit) {
    final Optional<UnitPicture> uploaded =
        Optional.ofNullable(project.getFiles().unitPictures().get(unit));
    final List<JComponent> links = new ArrayList<>();
    links.add(newLink("Upload picture…", () -> uploadPicture(unit)));
    uploaded.ifPresent(
        u -> links.add(newLink("Use the game's picture", () -> project.removeUnitPicture(unit))));
    headerLine = Theme.newSecondaryLabel(line(game, unit));
    // the links in a row of their own below the line, as Details' row starts 8 px further in
    final JPanel lineAndLinks = new JPanel();
    lineAndLinks.setLayout(new BoxLayout(lineAndLinks, BoxLayout.Y_AXIS));
    lineAndLinks.setOpaque(false);
    final JPanel linkRow = leftAligned(links.toArray(JComponent[]::new));
    headerLine.setAlignmentX(Component.LEFT_ALIGNMENT);
    linkRow.setAlignmentX(Component.LEFT_ALIGNMENT);
    lineAndLinks.add(headerLine);
    lineAndLinks.add(linkRow);
    inspector.row(
        Details.header(
            project.unitPicture(unit).map(UnitsEditor::newPicture),
            unit,
            lineAndLinks,
            List.of()));
    // an uploaded picture's coloring says it too: one choice, not two
    uploaded.ifPresentOrElse(u -> showColoring(unit, u), () -> showNationTint(unit));
  }

  /**
   * Whether the game tints the unit in each nation's color, once a nation's units are tinted
   * (Nations); off for a unit in the same colors for everyone.
   */
  private void showNationTint(final String unit) {
    final boolean anyTinted =
        MapXml.nations(project.game()).stream()
            .anyMatch(nation -> project.getFiles().unitTint(nation).isPresent());
    if (!anyTinted) {
      return;
    }
    final JCheckBox tinted =
        new JCheckBox(
            "In each nation's color", !project.getFiles().untintedUnits().contains(unit));
    tinted.setToolTipText(
        "Off: the game draws it in its own colors for every nation, eg a unit all nations share,"
            + " and does not turn it to face the other way either.");
    tinted.addActionListener(
        e -> {
          if (tinted.isSelected()) {
            project.tintUnitType(unit);
          } else {
            project.keepUnitTypeColors(unit);
          }
        });
    inspector.row(leftAligned(tinted));
    inspector.row(Help.link("The color is set per nation in", "Nations"));
  }

  /** At most 48 px, never blown up, so a small picture stays sharp. */
  private static JComponent newPicture(final Image image) {
    final int width = image.getWidth(null);
    final int height = image.getHeight(null);
    final double scale = Math.min(1, (double) PICTURE_SIZE / Math.max(width, height));
    return new JLabel(
        new ImageIcon(
            scale == 1
                ? image
                : image.getScaledInstance(
                    (int) Math.round(width * scale),
                    (int) Math.round(height * scale),
                    Image.SCALE_SMOOTH)));
  }

  private static JButton newLink(final String text, final Runnable action) {
    final JButton link = new JButton(text);
    Theme.setTertiaryButtonStyle(link);
    // no margin on the left, so the text lines up with the labels above it
    link.putClientProperty(
        FlatClientProperties.STYLE, "foreground: $Editor.accentText; margin: 2,0,2,4");
    link.addActionListener(e -> action.run());
    return link;
  }

  private void showColoring(final String unit, final UnitPicture uploaded) {
    final JRadioButton perNation =
        new JRadioButton("Color it for each nation", uploaded.coloring() == Coloring.PER_NATION);
    final JRadioButton asIs =
        new JRadioButton("Same for all nations", uploaded.coloring() == Coloring.AS_IS);
    final ButtonGroup coloring = new ButtonGroup();
    coloring.add(perNation);
    coloring.add(asIs);
    perNation.addActionListener(e -> setColoring(unit, uploaded, Coloring.PER_NATION));
    asIs.addActionListener(e -> setColoring(unit, uploaded, Coloring.AS_IS));
    inspector.row(leftAligned(perNation, asIs));
  }

  /** Cost, attack, defense, movement and hit points in one row, labels above. */
  private void showEssentials(final Game game, final String unit) {
    final JPanel cells = new JPanel(new GridLayout(2, 5, Theme.GAP_M, Theme.GAP_XS));
    cells.setOpaque(false);
    final List<JComponent> fields = new ArrayList<>();
    final Optional<Integer> cost = MapXml.unitCost(game, unit);
    if (cost.isPresent()) {
      final JSpinner spinner =
          Form.newNumber(
              cost.get(), 999, value -> quietly(g -> MapXml.withUnitCost(g, unit, value)));
      spinner.setToolTipText("PUs to buy one");
      fields.add(spinner);
    } else {
      final JLabel none = new JLabel("–");
      none.setToolTipText("No rule sells it; add one in Production");
      fields.add(none);
    }
    UnitOptions.ESSENTIALS.forEach(
        name -> fields.add(newEssential(game, unit, UnitOptions.named(name))));
    final List<String> labels = List.of("Cost", "Attack", "Defense", "Movement", "Hit points");
    for (int i = 0; i < labels.size(); i++) {
      final JLabel label = new JLabel(labels.get(i));
      label.setLabelFor(fields.get(i));
      label.setToolTipText(fields.get(i).getToolTipText());
      cells.add(label);
    }
    fields.forEach(
        field -> {
          field.setPreferredSize(new Dimension(NUMBER_WIDTH, field.getPreferredSize().height));
          cells.add(field);
        });
    inspector.row(leftAligned(cells));
  }

  /** The number's field, or where it is set instead. */
  private JComponent newEssential(
      final Game game, final String unit, final UnitOptions.UnitOption option) {
    final Optional<String> setBy = UnitsXml.setBy(game, unit, option.name());
    if (setBy.isPresent()) {
      return Theme.newSecondaryLabel("Set by " + setBy.get());
    }
    if (!UnitsXml.isPlain(game, unit, option.name())) {
      return Theme.newSecondaryLabel("In More options");
    }
    final Consumer<String> set = v -> quietly(g -> UnitsXml.withValue(g, unit, option, v));
    final JComponent field =
        parseInt(UnitsXml.value(game, unit, option))
            .<JComponent>map(
                number ->
                    Form.newNumber(
                        number, option.minimum(), 9999, v -> set.accept(String.valueOf(v))))
            .orElseGet(() -> newTextField(() -> UnitsXml.value(project.game(), unit, option), set));
    field.setToolTipText(option.help());
    return field;
  }

  /** Land, sea or air; none chosen for a unit that both sails and flies. */
  private void showKind(final Game game, final String unit) {
    final Optional<UnitKind> kind = UnitsXml.kind(game, unit);
    final ButtonGroup group = new ButtonGroup();
    final List<JComponent> parts = new ArrayList<>(List.of(new JLabel("Kind")));
    for (final UnitKind each : UnitKind.values()) {
      final String word = each.name().charAt(0) + each.name().substring(1).toLowerCase(Locale.ROOT);
      final JRadioButton radio = new JRadioButton(word, kind.equals(Optional.of(each)));
      // a change of kind changes which sections show, so the page is shown again
      radio.addActionListener(
          e -> {
            if (!UnitsXml.kind(project.game(), unit).equals(Optional.of(each))) {
              commit();
              project.edit(g -> UnitsXml.withKind(g, unit, each));
            }
          });
      group.add(radio);
      parts.add(radio);
    }
    if (kind.isEmpty()) {
      parts.add(Theme.newSecondaryLabel("Both sail and fly: set in Sea / Air"));
    }
    inspector.row(leftAligned(parts.toArray(JComponent[]::new)));
  }

  // ---- the sections

  /**
   * The sections for the unit's kind, and those with an option set, each with its common options
   * and those set, all of them once opened; the other sections closed below them.
   */
  private void showSections(final Game game, final String unit) {
    final Optional<UnitKind> kind = UnitsXml.kind(game, unit);
    final List<JComponent> closed = new ArrayList<>();
    grid.columns();
    for (final UnitOptions.Section section : UnitOptions.SECTIONS) {
      final String title = section.title();
      final List<UnitOptions.UnitOption> all =
          section.options().stream()
              .filter(o -> !UnitOptions.ESSENTIALS.contains(o.name()))
              .filter(o -> kind.isEmpty() || !UnitOptions.KIND_FLAGS.contains(o.name()))
              .filter(o -> UnitsXml.isPlain(game, unit, o.name()))
              .toList();
      final Predicate<UnitOptions.UnitOption> isSet =
          o ->
              !UnitsXml.value(game, unit, o).equals(o.defaultValue())
                  || UnitsXml.setBy(game, unit, o.name()).isPresent();
      final boolean relevant = isFor(title, kind) || all.stream().anyMatch(isSet);
      final List<UnitOptions.UnitOption> few =
          all.stream()
              .filter(o -> isSet.test(o) || (relevant && UnitOptions.COMMON.contains(o.name())))
              .toList();
      final boolean isOpen = open.contains(title);
      if (!relevant && !isOpen) {
        closed.add(
            newToggle(
                "▸ " + title + " (" + all.size() + ")",
                "Show the " + title.toLowerCase(Locale.ROOT) + " options",
                title));
        continue;
      }
      grid.section(title);
      final List<UnitOptions.UnitOption> shown = isOpen ? all : few;
      if (shown.isEmpty()) {
        grid.note("Nothing special.");
      }
      shown.forEach(o -> showOption(grid, game, unit, o));
      if (few.size() < all.size()) {
        grid.row(
            leftAligned(
                isOpen
                    ? newToggle("Show fewer ‹", "Show the common options and those set", title)
                    : newToggle(
                        "Show all " + all.size() + " " + title.toLowerCase(Locale.ROOT)
                            + " options ›",
                        "Show every option of " + title.toLowerCase(Locale.ROOT),
                        title)));
      }
    }
    grid.section(SUPPORT);
    showSupports(game, unit);
    inspector.row(grid);
    if (!closed.isEmpty()) {
      inspector.row(leftAligned(closed.toArray(JComponent[]::new)));
    }
  }

  /** Whether the section is for every unit of the kind, set or not. */
  private static boolean isFor(final String section, final Optional<UnitKind> kind) {
    return switch (section) {
      case "Combat", "Movement", "Transport and carrier" -> true;
      case "Sea" -> kind.equals(Optional.of(UnitKind.SEA));
      case "Air and anti-air" -> kind.equals(Optional.of(UnitKind.AIR));
      default -> false;
    };
  }

  /** A link that shows all of a section's options, or only the common ones again. */
  private JButton newToggle(final String text, final String tip, final String section) {
    final JButton toggle =
        newLink(
            text,
            () -> {
              commit();
              if (!open.remove(section)) {
                open.add(section);
              }
              showInspector();
            });
    toggle.setToolTipText(tip);
    return toggle;
  }

  /**
   * The option's field; one an old option, eg isTwoHit, sets is changed there instead, as leaving
   * the new one out would not undo the old one.
   */
  private void showOption(
      final Form form, final Game game, final String unit, final UnitOptions.UnitOption option) {
    final Optional<String> setBy = UnitsXml.setBy(game, unit, option.name());
    if (setBy.isPresent()) {
      form.field(
          option.label(),
          Theme.newSecondaryLabel("Set by " + setBy.get() + " in More options"));
      return;
    }
    final String value = UnitsXml.value(game, unit, option);
    final Consumer<String> set = v -> quietly(g -> UnitsXml.withValue(g, unit, option, v));
    final Optional<Integer> number = parseInt(value);
    if (option.kind() == Kind.FLAG) {
      final JCheckBox box = new JCheckBox(option.label(), Boolean.parseBoolean(value));
      box.addActionListener(e -> set.accept(String.valueOf(box.isSelected())));
      form.row(withTip(box, option.help()));
      final int supports = UnitsXml.supports(game, unit).size();
      if ("artillery".equals(option.name()) && supports > 0) {
        form.note(
            "It also gives "
                + (supports == 1 ? "one support" : supports + " supports")
                + " of its own: see Gives support.");
      }
    } else if (option.kind() == Kind.NUMBER && number.isPresent()) {
      final IntConsumer setNumber = v -> set.accept(String.valueOf(v));
      final String word = UnitOptions.BLANK_WORDS.get(option.name());
      final JSpinner spinner =
          word == null
              ? Form.newNumber(number.get(), option.minimum(), 9999, setNumber)
              : blankNumber(number.get(), option.minimum(), word, setNumber);
      form.number(option.label(), withTip(spinner, option.help()));
    } else {
      form.field(
          option.label(),
          withTip(
              newListField(
                  option.label(),
                  () -> UnitsXml.value(project.game(), unit, option),
                  choices(option.kind()),
                  set),
              option.help()));
    }
  }

  private static <T extends JComponent> T withTip(final T component, final String tip) {
    if (!tip.isEmpty()) {
      component.setToolTipText(tip);
    }
    return component;
  }

  /**
   * A number whose default -1 shows as an empty field with the word in it, eg "none"; emptied, it
   * is -1 again, which leaves the option out.
   */
  private static JSpinner blankNumber(
      final int value, final int min, final String word, final IntConsumer onChange) {
    final JSpinner spinner = Form.newNumber(value, min, 9999, onChange);
    final JFormattedTextField text = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
    text.setFormatterFactory(
        new DefaultFormatterFactory(new BlankFormatter((SpinnerNumberModel) spinner.getModel())));
    text.setValue(spinner.getValue());
    text.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, word);
    return spinner;
  }

  /** Digits only, as Form's numbers, with -1 as the empty text. */
  private static final class BlankFormatter extends NumberFormatter {
    @Serial private static final long serialVersionUID = 1L;
    private static final Integer BLANK = -1;

    BlankFormatter(final SpinnerNumberModel model) {
      super(new DecimalFormat("#"));
      setValueClass(Integer.class);
      setMinimum(model.getMinimum());
      setMaximum(model.getMaximum());
    }

    @Override
    public String valueToString(final Object value) throws ParseException {
      return BLANK.equals(value) ? "" : super.valueToString(value);
    }

    @Override
    public Object stringToValue(final String text) throws ParseException {
      return text == null || text.isBlank() ? BLANK : super.stringToValue(text);
    }
  }

  private static Optional<Integer> parseInt(final String value) {
    try {
      return Optional.of(Integer.parseInt(value.strip()));
    } catch (final NumberFormatException e) {
      return Optional.empty();
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

  /** A text field, or for a list of names a short summary and "Choose…". */
  private JComponent newListField(
      final String label,
      final Supplier<String> value,
      final List<String> choices,
      final Consumer<String> onChange) {
    return choices.isEmpty()
        ? newTextField(value, onChange)
        : newNames(label, value, choices, onChange);
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
    OnTyping.attach(text, () -> typing = take);
    return text;
  }

  /**
   * Names joined by colons, shown short with all of them in the tool tip; "Choose…" changes them.
   */
  private JComponent newNames(
      final String label,
      final Supplier<String> value,
      final List<String> choices,
      final Consumer<String> onChange) {
    final JLabel summary = new JLabel();
    showNames(summary, value.get());
    final JButton choose = new JButton("Choose…");
    choose.addActionListener(
        e ->
            pick(label, value.get(), choices)
                .filter(picked -> !picked.equals(value.get().strip()))
                .ifPresent(
                    picked -> {
                      onChange.accept(picked);
                      showNames(summary, value.get());
                    }));
    final JPanel row = new JPanel(new BorderLayout(8, 0));
    row.setOpaque(false);
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
   * Asks which of the names to tick, with those of the value ticked; the value's words that are
   * no name, eg "all", stay in front.
   */
  private Optional<String> pick(final String title, final String value, final List<String> names) {
    final List<String> words = words(value);
    return Dialogs.pickMany(page, title, names, words, "Choose")
        .map(
            picked ->
                Stream.concat(words.stream().filter(w -> !names.contains(w)), picked.stream())
                    .collect(Collectors.joining(":")));
  }

  // ---- the support it gives

  /** One sentence for each support, the one being edited with its fields below it. */
  private void showSupports(final Game game, final String unit) {
    final List<String> supports = UnitsXml.supports(game, unit);
    if (supports.isEmpty()) {
      grid.note("None.");
    }
    for (final String support : supports) {
      final boolean isOpen = support.equals(openSupport);
      final JLabel sentence =
          new JLabel(
              "<html><div style='width:260px'>"
                  + SearchList.escape(supportSentence(game, unit, support))
                  + "</div></html>");
      sentence.setToolTipText(support);
      final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_XS, 0));
      buttons.setOpaque(false);
      buttons.add(
          newLink(
              isOpen ? "Close" : "Edit",
              () -> {
                commit();
                openSupport = isOpen ? null : support;
                showInspector();
              }));
      buttons.add(
          Theme.newIconButton("×", "Remove support", () -> removeSupport(unit, support)));
      final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
      row.setOpaque(false);
      row.add(sentence, BorderLayout.CENTER);
      row.add(buttons, BorderLayout.EAST);
      grid.row(row);
      if (isOpen) {
        showSupport(game, unit, support);
      }
    }
    final JButton add = newLink("+ Add support", () -> addSupport(unit));
    add.setToolTipText(
        "Support like artillery gives: a bonus to other units in the same battle. It starts as +1"
            + " attack to one allied infantry.");
    grid.row(leftAligned(add));
  }

  /**
   * eg "+1 attack to 1 allied infantry when attacking": what the support gives, to how many of
   * which units, and when.
   */
  static String supportSentence(final Game game, final String unit, final String support) {
    final Function<String, List<String>> words =
        option -> words(UnitsXml.supportOption(game, unit, support, option).orElse(""));
    final List<String> sides = words.apply("side");
    final List<String> dice = words.apply("dice");
    final List<String> factions = words.apply("faction");
    final List<String> units = words.apply("unitType");
    final int bonus =
        parseInt(UnitsXml.supportOption(game, unit, support, "bonus").orElse("")).orElse(0);
    final String number = UnitsXml.supportOption(game, unit, support, "number").orElse("0").strip();
    final boolean attacking = sides.stream().anyMatch("offence"::equalsIgnoreCase);
    final boolean defending = sides.stream().anyMatch("defence"::equalsIgnoreCase);
    final boolean allied = factions.stream().anyMatch("allied"::equalsIgnoreCase);
    final boolean enemy = factions.stream().anyMatch("enemy"::equalsIgnoreCase);
    final String kind =
        dice.isEmpty() || dice.equals(List.of("strength"))
            ? ""
            : " "
                + dice.stream()
                    .map(d -> DICE.getOrDefault(d, d))
                    .collect(Collectors.joining(" and "));
    return (bonus >= 0 ? "+" : "")
        + bonus
        + " "
        + both(attacking, defending, "attack", "defense", " and ")
        + kind
        + " to "
        + number
        + both(allied, enemy, " allied", " enemy", " and")
        + " "
        + (units.size() <= 3
            ? String.join(", ", units)
            : String.join(", ", units.subList(0, 2)) + " and " + (units.size() - 2) + " more")
        + " when "
        + both(attacking, defending, "attacking", "defending", " or ");
  }

  /** The first, the second, or both joined, eg "attack and defense"; empty for neither. */
  private static String both(
      final boolean first,
      final boolean second,
      final String firstWord,
      final String secondWord,
      final String joint) {
    return first && second
        ? firstWord + joint + secondWord
        : first ? firstWord : second ? secondWord : "";
  }

  private void showSupport(final Game game, final String unit, final String support) {
    final BiConsumer<String, String> set =
        (option, value) ->
            quietly(g -> UnitsXml.withSupportOption(g, unit, support, option, value));
    final Function<String, String> value =
        option -> UnitsXml.supportOption(game, unit, support, option).orElse("");
    final Function<String, Supplier<String>> now =
        option -> () -> UnitsXml.supportOption(project.game(), unit, support, option).orElse("");
    grid.field(
        "Supports",
        newListField(
            "Supports",
            now.apply("unitType"),
            MapXml.unitTypes(game),
            v -> set.accept("unitType", v)));
    grid.field("When", newChecks(value.apply("side"), SIDES, v -> set.accept("side", v)));
    grid.field(
        "Whose units", newChecks(value.apply("faction"), FACTIONS, v -> set.accept("faction", v)));
    grid.number(
        "Bonus",
        withTip(
            Form.newNumber(
                parseInt(value.apply("bonus")).orElse(0),
                -99,
                99,
                v -> set.accept("bonus", String.valueOf(v))),
            "Added to each supported unit's dice, eg 1, or -1 against enemies."));
    grid.field(
        "To",
        withTip(
            newChecks(value.apply("dice"), DICE, v -> set.accept("dice", v)),
            "What the bonus changes."));
    grid.number(
        "Units one supports",
        Form.newNumber(
            parseInt(value.apply("number")).orElse(0),
            999,
            v -> set.accept("number", String.valueOf(v))));
    grid.field(
        "Kind of bonus",
        withTip(
            newListField(
                "Kind of bonus",
                now.apply("bonusType"),
                List.of(),
                v -> set.accept("bonusType", v)),
            "Bonuses of one kind do not add up on a unit; 2:name lets two add up."));
    grid.field(
        "For nations",
        newListField(
            "For nations",
            now.apply("players"),
            MapXml.nations(game),
            v -> set.accept("players", v)));
    final JCheckBox tech =
        new JCheckBox(
            "Better with improved artillery", Boolean.parseBoolean(value.apply("impArtTech")));
    tech.addActionListener(e -> set.accept("impArtTech", tech.isSelected() ? "true" : ""));
    grid.row(withTip(tech, "The nation's improved artillery technology doubles this support."));
  }

  /**
   * Check boxes for the words of a value joined by colons, eg "offence:defence"; words they do
   * not show stay.
   */
  private static JComponent newChecks(
      final String value, final Map<String, String> labels, final Consumer<String> onChange) {
    final List<String> words = words(value);
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    row.setOpaque(false);
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
    // the new one opens, to be set
    openSupport = name;
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

  /** The unit's options the sections do not show, eg counted or repeated ones. */
  private static List<Option> moreOptions(final Game game, final String unit) {
    return UnitsXml.options(game, unit).stream()
        .filter(
            o ->
                !UnitOptions.NAMES.contains(o.getName())
                    || !UnitsXml.isPlain(game, unit, o.getName()))
        .toList();
  }

  /** Each of the options, by its raw name, with its value and ×; then "+ Add option…". */
  private void showMoreOptions(
      final Form form, final Game game, final String unit, final List<Option> more) {
    final Map<String, Integer> seen = new HashMap<>();
    for (final Option option : UnitsXml.options(game, unit)) {
      final int occurrence = seen.merge(option.getName(), 1, Integer::sum) - 1;
      if (more.contains(option)) {
        showMoreOption(form, unit, option, occurrence);
      }
    }
    final JButton add = newLink("+ Add option…", () -> addOption(unit));
    add.setToolTipText("Any option TripleA's unitAttachment takes, by its name in the game's XML");
    form.row(leftAligned(add));
  }

  /**
   * The option as the n-th of its name, which stays so while the sections add or remove options of
   * other names.
   */
  private void showMoreOption(
      final Form form, final String unit, final Option option, final int occurrence) {
    final String name = option.getName();
    final JComponent value =
        newTextField(
            () ->
                UnitsXml.optionAt(project.game(), unit, name, occurrence)
                    .map(o -> Objects.toString(o.getValue(), ""))
                    .orElse(""),
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
    final String label = name + (option.getCount() == null ? "" : " ×" + option.getCount());
    final JLabel nameLabel = new JLabel(label);
    nameLabel.setToolTipText(label);
    nameLabel.setLabelFor(value);
    nameLabel.setPreferredSize(
        new Dimension(MORE_NAME_WIDTH, nameLabel.getPreferredSize().height));
    final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    row.setOpaque(false);
    row.add(nameLabel, BorderLayout.WEST);
    row.add(value, BorderLayout.CENTER);
    row.add(
        Theme.newIconButton(
            "×",
            "Remove " + name,
            () -> {
              commit();
              project.edit(g -> UnitsXml.withoutOptionAt(g, unit, name, occurrence));
            }),
        BorderLayout.EAST);
    form.row(row);
  }

  /**
   * Asks for an option by its name, those the sections do not show first, and its value; adds it
   * after the unit's others as one step.
   */
  private void addOption(final String unit) {
    commit();
    final List<String> known =
        Stream.concat(
                AttachmentsXml.knownOptions(project.game(), "UnitAttachment").stream()
                    .filter(o -> !UnitOptions.NAMES.contains(o)),
                UnitOptions.SECTIONS.stream()
                    .flatMap(s -> s.options().stream())
                    .map(UnitOptions.UnitOption::name))
            .toList();
    final JTextField search = new JTextField();
    search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search options");
    search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    final JList<String> options = new JList<>(known.toArray(String[]::new));
    options.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    options.setVisibleRowCount(10);
    options.setCellRenderer(new OptionRow(known));
    final JTextField value = new JTextField();
    value.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Value as TripleA reads it");
    options.addListSelectionListener(
        e ->
            value.putClientProperty(
                FlatClientProperties.PLACEHOLDER_TEXT,
                Optional.ofNullable(options.getSelectedValue())
                    .map(UnitOptions.EXTRA_HINTS::get)
                    .map(UnitOptions.Hint::format)
                    .orElse("Value as TripleA reads it")));
    OnTyping.attach(
        search,
        () -> {
          final String typed = search.getText().strip();
          final String filter = typed.toLowerCase(Locale.ROOT);
          final List<String> matching =
              known.stream()
                  .filter(
                      o ->
                          o.toLowerCase(Locale.ROOT).contains(filter)
                              || OptionRow.phrase(o).toLowerCase(Locale.ROOT).contains(filter))
                  .toList();
          // a name TripleA may read that the editor does not know is still taken
          options.setListData(
              (matching.isEmpty() && !typed.isEmpty() ? List.of(typed) : matching)
                  .toArray(String[]::new));
        });
    final JPanel content = new JPanel(new BorderLayout(0, Theme.GAP_S));
    content.setOpaque(false);
    content.add(search, BorderLayout.NORTH);
    content.add(SwingComponents.newJScrollPane(options), BorderLayout.CENTER);
    final JPanel valueRow = new JPanel(new BorderLayout(0, Theme.GAP_XS));
    valueRow.setOpaque(false);
    valueRow.add(new JLabel("Value"), BorderLayout.NORTH);
    valueRow.add(value, BorderLayout.CENTER);
    content.add(valueRow, BorderLayout.SOUTH);
    if (Dialogs.ask(
        page,
        "Add an option to " + unit,
        content,
        "Add option",
        () -> options.getSelectedValue() != null)) {
      final String chosen = options.getSelectedValue().strip();
      project.edit(
          g ->
              UnitsXml.withNewOption(
                  g, unit, Option.builder().name(chosen).value(value.getText().strip()).build()));
    }
  }

  /** An option's raw name, with what it is in plain words, or the offer to add a typed name. */
  private static final class OptionRow extends DefaultListCellRenderer {
    @Serial private static final long serialVersionUID = 1L;
    private final List<String> known;

    OptionRow(final List<String> known) {
      this.known = List.copyOf(known);
    }

    static String phrase(final String option) {
      return Optional.ofNullable(UnitOptions.EXTRA_HINTS.get(option))
          .map(UnitOptions.Hint::phrase)
          .orElseGet(
              () ->
                  UnitOptions.NAMES.contains(option)
                      ? UnitOptions.named(option).label() + " · shown above"
                      : "");
    }

    @Override
    public Component getListCellRendererComponent(
        final JList<?> list,
        final Object value,
        final int index,
        final boolean isSelected,
        final boolean hasFocus) {
      final String option = String.valueOf(value);
      final String phrase = phrase(option);
      final String text =
          !known.contains(option)
              ? "Add '" + SearchList.escape(option) + "' as a new option"
              : SearchList.escape(option)
                  + (phrase.isEmpty()
                      ? ""
                      : "&nbsp;&nbsp;<span style='color:"
                          + Theme.convertColorToHex(
                              isSelected
                                  ? Theme.getSelectionSecondaryTextColor()
                                  : Theme.getSecondaryTextColor())
                          + "'>"
                          + SearchList.escape(phrase)
                          + "</span>");
      return super.getListCellRendererComponent(
          list, "<html>" + text + "</html>", index, isSelected, hasFocus);
    }
  }

  @Override
  public void commit() {
    if (typing != null) {
      typing.run();
    }
  }

  /** The parts in a row from the left edge, 8 px apart. */
  private static JPanel leftAligned(final JComponent... parts) {
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    row.setOpaque(false);
    for (final JComponent part : parts) {
      if (row.getComponentCount() > 0) {
        row.add(Box.createHorizontalStrut(Theme.GAP_S));
      }
      row.add(part);
    }
    return row;
  }

  private void quietly(final UnaryOperator<Game> change) {
    editingHere = true;
    try {
      project.edit(change);
    } finally {
      editingHere = false;
    }
  }

  private void setColoring(final String unit, final UnitPicture uploaded, final Coloring coloring) {
    if (uploaded.coloring() != coloring) {
      project.setUnitPicture(unit, new UnitPicture(uploaded.image(), coloring));
    }
  }

  /** Asks for a picture and makes it the unit type's, colored for each nation, as one step. */
  private void uploadPicture(final String unit) {
    readPicture("Choose a picture for " + unit)
        .ifPresent(
            chosen ->
                project.setUnitPicture(
                    unit,
                    new UnitPicture(MapFolder.toUnitSize(chosen.image()), Coloring.PER_NATION)));
  }

  private record Picture(Path file, BufferedImage image) {}

  /** A picture file the map maker chooses; empty when cancelled or when it does not read. */
  private Optional<Picture> readPicture(final String title) {
    final Optional<Path> file =
        MapMakerWindow.chooseFileToOpen(
            page, title, Path.of(System.getProperty("user.home")), "png", "gif", "jpg", "jpeg");
    if (file.isEmpty()) {
      return Optional.empty();
    }
    try {
      final BufferedImage image = ImageIO.read(file.get().toFile());
      if (image == null) {
        showNoPicture(file.get(), "It is no png, gif or jpg picture.");
        return Optional.empty();
      }
      return Optional.of(new Picture(file.get(), image));
    } catch (final IOException e) {
      showNoPicture(file.get(), e.getMessage());
      return Optional.empty();
    }
  }

  private void showNoPicture(final Path file, final String why) {
    Dialogs.warn(
        page, "Upload picture", "Could not read " + file.getFileName() + " as a picture.\n" + why);
  }
}
