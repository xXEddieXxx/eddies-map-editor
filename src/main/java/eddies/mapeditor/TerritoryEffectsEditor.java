package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The territory effects, eg forest or mountain: adding, renaming and removing them, what each does
 * to units in battle and on the move, and its picture. Territories gives them to territories.
 */
final class TerritoryEffectsEditor implements Editor {
  // the game draws an effect from territoryEffects/<name>.png in the map's folder
  private static final String PICTURES = "territoryEffects";
  private static final int MAX_BONUS = 12;

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final SearchList list;
  private final Form inspector = new Form(300);
  private @Nullable String selected;
  // unit types added to the table, shown until they get a value
  private final Set<String> addedRows = new LinkedHashSet<>();
  private boolean editingHere;
  private @Nullable Game countedIn;
  private Map<String, Long> counts = Map.of();

  TerritoryEffectsEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
            "Search effect",
            () -> TerritoryEffectsXml.effects(project.game()),
            this::detail,
            this::choose);
    final JButton add = new JButton("Add effect...");
    add.setToolTipText("A new effect, eg forest; give it to territories in Territories");
    add.addActionListener(e -> addEffect());
    final JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    addRow.add(add);
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(addRow, BorderLayout.SOUTH);
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
    return "Effects";
  }

  @Override
  public Glyph glyph() {
    return Glyph.EFFECTS;
  }

  @Override
  public String hint() {
    return "Territory effects, eg forest or mountain: what they do to units in battle and on the"
        + " move, and the picture the game shows for them. Give them to territories in"
        + " Territories.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  /** How many territories have the effect; counted once per game, as each row asks. */
  private String detail(final String effect) {
    if (countedIn != project.game()) {
      countedIn = project.game();
      counts = TerritoryEffectsXml.territoryCounts(countedIn);
    }
    final long territories = counts.getOrDefault(effect, 0L);
    return territories + (territories == 1 ? " territory" : " territories");
  }

  private void choose(final String effect) {
    if (!effect.equals(selected)) {
      addedRows.clear();
    }
    selected = effect;
    showInspector();
  }

  private void addEffect() {
    final String name =
        Optional.ofNullable(
                JOptionPane.showInputDialog(
                    page, "Name of the new effect:", "Add effect", JOptionPane.PLAIN_MESSAGE))
            .map(String::strip)
            .orElse("");
    if (name.isEmpty()) {
      return;
    }
    if (!isFreeName(name)) {
      refuseName("Add effect");
      return;
    }
    project.edit(g -> TerritoryEffectsXml.withEffect(g, name));
    choose(name);
    list.choose(name);
  }

  /** Not another effect's, territory's, nation's or unit's: options name them all alike. */
  private boolean isFreeName(final String name) {
    return project.isFreeName(name) && !TerritoryEffectsXml.effects(project.game()).contains(name);
  }

  private void refuseName(final String title) {
    JOptionPane.showMessageDialog(
        page,
        "<html>The name must not be empty, hold : &lt; or (, or be another effect's, territory's,"
            + " nation's or unit's.</html>",
        title,
        JOptionPane.WARNING_MESSAGE);
  }

  private void showInspector() {
    inspector.clear();
    final Game game = project.game();
    if (selected == null || !TerritoryEffectsXml.effects(game).contains(selected)) {
      inspector
          .note(
              TerritoryEffectsXml.effects(game).isEmpty()
                  ? "No effects yet. Add one, eg forest, then give it to territories in"
                      + " Territories."
                  : "Choose an effect to change it.")
          .end();
      return;
    }
    final String effect = selected;
    inspector.title(effect);
    final JTextField name = new JTextField(effect);
    final JButton rename = new JButton("Rename");
    rename.setToolTipText("Gives the effect the name typed, also in the territories that have it");
    name.getAccessibleContext().setAccessibleName("Name of the effect");
    name.addActionListener(e -> rename(effect, name.getText()));
    rename.addActionListener(e -> rename(effect, name.getText()));
    final JPanel naming = new JPanel(new BorderLayout(6, 0));
    naming.add(name, BorderLayout.CENTER);
    naming.add(rename, BorderLayout.EAST);
    inspector.field("Name", naming);
    inspector.field(
        "Picture",
        "What the game shows for the effect on the map and in battles: territoryEffects/"
            + effect
            + ".png in the map's folder, about 30 pixels.",
        pictureRow(effect));

    inspector.section(
        "Per unit type",
        "Attack and defence: added to the unit's strength in a battle here, eg -1 for armour in a"
            + " forest. Movement: added to what entering costs, eg 1 for one move more. 0 takes"
            + " the unit out.");
    inspector.row(unitTable(game, effect));

    inspector.section("Movement");
    inspector.field(
        "Units that may not enter",
        unitsRow(game, effect, TerritoryEffectsXml.NOT_ALLOWED, "Units that may not enter"));
    inspector.field(
        "Units that may not blitz through",
        unitsRow(game, effect, TerritoryEffectsXml.NO_BLITZ, "Units that may not blitz through"));

    final JButton remove = new JButton("Remove effect");
    remove.setToolTipText("Removes it, also from the territories that have it. Undo takes it back");
    remove.addActionListener(e -> removeEffect(effect));
    final JPanel removeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    removeRow.add(remove);
    inspector.row(removeRow);
    inspector.end();
  }

  private JPanel pictureRow(final String effect) {
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    final Optional<Path> file = pictureFile(effect);
    final JLabel shown =
        file.flatMap(TerritoryEffectsEditor::readPicture)
            .map(picture -> new JLabel(new ImageIcon(picture)))
            .orElseGet(() -> new JLabel("None in the map's folder"));
    shown.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
    row.add(shown);
    final JButton choose = new JButton("Choose...");
    choose.setEnabled(file.isPresent());
    choose.setToolTipText(
        file.isPresent()
            ? "Copies a PNG picture into the map's folder as the effect's"
            : "Save the map first: the picture goes into its folder");
    choose.addActionListener(e -> file.ifPresent(this::importPicture));
    row.add(choose);
    return row;
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
      JOptionPane.showMessageDialog(
          page,
          "Could not take " + chosen.get().getFileName() + ":\n" + e.getMessage(),
          "Picture of the effect",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    showInspector();
  }

  /** A row per unit type with its attack, defence and movement change, and one to add a type. */
  private JPanel unitTable(final Game game, final String effect) {
    final Map<String, String> attack =
        TerritoryEffectsXml.unitValues(game, effect, TerritoryEffectsXml.OFFENSE);
    final Map<String, String> defence =
        TerritoryEffectsXml.unitValues(game, effect, TerritoryEffectsXml.DEFENSE);
    final Map<String, String> movement =
        TerritoryEffectsXml.unitValues(game, effect, TerritoryEffectsXml.MOVEMENT_COST);
    final Set<String> units = new LinkedHashSet<>(attack.keySet());
    units.addAll(defence.keySet());
    units.addAll(movement.keySet());
    units.addAll(addedRows);
    final JPanel table = new JPanel(new GridLayout(0, 4, 6, 4));
    if (!units.isEmpty()) {
      List.of("Unit", "Attack", "Defence", "Movement").forEach(h -> table.add(new JLabel(h)));
    }
    for (final String unit : units) {
      table.add(new JLabel(unit));
      table.add(bonus(effect, TerritoryEffectsXml.OFFENSE, unit, attack.get(unit)));
      table.add(bonus(effect, TerritoryEffectsXml.DEFENSE, unit, defence.get(unit)));
      table.add(movementCost(effect, unit, movement.get(unit)));
    }
    final JComboBox<String> type = new JComboBox<>();
    MapXml.unitTypes(game).stream().filter(u -> !units.contains(u)).forEach(type::addItem);
    final JButton add = new JButton("Add");
    add.setToolTipText("A row for the unit type, to give it values");
    add.addActionListener(
        e -> {
          if (type.getSelectedItem() instanceof String unit) {
            addedRows.add(unit);
            showInspector();
          }
        });
    final JPanel column = new JPanel(new BorderLayout(0, 6));
    column.add(table, BorderLayout.CENTER);
    final JPanel addRow = new JPanel(new BorderLayout(6, 0));
    addRow.add(type, BorderLayout.CENTER);
    addRow.add(add, BorderLayout.EAST);
    column.add(addRow, BorderLayout.SOUTH);
    return column;
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
    spinner.addChangeListener(
        e -> {
          final BigDecimal changed =
              BigDecimal.valueOf((Double) spinner.getValue()).stripTrailingZeros();
          editUnitValue(
              effect,
              TerritoryEffectsXml.MOVEMENT_COST,
              unit,
              changed.signum() == 0 ? null : changed.toPlainString());
        });
    spinner.setToolTipText("What entering costs " + unit + " more, in moves");
    spinner.getAccessibleContext().setAccessibleName("Movement cost of " + unit);
    return spinner;
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
    final JPanel row = new JPanel(new BorderLayout(8, 0));
    row.add(
        new JLabel(
            "<html>"
                + SearchList.escape(units.isEmpty() ? "None" : String.join(", ", units))
                + "</html>"),
        BorderLayout.CENTER);
    final JButton choose = new JButton("Choose...");
    choose.setToolTipText("Choose the unit types: " + title);
    choose.addActionListener(
        e ->
            Panels.pick(page, title, MapXml.unitTypes(game), units)
                .ifPresent(
                    chosen ->
                        project.edit(
                            g -> TerritoryEffectsXml.withUnits(g, effect, option, chosen))));
    row.add(choose, BorderLayout.EAST);
    return row;
  }

  private void rename(final String old, final String typed) {
    final String renamed = typed.strip();
    if (renamed.equals(old)) {
      return;
    }
    if (!isFreeName(renamed)) {
      refuseName("Rename");
      return;
    }
    if (!refusedAsNamed(old, "Rename")) {
      selected = renamed;
      project.edit(g -> TerritoryEffectsXml.withRenamedEffect(g, old, renamed));
      movePictures(old, renamed);
      list.choose(renamed);
      showInspector();
    }
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
          JOptionPane.showMessageDialog(
              page,
              "Could not move " + from.getFileName() + " to " + to.getFileName() + ": "
                  + e.getMessage(),
              "Rename",
              JOptionPane.WARNING_MESSAGE);
        }
      }
    }
  }

  private void removeEffect(final String effect) {
    if (!refusedAsNamed(effect, "Remove effect")) {
      project.edit(g -> TerritoryEffectsXml.withoutEffect(g, effect));
    }
  }

  /** Tells where else the effect is named, eg a trigger, which must change first; whether so. */
  private boolean refusedAsNamed(final String effect, final String title) {
    final List<String> named = TerritoryEffectsXml.whereNamed(project.game(), effect);
    if (named.isEmpty()) {
      return false;
    }
    JOptionPane.showMessageDialog(
        page,
        "<html>"
            + SearchList.escape(effect)
            + " is named here, change it there first:<br>"
            + String.join("<br>", named.stream().map(SearchList::escape).toList())
            + "</html>",
        title,
        JOptionPane.WARNING_MESSAGE);
    return true;
  }

  /** A change from the inspector, which keeps its fields instead of being built again. */
  private void edit(final UnaryOperator<Game> change) {
    editingHere = true;
    try {
      project.edit(change);
    } finally {
      editingHere = false;
    }
  }
}
