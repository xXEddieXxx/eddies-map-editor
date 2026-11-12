package eddies.mapeditor;

import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.NumberFormatter;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The terrain effects, eg forest or mountain: adding, renaming and removing them, what each does
 * to units in battle and on the move, who may enter, and its picture. Territories gives them to
 * territories.
 */
final class TerritoryEffectsEditor implements Editor {
  // the game draws an effect from territoryEffects/<name>.png in the map's folder
  private static final String PICTURES = "territoryEffects";
  private static final int MAX_BONUS = 12;
  private static final int PICTURE_SIZE = 30;
  private static final int SPINNER_WIDTH = 72;
  private static final int NAMES_SHOWN = 3;
  private static final String EMPTY = "empty";
  private static final String EFFECTS = "effects";
  private static final String NAME_PROBLEM =
      "Used by another effect, territory, nation or unit, or holds : < (";

  private final MapProject project;
  private final Consumer<String> showOnMap;
  private final CardLayout cards = new CardLayout();
  private final JPanel page = new JPanel(cards);
  private final SearchList list;
  private final Form details = new Form(420);
  private @Nullable String selected;
  // unit types added to the table, shown until they get a value
  private final Set<String> addedRows = new LinkedHashSet<>();
  private boolean editingHere;
  // the name typed and not yet taken, for commit()
  private Runnable commitName = () -> {};
  // the rename last refused as named elsewhere, so focus moving to the dialog does not ask again
  private String refusedRename = "";
  private @Nullable Game countedIn;
  private Map<String, List<String>> territories = Map.of();

  TerritoryEffectsEditor(final MapProject project) {
    this(project, effect -> {});
  }

  /**
   * @param showOnMap shows the territories with the effect on the map, painting it on a click
   */
  TerritoryEffectsEditor(final MapProject project, final Consumer<String> showOnMap) {
    this.project = project;
    this.showOnMap = showOnMap;
    list =
        new SearchList(
                "effect",
                "effects",
                () -> TerritoryEffectsXml.effects(project.game()),
                e -> e,
                this::detail,
                this::choose)
            .withAdd("Add", "A new effect, eg forest", this::addEffect);
    page.add(
        Panels.empty(
            Glyph.EFFECTS,
            "No terrain effects yet",
            "An effect such as forest or mountain changes how units fight and move in the"
                + " territories that have it.",
            "Add effect…",
            this::addEffect),
        EMPTY);
    page.add(
        Panels.sideBySide(list, SwingComponents.newJScrollPane(details)), EFFECTS);
    project.onChange(
        () -> {
          list.refresh();
          if (!editingHere) {
            showDetails();
          }
        });
    showDetails();
  }

  @Override
  public String title() {
    return "Terrain effects";
  }

  @Override
  public Glyph glyph() {
    return Glyph.EFFECTS;
  }

  @Override
  public String hint() {
    return "Effects such as forest or mountain: how units fight and move in the territories that"
        + " have them.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void commit() {
    commitName.run();
  }

  /** The territories with the effect; found once per game, as each list row asks. */
  private List<String> territoriesWith(final String effect) {
    if (countedIn != project.game()) {
      countedIn = project.game();
      territories = TerritoryEffectsXml.territoriesWith(countedIn);
    }
    return territories.getOrDefault(effect, List.of());
  }

  private String detail(final String effect) {
    return count(territoriesWith(effect).size());
  }

  private static String count(final int territories) {
    return territories + (territories == 1 ? " territory" : " territories");
  }

  private void choose(final String effect) {
    if (!effect.equals(selected)) {
      commit();
      addedRows.clear();
    }
    selected = effect;
    showDetails();
  }

  private void addEffect() {
    Dialogs.askName(
            page,
            "Add effect",
            "Name",
            "",
            "Add",
            n -> isFreeName(n) ? Optional.empty() : Optional.of(NAME_PROBLEM))
        .ifPresent(
            name -> {
              project.edit(g -> TerritoryEffectsXml.withEffect(g, name));
              choose(name);
              list.choose(name);
            });
  }

  /** Not another effect's, territory's, nation's or unit's: options name them all alike. */
  private boolean isFreeName(final String name) {
    return project.isFreeName(name) && !TerritoryEffectsXml.effects(project.game()).contains(name);
  }

  private void showDetails() {
    final List<String> effects = TerritoryEffectsXml.effects(project.game());
    cards.show(page, effects.isEmpty() ? EMPTY : EFFECTS);
    commitName = () -> {};
    details.clear();
    if (effects.isEmpty()) {
      details.end();
      return;
    }
    if (selected == null || !effects.contains(selected)) {
      selected = effects.getFirst();
      addedRows.clear();
      list.choose(selected);
    }
    fillDetails(selected);
  }

  private void fillDetails(final String effect) {
    final Game game = project.game();
    details.row(header(effect));
    if (project.getFiles().folder().isEmpty()) {
      details.note("Save the map first: the picture goes into its folder.");
    }
    final JTextField name = new JTextField(effect);
    name.getAccessibleContext().setAccessibleName("Name of the effect");
    details.field("Name", name);
    final FieldError error = details.error(name);
    commitName = () -> rename(effect, name.getText(), error);
    name.addActionListener(e -> commitName.run());
    name.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            rename(effect, name.getText(), error);
          }
        });

    // the sections side by side in a form of their own, so Remove stays below both
    final Form sections = new Form(420);
    sections.setBorder(BorderFactory.createEmptyBorder());
    sections.setOpaque(false);
    sections.columns();
    sections.section("In battle and on the move");
    sections.row(unitTable(game, effect));
    sections.row(
        leftAligned(
            Theme.newAddButton(
                "Add unit type", "A row for a unit type, to give it values", this::addUnitType)));
    sections.note(
        "Added to the unit's strength in a battle here; moves to enter: how many more moves it"
            + " costs.");
    sections.section("Who may enter");
    sections.field(
        "Units that may not enter",
        unitsRow(game, effect, TerritoryEffectsXml.NOT_ALLOWED, "Units that may not enter"));
    sections.field(
        "Units that may not blitz through",
        unitsRow(game, effect, TerritoryEffectsXml.NO_BLITZ, "Units that may not blitz through"));
    sections.end();
    details.row(sections);
    details.remove("Remove effect", () -> removeEffect(effect));
    details.end();
  }

  /** The title and where the effect is used at the left, its picture at the right. */
  private JPanel header(final String effect) {
    final JPanel left = new JPanel();
    left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
    final JLabel title = Theme.newTitle(effect);
    final List<String> used = territoriesWith(effect);
    final JPanel usedRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    usedRow.add(Theme.newSecondaryLabel(usedBy(used)));
    // also without territories: the map is where a click gives one the effect
    final JButton show = new JButton("Show on map");
    Theme.setTertiaryButtonStyle(show);
    show.setToolTipText("Shows them in Territories, where a click gives a territory the effect");
    show.addActionListener(
        e -> {
          commit();
          showOnMap.accept(effect);
        });
    usedRow.add(show);
    for (final JComponent part : List.of(title, usedRow)) {
      part.setAlignmentX(JComponent.LEFT_ALIGNMENT);
      left.add(part);
    }
    final JPanel header = new JPanel(new BorderLayout(Theme.GAP_L, 0));
    header.add(left, BorderLayout.CENTER);
    header.add(pictureColumn(effect), BorderLayout.EAST);
    return header;
  }

  /** "Used by 7 territories: Karelia, Belarus, Bryansk, …". */
  static String usedBy(final List<String> territories) {
    if (territories.isEmpty()) {
      return "No territory has it yet.";
    }
    return "Used by "
        + count(territories.size())
        + ": "
        + String.join(", ", territories.subList(0, Math.min(NAMES_SHOWN, territories.size())))
        + (territories.size() > NAMES_SHOWN ? ", …" : "");
  }

  private JPanel pictureColumn(final String effect) {
    final Optional<Path> file = pictureFile(effect);
    final JLabel shown =
        file.flatMap(TerritoryEffectsEditor::readPicture)
            .map(picture -> new JLabel(new ImageIcon(fitted(picture))))
            .orElseGet(
                () -> {
                  final JLabel none = new JLabel();
                  none.setBorder(
                      BorderFactory.createDashedBorder(Theme.getSecondaryTextColor(), 3, 2));
                  none.setToolTipText("No picture in the map's folder");
                  return none;
                });
    final Dimension size = new Dimension(UIScale.scale(PICTURE_SIZE), UIScale.scale(PICTURE_SIZE));
    shown.setPreferredSize(size);
    shown.setMaximumSize(size);
    shown.setAlignmentX(JComponent.CENTER_ALIGNMENT);
    final JButton choose = new JButton("Choose…");
    Theme.setSecondaryButtonStyle(choose);
    choose.setAlignmentX(JComponent.CENTER_ALIGNMENT);
    choose.setEnabled(file.isPresent());
    choose.setToolTipText(
        file.isPresent()
            ? "Copies a picture into the map's folder as the effect's, about 30 pixels"
            : "Save the map first: the picture goes into its folder.");
    choose.addActionListener(e -> file.ifPresent(this::importPicture));
    final JPanel column = new JPanel();
    column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
    column.add(shown);
    column.add(Box.createVerticalStrut(Theme.GAP_XS));
    column.add(choose);
    return column;
  }

  /** The picture within 30 px, keeping its shape. */
  private static Image fitted(final BufferedImage picture) {
    final double scale =
        Math.min(
            1,
            UIScale.scale(PICTURE_SIZE)
                / (double) Math.max(picture.getWidth(), picture.getHeight()));
    return scale == 1
        ? picture
        : picture.getScaledInstance(
            (int) Math.round(picture.getWidth() * scale),
            (int) Math.round(picture.getHeight() * scale),
            Image.SCALE_SMOOTH);
  }

  /** The picture in the file, read anew each time as an import replaces it. */
  private static Optional<BufferedImage> readPicture(final Path file) {
    try {
      return Files.exists(file)
          ? Optional.ofNullable(ImageIO.read(file.toFile()))
          : Optional.empty();
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  private Optional<Path> pictureFile(final String effect) {
    return project.getFiles().folder().map(f -> f.resolve(PICTURES).resolve(effect + ".png"));
  }

  /** Writes the chosen picture as the effect's; the one there before stays as ".bak". */
  private void importPicture(final Path file) {
    final Optional<Path> chosen =
        MapMakerWindow.chooseFileToOpen(
            page, "Picture of the effect", file.getParent().getParent(), "png", "gif", "jpg");
    if (chosen.isEmpty()) {
      return;
    }
    try {
      MapFolder.importPicture(chosen.get(), file);
    } catch (final IOException e) {
      Dialogs.warn(
          page,
          "Picture of the effect",
          "Could not take " + chosen.get().getFileName() + ".\n" + e.getMessage());
      return;
    }
    showDetails();
  }

  /** A row per unit type with its attack, defence and moves to enter, and × to clear them. */
  private JPanel unitTable(final Game game, final String effect) {
    final Map<String, String> attack =
        TerritoryEffectsXml.unitValues(game, effect, TerritoryEffectsXml.OFFENSE);
    final Map<String, String> defence =
        TerritoryEffectsXml.unitValues(game, effect, TerritoryEffectsXml.DEFENSE);
    final Map<String, String> movement =
        TerritoryEffectsXml.unitValues(game, effect, TerritoryEffectsXml.MOVEMENT_COST);
    final List<String> units = new ArrayList<>(tableUnits(game, effect));
    // in the order of the game's unit types, as Units lists them; unknown ones last
    final List<String> types = MapXml.unitTypes(game);
    units.sort(
        Comparator.comparingInt(u -> types.contains(u) ? types.indexOf(u) : Integer.MAX_VALUE));
    final JPanel table = new JPanel(new GridBagLayout());
    if (units.isEmpty()) {
      return table;
    }
    final List<String> headings = List.of("Unit", "Attack", "Defence", "Moves to enter");
    for (int column = 0; column < headings.size(); column++) {
      table.add(Theme.newSecondaryLabel(headings.get(column)), cell(column, 0));
    }
    int row = 1;
    for (final String unit : units) {
      table.add(new JLabel(unit), cell(0, row));
      table.add(bonus(effect, TerritoryEffectsXml.OFFENSE, unit, attack.get(unit)), cell(1, row));
      table.add(bonus(effect, TerritoryEffectsXml.DEFENSE, unit, defence.get(unit)), cell(2, row));
      table.add(movementCost(effect, unit, movement.get(unit)), cell(3, row));
      table.add(
          Theme.newIconButton("×", "Clear " + unit, () -> clearUnit(effect, unit)), cell(4, row));
      row++;
    }
    return table;
  }

  /** The unit types with a value in the effect, and those added to the table. */
  private Set<String> tableUnits(final Game game, final String effect) {
    final Set<String> units = new LinkedHashSet<>();
    for (final String option :
        List.of(
            TerritoryEffectsXml.OFFENSE,
            TerritoryEffectsXml.DEFENSE,
            TerritoryEffectsXml.MOVEMENT_COST)) {
      units.addAll(TerritoryEffectsXml.unitValues(game, effect, option).keySet());
    }
    units.addAll(addedRows);
    return units;
  }

  private static GridBagConstraints cell(final int column, final int row) {
    final GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = column;
    constraints.gridy = row;
    constraints.weightx = column == 0 ? 1 : 0;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(0, column == 0 ? 0 : Theme.GAP_S, Theme.GAP_XS, 0);
    return constraints;
  }

  private void addUnitType() {
    final Set<String> shown = tableUnits(project.game(), Optional.ofNullable(selected).orElse(""));
    Dialogs.pick(
            page,
            "Add unit type",
            "",
            MapXml.unitTypes(project.game()).stream().filter(u -> !shown.contains(u)).toList(),
            t -> t,
            "Add")
        .ifPresent(
            unit -> {
              addedRows.add(unit);
              showDetails();
            });
  }

  /** Attack, defence and moves to enter of the unit type to none, as one step. */
  private void clearUnit(final String effect, final String unit) {
    addedRows.remove(unit);
    project.edit(
        g -> {
          Game cleared = g;
          for (final String option :
              List.of(
                  TerritoryEffectsXml.OFFENSE,
                  TerritoryEffectsXml.DEFENSE,
                  TerritoryEffectsXml.MOVEMENT_COST)) {
            final Map<String, String> values =
                new LinkedHashMap<>(TerritoryEffectsXml.unitValues(cleared, effect, option));
            values.remove(unit);
            cleared = TerritoryEffectsXml.withUnitValues(cleared, effect, option, values);
          }
          return cleared;
        });
    // the step may change nothing, eg a row only added: the table still loses it
    showDetails();
  }

  private JSpinner bonus(
      final String effect, final String option, final String unit, @Nullable final String value) {
    final int bonus = value == null ? 0 : parseInt(value);
    final JSpinner spinner =
        Form.newNumber(
            bonus,
            -MAX_BONUS,
            MAX_BONUS,
            changed ->
                editUnitValue(effect, option, unit, changed == 0 ? null : String.valueOf(changed)));
    showSign(spinner, "+#;-#");
    final String what = TerritoryEffectsXml.OFFENSE.equals(option) ? "Attack" : "Defence";
    spinner.setToolTipText(what + " of " + unit + " here, added to its strength");
    spinner.getAccessibleContext().setAccessibleName(what + " of " + unit);
    return spinner;
  }

  private static int parseInt(final String value) {
    try {
      return Integer.parseInt(value.strip());
    } catch (final NumberFormatException e) {
      return 0;
    }
  }

  /** A number in halves, as the game takes a decimal for what a move costs. */
  private JSpinner movementCost(
      final String effect, final String unit, @Nullable final String value) {
    double cost;
    try {
      cost = value == null ? 0 : new BigDecimal(value.strip()).doubleValue();
    } catch (final NumberFormatException e) {
      cost = 0;
    }
    final JSpinner spinner =
        new JSpinner(
            new SpinnerNumberModel(
                cost, Math.min(-MAX_BONUS, cost), Math.max(MAX_BONUS, cost), 0.5));
    showSign(spinner, "+0.##;-0.##");
    spinner.addChangeListener(
        e -> {
          final BigDecimal changed =
              BigDecimal.valueOf(((Number) spinner.getValue()).doubleValue()).stripTrailingZeros();
          editUnitValue(
              effect,
              TerritoryEffectsXml.MOVEMENT_COST,
              unit,
              changed.signum() == 0 ? null : changed.toPlainString());
        });
    spinner.setToolTipText("How many more moves entering costs " + unit);
    spinner.getAccessibleContext().setAccessibleName("Moves to enter of " + unit);
    return spinner;
  }

  /**
   * Shows "+1" and "-1" in English digits, 0 as "0", in 72 px; typing takes a number without a
   * sign too.
   */
  private static void showSign(final JSpinner spinner, final String pattern) {
    final SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
    final JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, pattern);
    spinner.setEditor(editor);
    final NumberFormatter formatter =
        new NumberFormatter(new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ENGLISH))) {
          @Override
          public String valueToString(final Object value) throws ParseException {
            return value instanceof Number n && n.doubleValue() == 0 ? "0" : super.valueToString(value);
          }

          @Override
          public Object stringToValue(final String text) throws ParseException {
            final String typed = text.strip();
            return super.stringToValue(
                typed.startsWith("+") || typed.startsWith("-") ? typed : "+" + typed);
          }
        };
    formatter.setValueClass(model.getValue().getClass());
    formatter.setMinimum(model.getMinimum());
    formatter.setMaximum(model.getMaximum());
    editor.getTextField().setFormatterFactory(new DefaultFormatterFactory(formatter));
    editor.getTextField().setValue(model.getValue());
    spinner.setPreferredSize(
        new Dimension(UIScale.scale(SPINNER_WIDTH), spinner.getPreferredSize().height));
  }

  /** Sets the unit type's value in the option, or takes it out for null. */
  private void editUnitValue(
      final String effect, final String option, final String unit, @Nullable final String value) {
    edit(
        g -> {
          final Map<String, String> values =
              new LinkedHashMap<>(TerritoryEffectsXml.unitValues(g, effect, option));
          if (value == null) {
            values.remove(unit);
          } else {
            values.put(unit, value);
          }
          return TerritoryEffectsXml.withUnitValues(g, effect, option, values);
        });
  }

  private JPanel unitsRow(
      final Game game, final String effect, final String option, final String title) {
    final List<String> units = TerritoryEffectsXml.units(game, effect, option);
    final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    row.add(
        new JLabel(
            "<html>"
                + SearchList.escape(units.isEmpty() ? "None" : String.join(", ", units))
                + "</html>"),
        BorderLayout.CENTER);
    final JButton choose = new JButton("Choose…");
    Theme.setSecondaryButtonStyle(choose);
    choose.setToolTipText("Choose the unit types: " + title);
    choose.addActionListener(
        e ->
            Dialogs.pickMany(page, title, MapXml.unitTypes(game), units, "Choose")
                .ifPresent(
                    chosen ->
                        project.edit(
                            g -> TerritoryEffectsXml.withUnits(g, effect, option, chosen))));
    row.add(choose, BorderLayout.EAST);
    return row;
  }

  private static JPanel leftAligned(final JComponent part) {
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    row.add(part);
    return row;
  }

  /** Renames to the typed name; a refused one stays typed, with the reason below the field. */
  private void rename(final String old, final String typed, final FieldError error) {
    final String renamed = typed.strip();
    // after the rename the old field may lose focus too: nothing to do then
    if (renamed.equals(old) || !TerritoryEffectsXml.effects(project.game()).contains(old)) {
      error.setProblem(Optional.empty());
      return;
    }
    if (!isFreeName(renamed)) {
      error.setProblem(Optional.of(NAME_PROBLEM));
      return;
    }
    error.setProblem(Optional.empty());
    if (refusedAsNamed(old, "renamed", old + "\n" + renamed)) {
      return;
    }
    selected = renamed;
    project.edit(g -> TerritoryEffectsXml.withRenamedEffect(g, old, renamed));
    movePictures(old, renamed);
    list.choose(renamed);
  }

  /**
   * Moves the effect's pictures, eg territoryEffects/forest.png and forest_large.png, to the new
   * name; both first versions stay as ".bak", and a picture the new name has already stays.
   */
  // shortcut: Undo of the rename leaves the pictures under the new name; move them on undo when
  // map makers trip over it
  private void movePictures(final String old, final String renamed) {
    final Optional<Path> pictures = project.getFiles().folder().map(f -> f.resolve(PICTURES));
    if (pictures.isEmpty()) {
      return;
    }
    for (final String ending : List.of(".png", "_large.png")) {
      final Path from = pictures.get().resolve(old + ending);
      final Path to = pictures.get().resolve(renamed + ending);
      if (Files.exists(from) && !Files.exists(to)) {
        try {
          MapFolder.copyInto(from, to);
          MapFolder.remove(from);
        } catch (final IOException e) {
          Dialogs.warn(
              page,
              "Picture of the effect",
              "Could not move " + from.getFileName() + " to " + to.getFileName() + ".\n"
                  + e.getMessage());
        }
      }
    }
  }

  private void removeEffect(final String effect) {
    if (!refusedAsNamed(effect, "removed", "")) {
      project.edit(g -> TerritoryEffectsXml.withoutEffect(g, effect));
    }
  }

  /**
   * Tells where else the effect is named, eg a trigger, which must change first; whether so. The
   * same refusal as the last, by its key, is not told again.
   */
  private boolean refusedAsNamed(final String effect, final String done, final String key) {
    final List<String> named = TerritoryEffectsXml.whereNamed(project.game(), effect);
    if (named.isEmpty()) {
      return false;
    }
    if (key.isEmpty() || !key.equals(refusedRename)) {
      refusedRename = key;
      final int shown = Math.min(6, named.size());
      Dialogs.refuseNamed(
          page,
          effect + " can't be " + done + " yet",
          "The game would not load without it.\nThese still name it:\n• "
              + String.join("\n• ", named.subList(0, shown))
              + (named.size() > shown ? "\n… and " + (named.size() - shown) + " more" : ""),
          named);
    }
    return true;
  }

  /** A change from the details, which keep their fields instead of being built again. */
  private void edit(final UnaryOperator<Game> change) {
    editingHere = true;
    try {
      project.edit(change);
    } finally {
      editingHere = false;
    }
  }
}
