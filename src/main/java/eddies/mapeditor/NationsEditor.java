package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize;
import org.triplea.map.data.elements.Initialize.UnitInitialize;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.map.data.elements.Production.Rule.Result;
import org.triplea.map.data.elements.PlayerList.Player;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapFolder.UnitPictures;

/**
 * The nations: adding and removing them, their color, alliance, the PUs they start with, what
 * they own at the start, who plays them and their own rules (playerAttachment).
 */
final class NationsEditor implements Editor {
  private static final String RETAIN_CAPITAL = "retainCapitalNumber";
  private static final String RETAIN_CAPITAL_PRODUCE = "retainCapitalProduceNumber";
  private static final String DESTROYS_PUS = "destroysPUs";
  private static final String IMMUNE_TO_BLOCKADE = "immuneToBlockade";
  private static final String GIVE_UNIT_CONTROL = "giveUnitControl";
  private static final String GIVE_UNIT_CONTROL_EVERYWHERE = "giveUnitControlInAllTerritories";
  private static final String SHARE_TECHNOLOGY = "shareTechnology";
  private static final String HELP_PAY_TECH_COST = "helpPayTechCost";
  private static final List<String> COMMON_RULES =
      List.of(
          RETAIN_CAPITAL,
          RETAIN_CAPITAL_PRODUCE,
          DESTROYS_PUS,
          IMMUNE_TO_BLOCKADE,
          GIVE_UNIT_CONTROL,
          GIVE_UNIT_CONTROL_EVERYWHERE,
          SHARE_TECHNOLOGY,
          HELP_PAY_TECH_COST);
  // the player list's defaultType: none is a person, as the game reads it
  private static final List<String> PLAYED_BY = List.of("", "AI", "DoesNothing");
  private static final Icon BLANK =
      new ImageIcon(new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB));
  private static final List<String> PLAYED_BY_LABELS =
      List.of("A person", "The computer", "Nobody: it does nothing");

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final SearchList list;
  private final Form inspector = new Form(280);
  private @Nullable String selected;
  private boolean editingHere;

  NationsEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
            "Search nation",
            () -> MapXml.nations(project.game()),
            nation -> allianceLine(project.game(), nation),
            this::choose);
    final JComponent inspectorScroll = SwingComponents.newJScrollPane(inspector);
    final JButton add = new JButton("Add nation...");
    add.addActionListener(e -> addNation());
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(add, BorderLayout.SOUTH);
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

  /**
   * The nation's alliances for the list's second line; "no alliance" when it plays alone, as an
   * alliance of its own name is.
   */
  static String allianceLine(final Game game, final String nation) {
    final List<String> alliances = MapXml.alliances(game, nation);
    return alliances.isEmpty() || alliances.equals(List.of(nation))
        ? "no alliance"
        : String.join(", ", alliances);
  }

  @Override
  public String title() {
    return "Nations";
  }

  @Override
  public Glyph glyph() {
    return Glyph.NATIONS;
  }

  @Override
  public String hint() {
    return "The nations that play. Add them here; choose one to change its color, alliance, the"
        + " PUs it starts with, who plays it, its own rules and its unit pictures. What it owns"
        + " is set in Territories.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  private void choose(final String nation) {
    selected = nation;
    showInspector();
  }

  private void addNation() {
    final String name =
        JOptionPane.showInputDialog(
            page, "Name of the new nation:", "Add nation", JOptionPane.PLAIN_MESSAGE);
    if (name == null) {
      return;
    }
    final String nation = name.strip();
    if (project.addNation(nation)) {
      choose(nation);
      list.choose(nation);
    } else {
      JOptionPane.showMessageDialog(
          page,
          "<html>The name must not be empty or be a territory's, a nation's or a unit's, and"
              + " must not hold : &lt; or (.</html>",
          "Add nation",
          JOptionPane.WARNING_MESSAGE);
    }
  }

  private void showInspector() {
    inspector.clear();
    final Game game = project.game();
    if (selected == null || !MapXml.nations(game).contains(selected)) {
      inspector
          .note(
              MapXml.nations(game).isEmpty()
                  ? "No nations yet. Add the nations that play with Add nation."
                  : MapXml.nations(game).size() + " nations play this game.")
          .end();
      return;
    }
    final String nation = selected;
    inspector.title(nation);
    final JPanel swatch = new JPanel();
    swatch.setPreferredSize(new Dimension(48, 20));
    swatch.setBackground(project.nationColor(nation));
    swatch.setBorder(
        BorderFactory.createLineBorder(Theme.getSwatchOutlineColor(project.nationColor(nation))));
    final JButton color = new JButton("Change color...");
    color.addActionListener(
        e ->
            Optional.ofNullable(
                    JColorChooser.showDialog(
                        page, "Color of " + nation, project.nationColor(nation)))
                .ifPresent(chosen -> project.setNationColor(nation, chosen)));
    final JPanel colorRow = new JPanel(new BorderLayout(8, 0));
    colorRow.add(swatch, BorderLayout.WEST);
    colorRow.add(new JLabel("Its color on the map"), BorderLayout.CENTER);
    colorRow.add(color, BorderLayout.EAST);
    inspector.row(colorRow);
    final JButton remove = new JButton("Remove nation");
    remove.addActionListener(e -> removeNation(nation));
    final JPanel removeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    removeRow.add(remove);
    inspector.row(removeRow);

    inspector.section("Alliance");
    final JComboBox<String> alliance = new JComboBox<>();
    alliance.setEditable(true);
    MapXml.allAlliances(game).forEach(alliance::addItem);
    final List<String> current = MapXml.alliances(game, nation);
    alliance.setSelectedItem(current.isEmpty() ? "" : current.get(0));
    alliance.addActionListener(
        e -> {
          final String chosen = String.valueOf(alliance.getSelectedItem());
          if (!List.of(chosen).equals(MapXml.alliances(project.game(), nation))) {
            project.edit(g -> MapXml.withAlliance(g, nation, chosen));
          }
        });
    inspector.field(
        "Plays in the alliance",
        "Nations of one alliance fight together. Type a name for a new alliance.",
        alliance);

    inspector.section("Start");
    inspector.field(
        "PUs at the start",
        Form.newNumber(
            MapXml.startingResource(game, nation, MapXml.PUS),
            9999,
            value -> {
              editingHere = true;
              try {
                project.edit(g -> MapXml.withStartingResource(g, nation, MapXml.PUS, value));
              } finally {
                editingHere = false;
              }
            }));
    final List<String> owned =
        MapXml.territories(game).stream()
            .filter(t -> MapXml.owner(game, t).filter(nation::equals).isPresent())
            .toList();
    final int income = owned.stream().mapToInt(t -> MapXml.production(game, t)).sum();
    inspector.note(
        "Owns " + owned.size() + " territories at the start, worth " + income + " PUs per turn.");
    owned.stream()
        .filter(t -> MapXml.isCapital(game, t))
        .findFirst()
        .ifPresentOrElse(
            capital -> inspector.note("Capital: " + capital),
            () -> inspector.note("No capital yet: mark one in Territories."));
    showHowItPlays(nation);
    showRules(nation);
    showUnitPictures(nation);
    inspector.end();
  }

  /** Who plays the nation by default, and whether a game may leave it out or hide it. */
  private void showHowItPlays(final String nation) {
    inspector.section(
        "How it plays",
        "Who plays it when a game starts, and whether players may leave it out. A hidden nation"
            + " is not shown in the list of players, eg the neutrals.");
    final Player player = RulesXml.player(project.game(), nation);
    final JComboBox<String> playedBy = new JComboBox<>(PLAYED_BY_LABELS.toArray(String[]::new));
    final String type = Optional.ofNullable(player.getDefaultType()).orElse("");
    if (!PLAYED_BY.contains(type)) {
      // a type this editor does not know stays choosable, so nothing changes by itself
      playedBy.addItem(type);
    }
    playedBy.setSelectedIndex(
        PLAYED_BY.contains(type) ? PLAYED_BY.indexOf(type) : PLAYED_BY.size());
    playedBy.addActionListener(
        e -> {
          final int index = playedBy.getSelectedIndex();
          final String chosen = index < PLAYED_BY.size() ? PLAYED_BY.get(index) : type;
          if (!chosen.equals(type)) {
            project.edit(
                g ->
                    RulesXml.withPlayer(
                        g, nation, b -> b.defaultType(chosen.isEmpty() ? null : chosen)));
          }
        });
    inspector.field("Played by default", playedBy);
    inspector.row(
        playerFlag(
            nation,
            "Optional: players may leave it out",
            player.getOptional(),
            Player.PlayerBuilder::optional));
    inspector.row(
        playerFlag(
            nation,
            "Can be switched off at the start",
            player.getCanBeDisabled(),
            Player.PlayerBuilder::canBeDisabled));
    inspector.row(
        playerFlag(
            nation,
            "Hidden from the list of players",
            player.getIsHidden(),
            Player.PlayerBuilder::isHidden));
  }

  /** A check box of the player list; unchecked writes false only where the map gave it. */
  private JCheckBox playerFlag(
      final String nation,
      final String label,
      @Nullable final Boolean value,
      final BiFunction<Player.PlayerBuilder, Boolean, Player.PlayerBuilder> set) {
    final JCheckBox box = new JCheckBox(label, Boolean.TRUE.equals(value));
    box.addActionListener(
        e -> {
          final Boolean chosen = box.isSelected() ? Boolean.TRUE : value == null ? null : false;
          project.edit(g -> RulesXml.withPlayer(g, nation, b -> set.apply(b, chosen)));
        });
    return box;
  }

  /** The common options of the nation's playerAttachment in plain words, the others below. */
  private void showRules(final String nation) {
    inspector.section(
        "Rules for this nation",
        "Rules that only this nation has. Left as they are, the game's defaults apply.");
    inspector.field(
        "Capitals it must hold to keep its PUs",
        "With fewer of its capitals, an enemy taking one takes its PUs. 1 when not set.",
        playerNumber(nation, RETAIN_CAPITAL));
    inspector.field(
        "Capitals it must hold to collect and build",
        "With fewer of its capitals it gets no income and cannot buy units. 1 when not set.",
        playerNumber(nation, RETAIN_CAPITAL_PRODUCE));
    inspector.row(
        Help.with(
            playerCheck(nation, DESTROYS_PUS, "Its PUs are lost when its capital falls"),
            "The enemy that takes its capital does not get its PUs: they are gone."));
    inspector.row(
        Help.with(
            playerCheck(nation, IMMUNE_TO_BLOCKADE, "Immune to blockades"),
            "Enemy ships by its land do not cut its income."));
    inspector.row(
        playerNations(
            nation,
            GIVE_UNIT_CONTROL,
            "Hands its units over to",
            "At the end of its turn its units in land these nations own become theirs."));
    inspector.row(
        playerCheck(
            nation, GIVE_UNIT_CONTROL_EVERYWHERE, "Hands its units over in all territories"));
    inspector.row(
        playerNations(
            nation,
            SHARE_TECHNOLOGY,
            "Shares its technology with",
            "Technology it finds is given to these nations too."));
    inspector.row(
        playerNations(
            nation,
            HELP_PAY_TECH_COST,
            "May help pay its research",
            "These nations may give PUs to its research."));
    final List<Option> others =
        RulesXml.playerOptions(project.game(), nation).stream()
            .filter(o -> !COMMON_RULES.contains(o.getName()))
            .toList();
    if (!others.isEmpty()) {
      inspector.section(
          "Other rules",
          "Rules of this nation by TripleA's names, eg placementLimit. Change or remove them in"
              + " Attachments, in its playerAttachment.");
      final JPanel table = new JPanel(new GridLayout(0, 2, 12, 4));
      others.forEach(
          o -> {
            table.add(new JLabel(o.getName()));
            table.add(new JLabel(Objects.toString(o.getValue(), "")));
          });
      inspector.row(table);
    }
    final JButton more = new JButton("More rules...");
    more.setToolTipText(
        "Adds another rule, eg a placementLimit; change or remove it in Attachments");
    more.addActionListener(e -> addRule(nation));
    final JPanel moreRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    moreRow.add(more);
    inspector.row(moreRow);
  }

  /** A number of the playerAttachment whose default is 1; 1 takes it out. */
  private JSpinner playerNumber(final String nation, final String option) {
    final int value =
        RulesXml.playerOption(project.game(), nation, option)
            .flatMap(NationsEditor::parseInt)
            .orElse(1);
    return Form.newNumber(
        value,
        99,
        chosen -> {
          editingHere = true;
          try {
            project.edit(
                g ->
                    chosen == 1
                        ? RulesXml.withoutPlayerOption(g, nation, option)
                        : RulesXml.withPlayerOption(g, nation, option, String.valueOf(chosen)));
          } finally {
            editingHere = false;
          }
        });
  }

  private static Optional<Integer> parseInt(final String value) {
    try {
      return Optional.of(Integer.parseInt(value.strip()));
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }
  }

  private JCheckBox playerCheck(final String nation, final String option, final String label) {
    final JCheckBox box =
        new JCheckBox(
            label,
            RulesXml.playerOption(project.game(), nation, option)
                .map(Boolean::parseBoolean)
                .orElse(false));
    box.addActionListener(
        e ->
            project.edit(
                g ->
                    box.isSelected()
                        ? RulesXml.withPlayerOption(g, nation, option, "true")
                        : RulesXml.withoutPlayerOption(g, nation, option)));
    return box;
  }

  /** The nations an option lists, with a button to choose them. */
  private JComponent playerNations(
      final String nation, final String option, final String label, final String help) {
    final List<String> chosen = RulesXml.playerNames(project.game(), nation, option);
    final JButton choose = new JButton("Choose...");
    choose.setToolTipText("Choose the nations");
    choose.addActionListener(
        e ->
            chooseNations(
                    page,
                    label,
                    MapXml.nations(project.game()).stream()
                        .filter(n -> !n.equals(nation))
                        .toList(),
                    chosen)
                .ifPresent(
                    names ->
                        project.edit(g -> RulesXml.withPlayerNames(g, nation, option, names))));
    final JPanel row = new JPanel(new BorderLayout(8, 4));
    row.add(Help.with(new JLabel(label), help), BorderLayout.NORTH);
    row.add(
        new JLabel(chosen.isEmpty() ? "None" : String.join(", ", chosen)), BorderLayout.CENTER);
    row.add(choose, BorderLayout.EAST);
    return row;
  }

  /**
   * Asks which of the nations to take, those chosen before checked; empty when cancelled. Shared by
   * the tools whose options list nations.
   */
  static Optional<List<String>> chooseNations(
      final JComponent parent,
      final String title,
      final List<String> nations,
      final List<String> chosen) {
    final JPanel boxes = new JPanel(new GridLayout(0, 3, 8, 4));
    final List<JCheckBox> checks =
        nations.stream().map(n -> new JCheckBox(n, chosen.contains(n))).toList();
    checks.forEach(boxes::add);
    if (!Panels.ask(parent, boxes, title)) {
      return Optional.empty();
    }
    return Optional.of(
        checks.stream().filter(JCheckBox::isSelected).map(JCheckBox::getText).toList());
  }

  /** Adds another option of the nation's playerAttachment, eg a second placementLimit. */
  private void addRule(final String nation) {
    final JComboBox<String> name = new JComboBox<>();
    name.setEditable(true);
    AttachmentsXml.knownOptions(project.game(), "PlayerAttachment").stream()
        .filter(o -> !COMMON_RULES.contains(o))
        .forEach(name::addItem);
    final JTextField value = new JTextField(24);
    final JPanel ask = new JPanel(new GridLayout(0, 1, 0, 4));
    ask.add(new JLabel("Rule"));
    ask.add(name);
    ask.add(new JLabel("Value, eg 2:territory:infantry for a placementLimit"));
    ask.add(value);
    if (!Panels.ask(page, ask, "More rules for " + nation)) {
      return;
    }
    final String option = String.valueOf(name.getSelectedItem()).strip();
    if (!option.isEmpty() && !value.getText().isBlank()) {
      project.edit(
          g -> RulesXml.withPlayerOptionAdded(g, nation, option, value.getText().strip()));
    }
  }

  /** The nation's units as they look, and buttons to import pictures for them or take them back. */
  private void showUnitPictures(final String nation) {
    inspector.section(
        "Unit pictures",
        "How this nation's units look. Import a folder of pictures, eg another map's"
            + " units/<nation> folder: each picture named like a unit type, eg infantry.png,"
            + " becomes this nation's picture of it. Saving writes them into the map's units"
            + " folder; the first versions stay as .bak. Use the game's pictures takes them back.");
    final List<String> units = unitsOf(project.game(), nation);
    if (units.size() < MapXml.unitTypes(project.game()).size()) {
      inspector.note("The " + units.size() + " unit types it can buy or has at the start.");
    }
    // rows of a few, as a flowing row would not wrap in the form
    final JPanel pictures = new JPanel(new GridLayout(0, 5, 4, 8));
    final Color color = project.nationColor(nation);
    for (final String unit : units) {
      final JLabel picture = new JLabel(unit);
      // a blank of the same size where a picture is missing, so the names stay in line
      picture.setIcon(
          project.unitPicture(nation, color, unit).map(NationsEditor::icon).orElse(BLANK));
      picture.setHorizontalTextPosition(SwingConstants.CENTER);
      picture.setVerticalTextPosition(SwingConstants.BOTTOM);
      picture.setHorizontalAlignment(SwingConstants.CENTER);
      picture.setToolTipText(unit);
      pictures.add(picture);
    }
    inspector.row(pictures);
    final JButton importPictures = new JButton("Import unit pictures...");
    importPictures.addActionListener(e -> importUnitPictures(nation));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    buttons.add(importPictures);
    if (project.getFiles().nationUnitPictures().keySet().stream()
        .anyMatch(key -> key.nation().equals(nation))) {
      final JButton takeBack = new JButton("Use the game's pictures");
      takeBack.addActionListener(e -> project.setNationUnitPictures(nation, Map.of()));
      buttons.add(takeBack);
    }
    inspector.row(buttons);
  }

  /**
   * The unit types the nation can buy (its production frontier) or has at the start; all of them
   * when it has none, eg in a new map. A big map has hundreds, of every nation.
   */
  private static List<String> unitsOf(final Game game, final String nation) {
    final Set<String> rules =
        ProductionXml.frontierOf(game, nation)
            .map(f -> Set.copyOf(ProductionXml.rulesIn(game, f)))
            .orElse(Set.of());
    final Set<String> its = new HashSet<>();
    ProductionXml.rules(game).stream()
        .filter(r -> rules.contains(r.getName()))
        .flatMap(r -> Optional.ofNullable(r.getResults()).orElse(List.of()).stream())
        .map(Result::getResourceOrUnit)
        .forEach(its::add);
    Optional.ofNullable(game.getInitialize())
        .map(Initialize::getUnitInitialize)
        .map(UnitInitialize::getUnitPlacements)
        .orElse(List.of())
        .stream()
        .filter(u -> nation.equals(u.getOwner()))
        .map(UnitPlacement::getUnitType)
        .forEach(its::add);
    final List<String> all = MapXml.unitTypes(game);
    final List<String> own = all.stream().filter(its::contains).toList();
    return own.isEmpty() ? all : own;
  }

  private static Icon icon(final Image image) {
    // a big picture, eg of a map with large units, stays a unit's size here
    final int width = image.getWidth(null);
    final int height = image.getHeight(null);
    final double scale = Math.min(1, 48.0 / Math.max(1, Math.max(width, height)));
    return new ImageIcon(
        scale == 1
            ? image
            : image.getScaledInstance(
                (int) (width * scale), (int) (height * scale), Image.SCALE_SMOOTH));
  }

  /** Asks for a folder of unit pictures and takes those named like unit types, as one step. */
  private void importUnitPictures(final String nation) {
    final Optional<Path> folder =
        MapMakerWindow.chooseFolder(
            page,
            "Choose a folder with unit pictures for " + nation,
            project
                .getFolder()
                .map(f -> f.resolve("units"))
                .filter(Files::isDirectory)
                .orElseGet(() -> Path.of(System.getProperty("user.home"))));
    if (folder.isEmpty()) {
      return;
    }
    final UnitPictures read;
    try {
      read = MapFolder.readUnitPictures(folder.get(), MapXml.unitTypes(project.game()));
    } catch (final IOException e) {
      JOptionPane.showMessageDialog(
          page,
          "Could not read " + folder.get() + ":\n" + e.getMessage(),
          "Import unit pictures",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    if (!read.byUnit().isEmpty()) {
      project.setNationUnitPictures(nation, read.byUnit());
    }
    JOptionPane.showMessageDialog(
        page,
        read.byUnit().isEmpty()
            ? "No picture there is named like a unit type of this game, eg infantry.png."
            : read.byUnit().size()
                + " unit pictures imported for "
                + nation
                + (read.unknown().isEmpty()
                    ? "."
                    : ".\nLeft out, named like no unit type or no picture: "
                        + String.join(", ", read.unknown())),
        "Import unit pictures",
        read.byUnit().isEmpty() ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
  }

  private void removeNation(final String nation) {
    final int answer =
        JOptionPane.showConfirmDialog(
            page,
            "Remove "
                + nation
                + " with its turn, the territories it owns at the start and its units?"
                + "\nUndo takes it back.",
            "Remove nation",
            JOptionPane.OK_CANCEL_OPTION);
    if (answer != JOptionPane.OK_OPTION) {
      return;
    }
    final List<String> named = project.removeNation(nation);
    if (!named.isEmpty()) {
      JOptionPane.showMessageDialog(
          page,
          nation
              + " stays: the game would not load without it, as these still name it:\n"
              + String.join("\n", named.subList(0, Math.min(10, named.size())))
              + (named.size() > 10 ? "\nand " + (named.size() - 10) + " more" : "")
              + "\nChange them in Attachments first.",
          "Remove nation",
          JOptionPane.WARNING_MESSAGE);
    }
  }
}
