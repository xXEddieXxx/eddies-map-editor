package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PlayerList.Player;
import org.triplea.swing.SwingComponents;
import org.triplea.swing.WrapLayout;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapFolder.UnitPictures;

/**
 * The nations: adding and removing them, their color, alliance, the PUs they start with, who
 * plays them, their unit pictures and their own rules (playerAttachment, and rulesAttachment
 * for placing and moving).
 */
final class NationsEditor implements Editor {
  private static final String NO_ALLIANCE = "No alliance";
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
  // the nation's rulesAttachment: where and how much it places, where it moves
  private static final String MOVE_TYPE = "movementRestrictionType";
  private static final String MOVE_TERRITORIES = "movementRestrictionTerritories";
  private static final String MOVE_ONLY_INTO = "allowed";
  private static final String MOVE_NOT_INTO = "disallowed";
  private static final String FREE_UNITS = "productionPerXTerritories";
  // the rules of the rulesAttachment that More options shows; others stay, shown in Attachments
  private static final List<String> NATION_RULES =
      List.of(
          "placementAnyTerritory",
          "placementAnySeaZone",
          "placementCapturedTerritory",
          "placementInCapitalRestricted",
          "unlimitedProduction",
          "maxPlacePerTerritory",
          "placementPerTerritory",
          FREE_UNITS,
          MOVE_TYPE,
          MOVE_TERRITORIES,
          "dominatingFirstRoundAttack",
          "negateDominatingFirstRoundAttack");
  private static final List<String> MOVE_LABELS =
      List.of("May move anywhere", "May move only into", "May not move into");
  // the player list's defaultType: none is a person, as the game reads it
  private static final List<String> PLAYED_BY = List.of("", "AI", "DoesNothing");
  private static final List<String> PLAYED_BY_LABELS =
      List.of("A person", "The computer", "Nobody (it does nothing)");
  // the rules the Add rule dialog offers first, in plain words
  private static final List<String> FIRST_RULES =
      List.of(
          "placementLimit",
          "movementLimit",
          "attackingLimit",
          "suicideAttackTargets",
          "suicideAttackResources",
          "vps",
          "captureVps",
          "captureUnitOnEnteringBy");
  private static final List<String> FIRST_RULE_LABELS =
      List.of(
          "Limit units placed per territory",
          "Limit units moving into a territory",
          "Limit units attacking a territory",
          "Units its suicide attacks may target",
          "Resources its suicide attacks cost",
          "Victory points",
          "Victory points for capturing it",
          "Who captures its units by moving in");
  // how a unit limit counts, as PlayerAttachment reads it
  private static final List<String> COUNTING = List.of("owned", "allied", "total");
  private static final List<String> COUNTING_LABELS =
      List.of("its own units", "its and allied units", "all units");
  private static final String ALL_UNITS = "All units";
  private static final int FIELD_WIDTH = 360;
  private static final int LIST_HEIGHT = 220;
  private static final Icon BLANK =
      new ImageIcon(new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB));

  private final MapProject project;
  private final MapView map;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final SearchList list;
  private final Form inspector = new Form(280);
  // the first two sections, side by side when wide enough
  private final Form start = new Form(280);
  private @Nullable String selected;
  private @Nullable JPanel headerLine;
  private boolean editingHere;
  // the rule's text typed and not yet taken, for commit()
  private @Nullable Runnable typing;

  NationsEditor(final MapProject project) {
    this(project, new MapView(project, () -> {}, () -> {}, () -> {}));
  }

  NationsEditor(final MapProject project, final MapView map) {
    this.project = project;
    this.map = map;
    list =
        new SearchList(
                "nation",
                "nations",
                () -> MapXml.nations(project.game()),
                nation -> nation,
                nation -> allianceLine(project.game(), nation),
                this::choose)
            .withAdd("Add", "Add a nation", this::addNation);
    start.setBorder(BorderFactory.createEmptyBorder());
    start.setOpaque(false);
    final JSplitPane stacked = Panels.stacked(list, SwingComponents.newJScrollPane(inspector));
    // a game has a few nations: the inspector gets the room
    stacked.setResizeWeight(0);
    stacked.setDividerLocation(LIST_HEIGHT);
    page.add(stacked, BorderLayout.CENTER);
    project.onChange(
        () -> {
          list.refresh();
          if (!editingHere) {
            showInspector();
          } else if (headerLine != null && selected != null) {
            showLine(headerLine, project.game(), selected);
          }
          showOnMap();
        });
    showInspector();
  }

  /** The nation's alliances for the list; "No alliance" when it plays alone, as in one of its name. */
  static String allianceLine(final Game game, final String nation) {
    final List<String> alliances = MapXml.alliances(game, nation);
    return playsAlone(alliances, nation) ? NO_ALLIANCE : String.join(", ", alliances);
  }

  private static boolean playsAlone(final List<String> alliances, final String nation) {
    return alliances.isEmpty() || alliances.equals(List.of(nation));
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
    return "Give each nation an alliance and its starting PUs. Who owns which land is set in"
        + " Territories.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public boolean usesMap() {
    return true;
  }

  /** The map in the nations' colors; a click on land chooses its owner. */
  @Override
  public void showing() {
    map.useFor(MapMode.OWNERS, new JPanel(), this::click);
    // the colors are made here
    map.hideNationsLink();
    markChosen();
  }

  /** A click on land chooses its owner. */
  void click(final String territory) {
    MapXml.owner(project.game(), territory)
        .ifPresent(
            owner -> {
              // a value typed and not yet taken is kept, as the form is built anew
              commit();
              choose(owner);
              list.choose(owner);
            });
  }

  /** The chosen nation's land marked, while this tool shows the map. */
  private void showOnMap() {
    if (page.isShowing()) {
      markChosen();
    }
  }

  private void markChosen() {
    map.mark(
        Optional.ofNullable(selected)
            .map(nation -> Set.copyOf(owned(project.game(), nation)))
            .orElse(Set.of()));
  }

  @Override
  public void commit() {
    if (typing != null) {
      typing.run();
    }
  }

  private void choose(final String nation) {
    selected = nation;
    showInspector();
    showOnMap();
  }

  private void addNation() {
    Dialogs.askName(page, "Add nation", "Name", "", "Add", project::nameProblem)
        .filter(project::addNation)
        .ifPresent(
            nation -> {
              choose(nation);
              list.choose(nation);
            });
  }

  private void showInspector() {
    typing = null;
    headerLine = null;
    inspector.clear();
    start.clear();
    final Game game = project.game();
    final List<String> nations = MapXml.nations(game);
    if (nations.isEmpty()) {
      inspector.row(
          Panels.empty(
              Glyph.NATIONS,
              "No nations yet",
              "Nations are the sides that play. A game needs at least two.",
              "Add nation",
              this::addNation));
      inspector.end();
      return;
    }
    if (selected == null || !nations.contains(selected)) {
      selected = nations.getFirst();
      list.choose(selected);
    }
    final String nation = selected;
    showHeader(game, nation);
    start.columns();
    showTeam(game, nation);
    showHowItPlays(nation);
    start.end();
    inspector.row(start);
    showUnitPictures(nation);
    inspector.more("nations.more", moreCount(game, nation), f -> showMore(f, game, nation));
    inspector.remove("Remove nation", () -> removeNation(nation));
    inspector.end();
    // the header line wraps by its row's width, which the first layout only gives it
    SwingUtilities.invokeLater(inspector::revalidate);
  }

  /**
   * How many options "More options" holds: all of the nation's playerAttachment and those of its
   * rulesAttachment that it shows.
   */
  static int moreCount(final Game game, final String nation) {
    return RulesXml.playerOptions(game, nation).size()
        + (int)
            RulesXml.nationRules(game, nation).stream()
                .filter(o -> NATION_RULES.stream().anyMatch(r -> AttachmentsXml.isOption(o, r)))
                .count();
  }

  // ---- the top: color, name and what it is

  private void showHeader(final Game game, final String nation) {
    final JPanel swatch = new JPanel();
    swatch.setPreferredSize(new Dimension(40, 40));
    swatch.setBackground(project.nationColor(nation));
    swatch.setBorder(
        BorderFactory.createLineBorder(Theme.getSwatchOutlineColor(project.nationColor(nation))));
    swatch.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    swatch.setToolTipText("Change color");
    swatch.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(final MouseEvent e) {
            changeColor(nation);
          }
        });
    // its parts wrap in a narrow inspector instead of being cut
    final JPanel line = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 0));
    line.setOpaque(false);
    showLine(line, game, nation);
    headerLine = line;
    final List<JComponent> links = new ArrayList<>();
    links.add(newLink("Change color", () -> changeColor(nation)));
    final JButton flag = newLink("Flag…", () -> importFlag(nation));
    flag.setToolTipText(
        "Choose a picture: it becomes the nation's flags in the sizes the game shows"
            + " (flags/" + nation + ".png, _small, _large, _fade)");
    links.add(flag);
    if (capital(game, nation).isEmpty()) {
      links.add(newLink(
              "Mark one in Territories", () -> map.followStep(MapGuide.Step.Id.CAPITALS)));
    }
    final JPanel visual = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    visual.setOpaque(false);
    visual.add(swatch);
    flagIcon(nation)
        .ifPresent(
            icon -> {
              final JLabel shown = new JLabel(icon);
              shown.setToolTipText("The nation's flag as the game shows it");
              shown.getAccessibleContext().setAccessibleName("Flag of " + nation);
              visual.add(shown);
            });
    inspector.row(Details.header(Optional.of(visual), nation, line, links));
  }

  /** The nation's flag as the game finds it, the map's or the game's own; empty without one. */
  Optional<Icon> flagIcon(final String nation) {
    return project
        .getFiles()
        .folder()
        .flatMap(folder -> MapFolder.flag(folder, nation, ""))
        // read anew each time, as an import replaces the file under the same name
        .flatMap(
            url -> {
              try {
                return Optional.ofNullable(ImageIO.read(url));
              } catch (final IOException e) {
                return Optional.empty();
              }
            })
        .map(NationsEditor::icon);
  }

  /**
   * A picture chosen becomes the nation's flags in the map's folder, at once and outside undo as
   * other picture imports, each over the one before with its first version as ".bak".
   */
  private void importFlag(final String nation) {
    final Optional<Path> folder = project.getFiles().folder();
    if (folder.isEmpty()) {
      Dialogs.toast(page, "! Save the map first: its flags go into its folder");
      return;
    }
    MapMakerWindow.chooseFileToOpen(
            page,
            "Choose a flag picture for " + nation,
            Path.of(System.getProperty("user.home")),
            "png",
            "gif",
            "jpg",
            "jpeg")
        .ifPresent(
            file -> {
              try {
                final BufferedImage picture = ImageIO.read(file.toFile());
                if (picture == null) {
                  throw new IOException("It is no png, gif or jpg picture.");
                }
                MapFolder.importFlag(folder.get(), nation, picture);
                showInspector();
                Dialogs.toast(page, "✓ " + nation + " has a new flag, in 4 sizes in flags/");
              } catch (final IOException e) {
                Dialogs.warn(
                    page,
                    "Flag",
                    "Could not take " + file.getFileName() + " as the flag.\n" + e.getMessage());
              }
            });
  }

  /** "Axis · 30 PUs at the start · capital Germany · 9 territories", a missing capital warned. */
  private static void showLine(final JPanel line, final Game game, final String nation) {
    line.removeAll();
    line.add(Theme.newSecondaryLabel(allianceLine(game, nation) + " · "));
    line.add(
        Theme.newSecondaryLabel(
            MapXml.startingResource(game, nation, MapXml.PUS) + " PUs at the start · "));
    capital(game, nation)
        .ifPresentOrElse(
            capital -> line.add(Theme.newSecondaryLabel("capital " + capital + " · ")),
            () -> {
              final JLabel missing = new JLabel("! no capital yet");
              missing.setForeground(Theme.getWarningColor());
              line.add(missing);
              line.add(Theme.newSecondaryLabel(" · "));
            });
    line.add(Theme.newSecondaryLabel(owned(game, nation).size() + " territories"));
    line.revalidate();
    line.repaint();
  }

  private static List<String> owned(final Game game, final String nation) {
    return MapXml.territories(game).stream()
        .filter(t -> MapXml.owner(game, t).filter(nation::equals).isPresent())
        .toList();
  }

  private static Optional<String> capital(final Game game, final String nation) {
    return owned(game, nation).stream().filter(t -> MapXml.isCapital(game, t)).findFirst();
  }

  /** A color chooser; the map shows the nation's land in the color pointed at, before it is taken. */
  private void changeColor(final String nation) {
    final JColorChooser chooser = new JColorChooser(project.nationColor(nation));
    chooser.getSelectionModel().addChangeListener(e -> map.previewColor(nation, chooser.getColor()));
    final boolean chosen = Dialogs.ask(page, "Color of " + nation, chooser, "Choose");
    map.endPreview();
    if (chosen) {
      project.setNationColor(nation, chooser.getColor());
    }
  }

  private static JButton newLink(final String text, final Runnable action) {
    final JButton link = new JButton(text);
    Theme.setTertiaryButtonStyle(link);
    link.addActionListener(e -> action.run());
    return link;
  }

  // ---- team and start, who plays it

  private void showTeam(final Game game, final String nation) {
    start.section("Team and start");
    final JComboBox<String> alliance = new JComboBox<>();
    alliance.setEditable(true);
    alliance.addItem(NO_ALLIANCE);
    // its own alliance is "No alliance"
    MapXml.allAlliances(game).stream().filter(a -> !a.equals(nation)).forEach(alliance::addItem);
    final List<String> current = MapXml.alliances(game, nation);
    alliance.setSelectedItem(playsAlone(current, nation) ? NO_ALLIANCE : current.getFirst());
    alliance.addActionListener(
        e -> {
          final String chosen = String.valueOf(alliance.getSelectedItem());
          final List<String> now = MapXml.alliances(project.game(), nation);
          // alone in an alliance of its own name, as maps do, so it stays in the game's list
          final String written = NO_ALLIANCE.equals(chosen) ? nation : chosen;
          if (!(NO_ALLIANCE.equals(chosen) ? playsAlone(now, nation)
              : List.of(chosen).equals(now))) {
            project.edit(g -> MapXml.withAlliance(g, nation, written));
          }
        });
    start.field("Alliance", capped(alliance));
    start.note("Nations of one alliance fight together. Type a new name for a new alliance.");
    start.number(
        "PUs at the start",
        Form.newNumber(
            MapXml.startingResource(game, nation, MapXml.PUS),
            9999,
            value -> quietly(nation, MapXml.PUS, value)));
    final int income = owned(game, nation).stream().mapToInt(t -> MapXml.production(game, t)).sum();
    start.note("Its territories are worth " + income + " PUs per turn.");
  }

  // the same edit as Production's Resources, so both show one value
  private void quietly(final String nation, final String resource, final int value) {
    editingHere = true;
    try {
      project.edit(g -> MapXml.withStartingResource(g, nation, resource, value));
    } finally {
      editingHere = false;
    }
  }

  /** At most 360 px wide, at the left, narrower in a narrow column. */
  private static JComponent capped(final JComponent field) {
    final Dimension size = new Dimension(FIELD_WIDTH, field.getPreferredSize().height);
    field.setPreferredSize(size);
    field.setMaximumSize(size);
    final JPanel holder = new JPanel();
    holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
    holder.setOpaque(false);
    holder.add(field);
    holder.add(Box.createHorizontalGlue());
    return holder;
  }

  /** Who plays the nation by default, and whether a game may leave it out or hide it. */
  private void showHowItPlays(final String nation) {
    start.section("Who plays it");
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
    start.field("Played by default", capped(playedBy));
    start.row(
        playerFlag(
            nation,
            "Players may leave it out",
            player.getOptional(),
            Player.PlayerBuilder::optional));
    start.row(
        playerFlag(
            nation,
            "Can be switched off at the start",
            player.getCanBeDisabled(),
            Player.PlayerBuilder::canBeDisabled));
    start.row(
        Help.with(
            playerFlag(
                nation,
                "Hidden from the player list",
                player.getIsHidden(),
                Player.PlayerBuilder::isHidden),
            "Not shown in the list of players when a game starts, eg the neutrals."));
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

  // ---- unit pictures

  /** The nation's units as they look, and buttons to import pictures for them or take them back. */
  private void showUnitPictures(final String nation) {
    inspector.section("Unit pictures");
    final List<String> units = unitsOf(project.game(), nation);
    if (units.size() < MapXml.unitTypes(project.game()).size()) {
      inspector.note(
          units.size() == 1
              ? "The unit type it can buy or has at the start."
              : "The " + units.size() + " unit types it can buy or has at the start.");
    }
    // rows of a few, as a flowing row would not wrap in the form
    final JPanel pictures = new JPanel(new GridLayout(0, 3, 4, 8));
    pictures.setOpaque(false);
    final Color color = project.nationColor(nation);
    final boolean anyTinted =
        MapXml.nations(project.game()).stream()
            .anyMatch(n -> project.getFiles().unitTint(n).isPresent());
    for (final String unit : units) {
      // a blank of the same size where a picture is missing, so the names stay in line
      final Icon icon =
          project.unitPicture(nation, color, unit).map(NationsEditor::icon).orElse(BLANK);
      if (anyTinted) {
        // a button, as it offers the nation colors, so the keyboard reaches it too
        final JButton picture = new JButton(unit, icon);
        picture.putClientProperty(
            FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        picture.setHorizontalTextPosition(SwingConstants.CENTER);
        picture.setVerticalTextPosition(SwingConstants.BOTTOM);
        tintMenuOn(picture, unit);
        pictures.add(picture);
      } else {
        final JLabel picture = new JLabel(unit, icon, SwingConstants.CENTER);
        picture.setHorizontalTextPosition(SwingConstants.CENTER);
        picture.setVerticalTextPosition(SwingConstants.BOTTOM);
        picture.setToolTipText(unit);
        pictures.add(picture);
      }
    }
    inspector.row(pictures);
    final JCheckBox inColor =
        new JCheckBox(
            "In the nation's color",
            project.getFiles().unitTint(nation).equals(Optional.of(project.nationColor(nation))));
    inColor.setToolTipText(
        "The game tints the pictures in the nation's color when it draws them; the files stay.");
    inColor.addActionListener(
        e -> {
          if (inColor.isSelected()) {
            project.tintUnits(List.of(nation));
          } else {
            project.setMapProperty(MapFiles.UNIT_TINT + nation, "");
          }
        });
    final JButton allNations =
        newLink(
            "All nations",
            () -> project.tintUnits(MapXml.nations(project.game())));
    allNations.setToolTipText("Every nation's units in its color");
    final JPanel tint = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    tint.setOpaque(false);
    tint.add(inColor);
    tint.add(Box.createHorizontalStrut(Theme.GAP_S));
    tint.add(allNations);
    inspector.row(tint);
    final JButton importPictures = new JButton("Import pictures…");
    Theme.setSecondaryButtonStyle(importPictures);
    importPictures.addActionListener(e -> importUnitPictures(nation));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    buttons.setOpaque(false);
    buttons.add(importPictures);
    buttons.add(Box.createHorizontalStrut(Theme.GAP_S));
    if (project.getFiles().nationUnitPictures().keySet().stream()
        .anyMatch(key -> key.nation().equals(nation))) {
      final JButton reset =
          newLink("Reset", () -> project.setNationUnitPictures(nation, Map.of()));
      reset.setToolTipText("Use the game's pictures again");
      buttons.add(reset);
    }
    inspector.row(buttons);
  }

  /**
   * A click on the unit's picture offers whether the game tints it in each nation's color; off
   * for a unit in the same colors for everyone (map.properties units.transform.ignore).
   */
  private void tintMenuOn(final JButton picture, final String unit) {
    final boolean tinted = !project.getFiles().untintedUnits().contains(unit);
    picture.setToolTipText(
        unit + (tinted ? "" : " · in its own colors for every nation") + " · click to change");
    picture.addActionListener(e -> tintMenu(unit).show(picture, 0, picture.getHeight()));
  }

  /** Whether the game tints the unit in each nation's color, ticked when it does. */
  JPopupMenu tintMenu(final String unit) {
    final JPopupMenu menu = new JPopupMenu();
    final JCheckBoxMenuItem inColor =
        new JCheckBoxMenuItem(
            "In each nation's color", !project.getFiles().untintedUnits().contains(unit));
    inColor.setToolTipText(
        "Off: drawn in its own colors for every nation, eg a unit all nations share");
    inColor.addActionListener(
        a -> {
          if (inColor.isSelected()) {
            project.tintUnitType(unit);
          } else {
            project.keepUnitTypeColors(unit);
          }
        });
    menu.add(inColor);
    return menu;
  }

  /**
   * The unit types the nation can buy (its production frontier) or has at the start; all of them
   * when it has none, eg in a new map. A big map has hundreds, of every nation.
   */
  private static List<String> unitsOf(final Game game, final String nation) {
    final List<String> own = MapChecks.unitsOf(game, nation);
    return own.isEmpty() ? MapXml.unitTypes(game) : own;
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
      Dialogs.warn(
          page, "Import pictures", "Could not read " + folder.get() + ":\n" + e.getMessage());
      return;
    }
    if (read.byUnit().isEmpty()) {
      Dialogs.warn(
          page,
          "Import pictures",
          "No picture there is named like a unit type of this game, eg infantry.png.");
      return;
    }
    project.setNationUnitPictures(
        nation, UnitsEditor.askToScale(page, project.getFiles(), read.byUnit()));
    Dialogs.toast(
        page,
        "✓ "
            + read.byUnit().size()
            + " unit pictures imported for "
            + nation
            + (read.unknown().isEmpty() ? "" : " · left out: " + String.join(", ", read.unknown())));
  }

  // ---- more options: capitals, sharing, other rules

  private void showMore(final Form f, final Game game, final String nation) {
    f.section("Capitals");
    f.number(
        "Capitals needed to keep its PUs",
        "With fewer of its capitals, an enemy taking one takes its PUs. 1 when not set.",
        playerNumber(nation, RETAIN_CAPITAL));
    f.number(
        "Capitals needed to collect income and build", playerNumber(nation, RETAIN_CAPITAL_PRODUCE));
    f.row(playerCheck(nation, DESTROYS_PUS, "Its PUs are lost when its capital falls"));
    f.row(
        Help.with(
            playerCheck(nation, IMMUNE_TO_BLOCKADE, "Immune to blockades"),
            "Enemy ships by its land do not cut its income."));
    f.section("Sharing");
    final boolean handsOver = !RulesXml.playerNames(game, nation, GIVE_UNIT_CONTROL).isEmpty();
    f.field("Hands its units over to", playerNations(nation, GIVE_UNIT_CONTROL, "Hands its units over to"));
    final JCheckBox everywhere =
        playerCheck(nation, GIVE_UNIT_CONTROL_EVERYWHERE, "…in all territories, not only theirs");
    everywhere.setEnabled(handsOver);
    everywhere.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
    f.row(everywhere);
    f.field(
        "Shares its technology with",
        playerNations(nation, SHARE_TECHNOLOGY, "Shares its technology with"));
    f.field(
        "Helps pay its research",
        playerNations(nation, HELP_PAY_TECH_COST, "Helps pay its research"));
    showPlacingAndMoving(f, game, nation);
    showOtherRules(f, game, nation);
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

  /** The nations an option lists, short, and "Choose…" right beside them. */
  private JComponent playerNations(final String nation, final String option, final String label) {
    final List<String> chosen = RulesXml.playerNames(project.game(), nation, option);
    final JLabel names = new JLabel(shortList(chosen));
    names.setToolTipText(chosen.isEmpty() ? null : String.join(", ", chosen));
    final JButton choose =
        newLink(
            "Choose…",
            () ->
                Dialogs.pickMany(
                        page,
                        label,
                        MapXml.nations(project.game()).stream()
                            .filter(n -> !n.equals(nation))
                            .toList(),
                        chosen,
                        "Choose")
                    .ifPresent(
                        picked ->
                            project.edit(
                                g -> RulesXml.withPlayerNames(g, nation, option, picked))));
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    row.setOpaque(false);
    row.add(names);
    row.add(Box.createHorizontalStrut(Theme.GAP_S));
    row.add(choose);
    return row;
  }

  // ---- placing and moving: the nation's rulesAttachment

  private void showPlacingAndMoving(final Form f, final Game game, final String nation) {
    f.section("Placing units");
    f.row(
        Help.with(
            ruleCheck(nation, "placementAnyTerritory", "May place in any land it owns"),
            "Also without a factory there. Needs the game option Place in Any Territory."));
    f.row(ruleCheck(nation, "placementAnySeaZone", "May place in any sea zone by its land"));
    f.row(ruleCheck(nation, "placementCapturedTerritory", "May place in land it took this turn"));
    f.row(ruleCheck(nation, "placementInCapitalRestricted", "May place only in its capital"));
    f.row(
        Help.with(
            ruleCheck(nation, "unlimitedProduction", "May buy more than it can place"),
            "The game does not warn it when it buys more units than it can place."));
    f.number(
        "Units placed per territory each turn, at most",
        ruleNumber(nation, "maxPlacePerTerritory"));
    f.number(
        "No placing where it has this many units",
        "It may not place in a territory that holds this many of its units already. Needs the"
            + " game option Unit Placement Per Territory Restricted.",
        ruleNumber(nation, "placementPerTerritory"));
    showFreeUnits(f, game, nation);
    f.section("Moving and attacking");
    showMovement(f, game, nation);
    f.row(
        Help.with(
            ruleCheck(
                nation, "dominatingFirstRoundAttack", "In round 1 its enemies defend at 1 at most"),
            "In its turn of the first round, units it attacks defend at 1 and get no support."));
    f.row(
        ruleCheck(
            nation, "negateDominatingFirstRoundAttack", "Defends as usual against such an attack"));
  }

  private JCheckBox ruleCheck(final String nation, final String option, final String label) {
    final JCheckBox box =
        new JCheckBox(
            label,
            RulesXml.nationRule(project.game(), nation, option)
                .map(v -> Boolean.parseBoolean(v.strip()))
                .orElse(false));
    box.addActionListener(
        e ->
            project.edit(
                g ->
                    box.isSelected()
                        ? RulesXml.withNationRule(g, nation, option, "true")
                        : RulesXml.withoutNationRule(g, nation, option)));
    return box;
  }

  /** A number the game takes as "no limit" when it is not set; emptied, it is taken out. */
  private JSpinner ruleNumber(final String nation, final String option) {
    return Form.newBlankNumber(
        RulesXml.nationRule(project.game(), nation, option)
            .flatMap(NationsEditor::parseInt)
            .orElse(-1),
        -1,
        "no limit",
        chosen ->
            editHere(
                g ->
                    chosen < 0
                        ? RulesXml.withoutNationRule(g, nation, option)
                        : RulesXml.withNationRule(g, nation, option, String.valueOf(chosen))));
  }

  /** A step made by a field that keeps the focus: the inspector is not built again. */
  private void editHere(final UnaryOperator<Game> change) {
    editingHere = true;
    try {
      project.edit(change);
    } finally {
      editingHere = false;
    }
  }

  /** A unit for nothing per so many territories owned: "4:Peasant", the unit infantry if none. */
  private void showFreeUnits(final Form f, final Game game, final String nation) {
    final List<String> units = MapXml.unitTypes(game);
    final String[] rule = RulesXml.nationRule(game, nation, FREE_UNITS).orElse("").split(":", 2);
    final int every = parseInt(rule[0]).orElse(-1);
    final String unit =
        rule.length > 1 ? rule[1] : units.contains("infantry") ? "infantry" : "";
    final JComboBox<String> type = new JComboBox<>(units.toArray(String[]::new));
    type.setSelectedItem(unit.isEmpty() ? units.stream().findFirst().orElse(null) : unit);
    final JSpinner territories =
        Form.newBlankNumber(
            every,
            -1,
            "none",
            chosen ->
                editHere(
                    g ->
                        chosen <= 0 || type.getSelectedItem() == null
                            ? RulesXml.withoutNationRule(g, nation, FREE_UNITS)
                            : RulesXml.withNationRule(
                                g,
                                nation,
                                FREE_UNITS,
                                String.valueOf(chosen),
                                (String) type.getSelectedItem())));
    type.addActionListener(
        e -> {
          final int chosen = (Integer) territories.getValue();
          if (chosen > 0 && type.getSelectedItem() != null) {
            editHere(
                g ->
                    RulesXml.withNationRule(
                        g,
                        nation,
                        FREE_UNITS,
                        String.valueOf(chosen),
                        (String) type.getSelectedItem()));
          }
        });
    f.number(
        "A free unit per this many territories",
        "At its purchase step it gets one unit for nothing for every so many territories it"
            + " owns. Needs the game option Production Per X Territories Restricted and a"
            + " purchase step without PUs (Turn order).",
        territories);
    f.field("The free unit", type);
  }

  /** Where the nation may move: anywhere, only into some territories, or not into them. */
  private void showMovement(final Form f, final Game game, final String nation) {
    final String type = RulesXml.nationRule(game, nation, MOVE_TYPE).orElse("").strip();
    final List<String> listed =
        Stream.of(RulesXml.nationRule(game, nation, MOVE_TERRITORIES).orElse("").split(":"))
            .map(String::strip)
            .filter(t -> !t.isEmpty())
            .toList();
    final int shown = List.of("", MOVE_ONLY_INTO, MOVE_NOT_INTO).indexOf(type);
    final JComboBox<String> kind = new JComboBox<>(MOVE_LABELS.toArray(String[]::new));
    kind.setSelectedIndex(Math.max(0, shown));
    kind.addActionListener(
        e ->
            project.edit(
                g ->
                    switch (kind.getSelectedIndex()) {
                      case 1 -> RulesXml.withNationRule(g, nation, MOVE_TYPE, MOVE_ONLY_INTO);
                      case 2 -> RulesXml.withNationRule(g, nation, MOVE_TYPE, MOVE_NOT_INTO);
                      default ->
                          RulesXml.withoutNationRule(
                              RulesXml.withoutNationRule(g, nation, MOVE_TYPE),
                              nation,
                              MOVE_TERRITORIES);
                    }));
    f.field("Movement", "Needs the game option Movement By Territory Restricted.", kind);
    if (shown <= 0) {
      return;
    }
    final JLabel names = new JLabel(shortList(listed));
    names.setToolTipText(listed.isEmpty() ? null : String.join(", ", listed));
    final List<String> territories = MapXml.territories(game);
    final JButton choose =
        newLink(
            "Choose…",
            () ->
                Dialogs.pickMany(page, MOVE_LABELS.get(shown), territories, listed, "Choose")
                    .ifPresent(
                        ticked -> {
                          // words the game knows, eg "original", are no territories: they stay
                          final List<String> result =
                              new ArrayList<>(
                                  listed.stream()
                                      .filter(t -> !territories.contains(t) || ticked.contains(t))
                                      .distinct()
                                      .toList());
                          ticked.stream().filter(t -> !result.contains(t)).forEach(result::add);
                          project.edit(
                              g ->
                                  result.isEmpty()
                                      ? RulesXml.withoutNationRule(g, nation, MOVE_TERRITORIES)
                                      : RulesXml.withNationRule(
                                          g, nation, MOVE_TERRITORIES, String.join(":", result)));
                        }));
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    row.setOpaque(false);
    row.add(names);
    row.add(Box.createHorizontalStrut(Theme.GAP_S));
    row.add(choose);
    f.row(row);
  }

  /** "None", "Russians and British", "Russians, British and 3 more". */
  static String shortList(final List<String> names) {
    return switch (names.size()) {
      case 0 -> "None";
      case 1 -> names.getFirst();
      case 2, 3 ->
          String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
      default -> names.get(0) + ", " + names.get(1) + " and " + (names.size() - 2) + " more";
    };
  }

  /** The options the editor has no words for, by TripleA's name, each changed or removed here. */
  private void showOtherRules(final Form f, final Game game, final String nation) {
    f.section("Other rules");
    final List<Option> options = RulesXml.playerOptions(game, nation);
    final List<JLabel> names = new ArrayList<>();
    for (int row = 0; row < options.size(); row++) {
      if (!COMMON_RULES.contains(options.get(row).getName())) {
        f.row(newRuleRow(nation, row, options.get(row), names));
      }
    }
    // the values line up behind the longest name
    final int nameWidth = names.stream().mapToInt(n -> n.getPreferredSize().width).max().orElse(0);
    for (final JLabel name : names) {
      final Dimension size = new Dimension(nameWidth, name.getPreferredSize().height);
      name.setPreferredSize(size);
      name.setMaximumSize(size);
    }
    final JPanel add = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    add.setOpaque(false);
    add.add(newLink("+ Add rule…", () -> addRule(nation)));
    f.row(add);
  }

  private JComponent newRuleRow(
      final String nation, final int row, final Option option, final List<JLabel> names) {
    final String name = option.getName();
    final String value = Objects.toString(option.getValue(), "");
    final JLabel label = new JLabel(name);
    names.add(label);
    final JTextField text = new JTextField(value);
    final Dimension size = new Dimension(FIELD_WIDTH, text.getPreferredSize().height);
    text.setPreferredSize(size);
    text.setMaximumSize(size);
    final Runnable take =
        () -> {
          typing = null;
          final String typed = text.getText().strip();
          if (!typed.isEmpty() && !typed.equals(value.strip())) {
            project.edit(
                g ->
                    isRuleAt(g, nation, row, name)
                        ? RulesXml.withPlayerOptionAt(g, nation, row, typed)
                        : g);
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
                        isRuleAt(g, nation, row, name)
                            ? RulesXml.withoutPlayerOptionAt(g, nation, row)
                            : g));
    final JPanel line = new JPanel();
    line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
    line.setOpaque(false);
    line.add(label);
    line.add(Box.createHorizontalStrut(Theme.GAP_S));
    line.add(text);
    line.add(Box.createHorizontalStrut(Theme.GAP_XS));
    line.add(remove);
    line.add(Box.createHorizontalGlue());
    return line;
  }

  // a field of a form the game changed under, eg by undo, changes nothing
  private static boolean isRuleAt(
      final Game game, final String nation, final int row, final String name) {
    final List<Option> options = RulesXml.playerOptions(game, nation);
    return row < options.size() && name.equals(options.get(row).getName());
  }

  /** Asks for a new rule of the nation, its value built from its parts, and adds it as one step. */
  private void addRule(final String nation) {
    final NewRule rule = new NewRule(project.game(), nation);
    if (Dialogs.ask(
        page, "Add a rule for " + nation, rule.content, "Add rule", () -> rule.value().isPresent())) {
      rule.value()
          .ifPresent(
              value ->
                  project.edit(
                      g -> RulesXml.withPlayerOptionAdded(g, nation, rule.name(), value)));
    }
  }

  /** A unit limit as PlayerAttachment reads it, eg "2:owned:infantry:artillery"; "All units" is all. */
  static String unitLimit(final int count, final String counting, final List<String> units) {
    return count
        + ":"
        + counting
        + ":"
        + (units.contains(ALL_UNITS) ? "all" : String.join(":", units));
  }

  /** How a rule's value is asked for. */
  private enum RuleForm {
    UNIT_LIMIT,
    UNITS,
    NATIONS,
    NUMBER,
    TEXT;

    static RuleForm of(final String rule) {
      return switch (rule) {
        case "placementLimit", "movementLimit", "attackingLimit" -> UNIT_LIMIT;
        case "suicideAttackTargets" -> UNITS;
        case "captureUnitOnEnteringBy" -> NATIONS;
        case "vps", "captureVps" -> NUMBER;
        default -> TEXT;
      };
    }
  }

  /** The Add rule dialog's inside: the rule, its value's parts by the rule, the value it writes. */
  private final class NewRule {
    private final JPanel content = new JPanel();
    private final JComboBox<String> rule = new JComboBox<>();
    private final JLabel plain = Theme.newSecondaryLabel(" ");
    private final JPanel parts = new JPanel(new GridLayout(0, 1, 0, Theme.GAP_XS));
    private final JLabel writes = Theme.newSecondaryLabel(" ");
    private final Game game;
    private final String nation;
    private @Nullable RuleForm form;
    private final JSpinner count = new JSpinner(new SpinnerNumberModel(1, 0, 999, 1));
    private final JComboBox<String> counting =
        new JComboBox<>(COUNTING_LABELS.toArray(String[]::new));
    private final JTextField text = new JTextField(24);
    private List<String> names = List.of();

    NewRule(final Game game, final String nation) {
      this.game = game;
      this.nation = nation;
      content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
      content.setOpaque(false);
      parts.setOpaque(false);
      rule.setEditable(true);
      Stream.concat(
              FIRST_RULES.stream(),
              AttachmentsXml.knownOptions(game, "PlayerAttachment").stream()
                  .filter(o -> !COMMON_RULES.contains(o) && !FIRST_RULES.contains(o)))
          .forEach(rule::addItem);
      rule.setRenderer(new RuleRenderer());
      count.setEditor(new JSpinner.NumberEditor(count, "#"));
      text.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Value as TripleA reads it");
      count.addChangeListener(e -> showWrites());
      counting.addActionListener(e -> showWrites());
      OnTyping.attach(text, this::showWrites);
      OnTyping.attach((JTextField) rule.getEditor().getEditorComponent(), this::ruleChanged);
      for (final JComponent part : List.of(new JLabel("Rule"), rule, plain, parts, writes)) {
        part.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(part);
        content.add(Box.createVerticalStrut(Theme.GAP_XS));
      }
      rule.setSelectedIndex(0);
      ruleChanged();
    }

    String name() {
      return ((JTextField) rule.getEditor().getEditorComponent()).getText().strip();
    }

    /** The value as the game reads it; empty while a part is missing. */
    Optional<String> value() {
      if (name().isEmpty() || form == null) {
        return Optional.empty();
      }
      return switch (form) {
        case UNIT_LIMIT ->
            names.isEmpty()
                ? Optional.empty()
                : Optional.of(
                    unitLimit(
                        (Integer) count.getValue(),
                        COUNTING.get(counting.getSelectedIndex()),
                        names));
        case UNITS, NATIONS ->
            names.isEmpty() ? Optional.empty() : Optional.of(String.join(":", names));
        case NUMBER -> Optional.of(String.valueOf(count.getValue()));
        case TEXT -> Optional.of(text.getText().strip()).filter(t -> !t.isEmpty());
      };
    }

    private void ruleChanged() {
      final int known = FIRST_RULES.indexOf(name());
      plain.setText(known < 0 ? " " : FIRST_RULE_LABELS.get(known));
      final RuleForm now = RuleForm.of(name());
      if (now != form) {
        form = now;
        names = List.of();
        showParts();
      }
      showWrites();
    }

    private void showParts() {
      parts.removeAll();
      switch (Objects.requireNonNull(form)) {
        case UNIT_LIMIT -> {
          parts.add(line(new JLabel("At most"), count, new JLabel("counting"), counting));
          parts.add(namesLine("of", "Choose units…", unitChoices()));
        }
        case UNITS -> parts.add(namesLine("Units", "Choose units…", MapXml.unitTypes(game)));
        case NATIONS ->
            parts.add(
                namesLine(
                    "Nations",
                    "Choose nations…",
                    MapXml.nations(game).stream().filter(n -> !n.equals(nation)).toList()));
        case NUMBER -> parts.add(line(count));
        case TEXT -> parts.add(line(text));
      }
      parts.revalidate();
      parts.repaint();
      fitWindow();
    }

    private List<String> unitChoices() {
      return Stream.concat(Stream.of(ALL_UNITS), MapXml.unitTypes(game).stream()).toList();
    }

    private JComponent namesLine(final String label, final String verb, final List<String> all) {
      final JLabel shown = new JLabel("None");
      final JButton choose =
          newLink(
              verb,
              () ->
                  Dialogs.pickMany(content, verb.replace("…", ""), all, names, "Choose")
                      .ifPresent(
                          picked -> {
                            names = picked;
                            shown.setText(shortList(picked));
                            shown.setToolTipText(String.join(", ", picked));
                            showWrites();
                          }));
      return line(new JLabel(label), shown, choose);
    }

    private static JPanel line(final JComponent... items) {
      final JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
      line.setOpaque(false);
      for (final JComponent item : items) {
        line.add(item);
      }
      return line;
    }

    private void showWrites() {
      writes.setText(
          "Writes: " + value().map(v -> name() + " = " + v).orElse("—"));
    }

    // the dialog grows with the parts, eg a units line
    private void fitWindow() {
      Optional.ofNullable(SwingUtilities.getWindowAncestor(content)).ifPresent(Window::pack);
    }
  }

  /** The first rules in plain words with TripleA's name after them, the rest by name. */
  private static final class RuleRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(
        final JList<?> list,
        final Object value,
        final int index,
        final boolean isSelected,
        final boolean cellHasFocus) {
      final String name = String.valueOf(value);
      final int known = FIRST_RULES.indexOf(name);
      final String text =
          known < 0
              ? name
              : "<html>"
                  + FIRST_RULE_LABELS.get(known)
                  + " <span style='color:"
                  + Theme.convertColorToHex(
                      isSelected
                          ? Theme.getSelectionSecondaryTextColor()
                          : Theme.getSecondaryTextColor())
                  + "'>"
                  + name
                  + "</span></html>";
      return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
    }
  }

  // ---- remove

  private void removeNation(final String nation) {
    if (!Dialogs.confirmRemove(
        page,
        "Remove nation",
        "Remove "
            + nation
            + "?\nIts turn, the territories it owns at the start and its units go too. Undo"
            + " brings them back.",
        List.of(),
        "Remove")) {
      return;
    }
    final List<String> named = project.removeNation(nation);
    if (!named.isEmpty()) {
      Dialogs.warn(
          page,
          "Remove nation",
          nation
              + " stays: these still name it\n"
              + String.join("\n", named.subList(0, Math.min(10, named.size())))
              + (named.size() > 10 ? "\nand " + (named.size() - 10) + " more" : "")
              + "\nChange them in Attachments first.");
    }
  }
}
