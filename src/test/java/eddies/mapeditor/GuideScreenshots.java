package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.accessibility.AccessibleContext;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import games.strategy.triplea.settings.ClientSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;

/**
 * Writes the user guide's pictures (docs/guide/img) from the editor as it runs: for each {@link
 * Shot} of {@link #SHOTS} the part of the window around the controls it names, as it is at 100 %
 * and without marks, once in FlatLaf light (name.png) and once in dark (name-dark.png), which the
 * guide swaps with its theme. Not a test of the editor: it runs only with
 * -Dguide.screenshots=true (-Dguide.only=units.* for the pictures whose names match), on a
 * computer with a screen. The window is far off it, never focused and not in the task bar; a
 * dialog or a menu shows on the screen for a moment, as Swing puts it there. No mouse, no keys.
 *
 * <p>It shows copies of its maps, so nothing it does reaches a map, and every shot starts from
 * the map as opened: the pictured map (the folder -Dguide.map names, else the downloaded 270BC),
 * the WW2 Global 1940 game of the tests and a new map made from the guide's example picture,
 * first-map.png, which it only reads. Beside each light picture it leaves the whole window and a
 * list of the controls it saw (build/guide-dump), to write the guide and a new shot from. It fails
 * for a control a shot names that is not there or not in view, and for a picture bigger than its
 * shot allows.
 *
 * <p>No picture shows a folder or the user's name of the computer that makes it ({@link
 * GuidePrivacy}): labels, buttons, fields, tabs and tool tips get a neutral text instead, and a
 * shot with one left elsewhere, eg in a list's row, fails without a picture.
 */
@EnabledIfSystemProperty(named = "guide.screenshots", matches = "true")
final class GuideScreenshots {
  static {
    // in English as the editor runs, before AWT reads a key's name for the menus: "Ctrl+O"
    Locale.setDefault(Locale.ENGLISH);
  }

  private static final Path IMAGES = Path.of("docs/guide/img");
  private static final Path DUMP = Path.of("build/guide-dump");
  private static final Dimension WINDOW = new Dimension(1280, 800);
  private static final Point OFF_SCREEN = new Point(-30000, -30000);
  // around the controls of a cut
  private static final int PADDING = 12;
  private static final Pattern PART = Pattern.compile("(.+):(\\d+)x(\\d+)");

  /** The maps shown: copies, each opened anew for light and dark. */
  private enum On {
    /** A finished map with its picture. */
    PICTURED,
    /** No picture, every rule: canals, politics, objectives, technologies, actions, triggers. */
    WW2,
    /** "Island Wars", as the New map wizard makes it from the example picture, Red and Blue. */
    ISLANDS
  }

  private static final Map<On, MapProject> MAPS = new EnumMap<>(On.class);

  /** What is chosen, opened or scrolled before a picture; run off the Swing thread. */
  @FunctionalInterface
  private interface Step {
    void run() throws Exception;
  }

  /**
   * A picture of the guide: its file's name, the map and the tool shown (as in the sidebar), what
   * is done first, the size of the window, the most the picture may measure, and the controls it
   * is cut around, each found by its text, accessible name or tool tip, else by its class's name;
   * "Units:300x40" takes only that much of the control from its top left corner, eg of the map.
   */
  private record Shot(
      String name,
      On map,
      String tool,
      List<Step> first,
      Dimension window,
      Dimension most,
      List<String> cut) {
    Shot within(final int width, final int height) {
      return new Shot(name, map, tool, first, window, new Dimension(width, height), cut);
    }

    /** In a window of this size, also narrower than the editor's goes: a row comes together. */
    Shot in(final int width, final int height) {
      return new Shot(name, map, tool, first, new Dimension(width, height), most, cut);
    }
  }

  /** A small picture beside a task's steps. */
  private static Shot shot(
      final String name, final On map, final String tool, final List<Step> first,
      final String... cut) {
    return new Shot(name, map, tool, first, WINDOW, new Dimension(700, 420), List.of(cut));
  }

  /** A picture the guide shows wide, below the steps: the map, a whole table. */
  private static Shot wide(
      final String name, final On map, final String tool, final List<Step> first,
      final String... cut) {
    return shot(name, map, tool, first, cut).within(1100, 420);
  }

  private static final List<Step> NOTHING = List.of();

  /** The New map wizard at its second step, with the example picture read. */
  private static final List<Step> WIZARD_PICTURE =
      List.of(click("File"), click("New map…"), type("Island Wars"), click("Next"),
          GuideScreenshots::readExample, await("first-map.png"));

  private static final List<Shot> SHOTS =
      List.of(
          // the new map, in the order of Getting started
          shot("new-map-folder", On.PICTURED, "Home",
              List.of(click("File"), click("New map…"), type("Island Wars")),
              "1 Name", "What is your map called?", "JTextField", "Change…",
              "TripleA lists the maps"),
          shot("new-map", On.PICTURED, "Home", WIZARD_PICTURE,
              "✓ Name", "A suitable picture", "Next").within(700, 520),
          shot("getting-started", On.PICTURED, "Home", WIZARD_PICTURE,
              "✓ Name", "A suitable picture", "Next").within(700, 520),
          shot("getting-started-guide", On.ISLANDS, "Home", NOTHING,
              "Steps to a playable map", "Check: Check the map and play it"),
          shot("home", On.ISLANDS, "Home", NOTHING,
              "Guide", "Next step", "Steps to a playable map", "3 of 7"),
          shot("publish", On.ISLANDS, "Publish", NOTHING,
              "Before you upload", "✓ No backup files in the folder"),
          shot("publish-version", On.ISLANDS, "Publish", NOTHING,
              "Map details", "Map name", "Game version"),
          shot("getting-started-game", On.ISLANDS, "Territories",
              List.of(GuideScreenshots::startTheIslands, show("Territories", "Territory 1")),
              "Units at the start", "Remove factory", "+ Add units"),
          shot("getting-started-play", On.ISLANDS, "Home",
              List.of(GuideScreenshots::startTheIslands),
              "Undo (Ctrl+Z)", "Redo (Ctrl+Y)", "Check", "Save", "Play"),
          shot("window-header", On.ISLANDS, "Home", List.of(GuideScreenshots::startTheIslands),
              "File", "Play").in(760, 800).within(740, 420),
          shot("window-sidebar", On.ISLANDS, "Home", NOTHING, "Sidebar").in(1280, 900)
              .within(320, 900),
          shot("home-problems", On.ISLANDS, "Home",
              List.of(GuideScreenshots::breakTheIslands, scroll("Problems")),
              "Problems", "Show: Territory 2", "Sea Zone 1 is a sea zone:469x40"),
          // the pictured map
          shot("home-tools", On.PICTURED, "Home", NOTHING,
              "Tools", "Territories:", "Game options::134x76"),
          shot("menus", On.PICTURED, "Home", List.of(click("File")), "File", "JPopupMenu"),
          shot("menus-picture", On.PICTURED, "Territories", List.of(click("Map picture")),
              "Map picture", "JPopupMenu"),
          shot("menus-view", On.PICTURED, "Territories", List.of(click("View: Game")),
              "View: Game", "JPopupMenu"),
          shot("menus-paint", On.PICTURED, "Territories", List.of(click("Paint")),
              "Paint", "JPopupMenu"),
          shot("territories", On.PICTURED, "Territories",
              List.of(click("Select"), show("Territories", "Sparta")),
              "← All territories", "Name", "+ Add units").within(700, 560),
          wide("territories-paint", On.PICTURED, "Territories",
              List.of(GuideScreenshots::zoom, show("Territories", "Sparta"),
                  click("Paint"), click("Owner"), click("RomanRepublic")),
              "View: Game", "Map picture", "MapView:2000x240"),
          shot("connections-find", On.PICTURED, "Connections", NOTHING,
              "697 connections", "Find neighbours again…"),
          wide("connections", On.PICTURED, "Connections",
              List.of(GuideScreenshots::zoom, show("Connections", "Sparta")), "MapView")
              .in(1280, 620).within(1100, 560),
          shot("terrain-effects", On.PICTURED, "Terrain effects",
              List.of(click("Add effect…"), type("Forest"), click("Add"),
                  click("+ Add unit type"), choose("archer"), click("Add")),
              "Forest:300x25", "Choose…", "Name", "In battle and on the move", "+ Add unit type",
              "Added to the unit's strength"),
          shot("map-look", On.PICTURED, "Map look", NOTHING,
              "Units", "Smaller units", "now 3600", "Stack up to"),
          shot("map-look-relief", On.PICTURED, "Map look",
              List.of(scroll("Relief"), open("Blending")),
              "Relief", "Create relief…", "Textures for the relief", "Strength in %"),
          shot("map-look-resize", On.PICTURED, "Map look", List.of(click("Resize map…")),
              "JRootPane"),
          shot("nations", On.PICTURED, "Nations", List.of(choose("")),
              "← All nations", "Name", "PUs at the start", "Its territories are worth"),
          shot("nations-limits", On.PICTURED, "Nations",
              List.of(choose(""), open("More options"), click("+ Add limit"), click("archer"),
                  click("hoplite"), click("Choose"), scroll("Stacking limits")),
              "Stacking limits", "A limit holds in every territory", "When", "Remove this limit",
              "+ Add limit"),
          shot("units", On.PICTURED, "Units", List.of(choose("archer")),
              "archer", "Hit points", "Draw it on the map", "Search options"),
          shot("production", On.PICTURED, "Production", List.of(choose("Macedonia")),
              "Macedonia:300x25", "Used by Macedonia", "Copy from…", "Show", "All rules ("),
          wide("production-rules", On.PICTURED, "Production",
              List.of(choose("Macedonia"), click("All rules (")),
              "Show", "+ Add rule…", "JTableHeader", "JTable:2000x156"),
          shot("turn-order", On.PICTURED, "Turn order", NOTHING,
              "The first round is round", "Help: The first round", "+ Add step…", "JTableHeader",
              "JTable:2000x72").in(760, 800),
          wide("turn-order-table", On.PICTURED, "Turn order",
              List.of(pick("JComboBox", 2), GuideScreenshots::chooseStep),
              "JTableHeader", "JTable:2000x268", "Buy units:320x25", "Name in game:320x22",
              "+ Add property…"),
          shot("game-options", On.PICTURED, "Game options", NOTHING,
              "Search options", "+ Add option…", "Dice sides", "8 Capital Victory: Players")
              .in(760, 800),
          shot("game-notes", On.PICTURED, "Game notes", List.of(click("Split")),
              "Bold (Ctrl+B)", "Preview", "CodeArea:2000x82").in(760, 800),
          shot("texts", On.PICTURED, "Texts", List.of(click("Messages"), choose("")),
              "JTabbedPane", "+ Add text…", "GameNotesView:2000x46").in(820, 800)
              .within(760, 420),
          shot("sounds", On.PICTURED, "Sounds",
              List.of(click("Your own"), choose("Your turn starts")),
              "Your turn starts", "Plays", "Add file…", "Add folder…"),
          shot("attachments", On.PICTURED, "Attachments", List.of(choose("archer")),
              "archer:300x25", "Name", "Attached to", "Options (", "+ Add option…",
              "JTableHeader", "JTable:2000x140"),
          shot("files", On.PICTURED, "Files", NOTHING,
              "What's in a map folder", "Games…", "vc.txt", "name_place.txt:1x12")
              .in(1480, 800).within(960, 420),
          // the game with every rule
          shot("production-resources", On.WW2, "Production", List.of(click("Resources")),
              "JTabbedPane:780x30", "+ Add resource…", "At the start", "JTableHeader",
              "JTable:2000x128")
              .within(800, 420),
          shot("canals", On.WW2, "Canals", List.of(choose("Suez")),
              "← All canals", "Name", "Choose the unit types that pass freely").within(700, 460),
          shot("politics", On.WW2, "Politics", NOTHING,
              "Click a cell to choose:580x16", "British and UK_Pacific:82x44"),
          shot("politics-types", On.WW2, "Politics",
              List.of(click("Relationship types"), choose("Neutrality")),
              "JTabbedPane:400x30", "Neutrality:300x25", "What nations in it may do:420x16",
              "Planes land:160x15").within(800, 420),
          shot("objectives", On.WW2, "Objectives", NOTHING,
              "Objectives of which nation", "SearchList:2000x320"),
          shot("objectives-checks", On.WW2, "Objectives",
              List.of(choose("Volgograd"), scroll("Pays while")),
              "Pays while", "Remove check", "+ Add check"),
          shot("technology", On.WW2, "Technology", NOTHING,
              "This game uses", "+ Add technology…", "SearchList:2000x230"),
          shot("technology-bonus", On.WW2, "Technology",
              List.of(choose("superSub"), click("+ Add bonus…"), click("Add"),
                  scroll("What it gives")),
              "What it gives", "Remove Attack for infantry", "+ Add bonus…",
              "Rockets, war bonds:440x22"),
          shot("actions", On.WW2, "Actions", List.of(choose("")),
              "Declare War!", "Message to the others when it succeeds"),
          shot("actions-does", On.WW2, "Actions",
              List.of(choose("Japanese To War With Russians"), scroll("What it does")),
              "What it does", "+ Add change", "Succeeds on a roll of").within(700, 450),
          shot("triggers", On.WW2, "Triggers", NOTHING,
              "JComboBox", "Group the triggers by", "SearchList:2000x270"),
          shot("triggers-when", On.WW2, "Triggers",
              List.of(choose("Germans 3 Presence"), click("Change"),
                  click("Step When and how often")),
              "When and how often:440x25", "A use is taken", "Only by chance"));

  // the copies of the maps, removed with it
  @TempDir Path folder;

  @DisplayName("Writes the guide's pictures, light and dark")
  @Test
  void writePictures() throws Exception {
    Files.createDirectories(IMAGES);
    Files.createDirectories(DUMP);
    final String only = System.getProperty("guide.only", "");
    // TripleA's settings, which the New map wizard asks for the maps folder
    ClientSetting.initialize();
    final List<String> missed = new ArrayList<>();
    Toolkit.getDefaultToolkit()
        .addAWTEventListener(
            GuideScreenshots::keepAway,
            AWTEvent.HIERARCHY_EVENT_MASK
                | AWTEvent.WINDOW_EVENT_MASK
                | AWTEvent.COMPONENT_EVENT_MASK);
    for (final boolean dark : List.of(false, true)) {
      final MapMakerWindow[] window = new MapMakerWindow[1];
      givenMaps(folder);
      onSwing(
          () -> {
            Theme.install();
            if (dark) {
              com.formdev.flatlaf.FlatDarkLaf.setup();
            } else {
              com.formdev.flatlaf.FlatLightLaf.setup();
            }
            window[0] = new MapMakerWindow();
            window[0].setMinimumSize(new Dimension(600, 400));
            window[0].setSize(WINDOW);
            window[0].setFocusableWindowState(false);
            // not in the task bar, where a window nobody sees would be closed by hand
            window[0].setType(Window.Type.UTILITY);
            window[0].setLocation(OFF_SCREEN);
            window[0].setVisible(true);
          });
      try {
        for (final Shot shot : SHOTS) {
          if (!only.isBlank() && !shot.name().matches(only)) {
            continue;
          }
          final String name = shot.name() + (dark ? "-dark" : "");
          // the map as it was opened and every tool as it opens, whatever the shot before did
          onSwing(
              () -> {
                final MapProject map = MAPS.get(shot.map());
                while (map.canUndo()) {
                  map.undo();
                }
                window[0].setSize(shot.window());
                window[0].showProject(map);
              });
          Thread.sleep(1500);
          try {
            run(clickTool(shot.tool()));
            for (final Step step : shot.first()) {
              run(step);
            }
          } catch (final RuntimeException e) {
            missed.add(name + ": " + e);
          }
          try {
            onSwing(() -> missed.addAll(cut(shot, name)));
          } finally {
            onSwing(GuideScreenshots::closeDialogsAndMenus);
          }
        }
      } finally {
        onSwing(window[0]::dispose);
      }
    }
    assertThat(missed).as("pictures not as their shots say").isEmpty();
  }

  private static void run(final Step step) throws Exception {
    step.run();
    Thread.sleep(500);
  }

  /**
   * A dialog is never focused, and it and a menu go off the screen as soon as they show there,
   * where Swing puts them.
   */
  private static void keepAway(final AWTEvent event) {
    if (event.getSource() instanceof Window window && !(window instanceof MapMakerWindow)) {
      if (event instanceof HierarchyEvent && !window.isVisible()) {
        window.setFocusableWindowState(false);
      } else if (event.getID() == WindowEvent.WINDOW_OPENED
          || event.getID() == ComponentEvent.COMPONENT_SHOWN) {
        window.setLocation(OFF_SCREEN);
      }
    }
  }

  private static void closeDialogsAndMenus() {
    MenuSelectionManager.defaultManager().clearSelectedPath();
    Stream.of(Window.getWindows())
        .filter(JDialog.class::isInstance)
        .filter(Component::isShowing)
        .forEach(Window::dispose);
  }

  // ---- the steps

  /** Clicks the sidebar's row of the tool. */
  private static Step clickTool(final String tool) {
    return () ->
        onSwing(
            () ->
                all(root())
                    .filter(AbstractButton.class::isInstance)
                    .map(AbstractButton.class::cast)
                    .filter(b -> tool.equals(accessibleNameOf(b)))
                    .filter(b -> SwingUtilities.getAncestorOfClass(Sidebar.class, b) != null)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no tool " + tool))
                    .doClick());
  }

  /**
   * Clicks the button, menu item or tab found by the text. Not waited for, as a click may open a
   * dialog, which ends only when it is closed.
   */
  private static Step click(final String text) {
    return () ->
        onSwing(
            () -> {
              final Component found =
                  find(text, c -> c instanceof AbstractButton)
                      .or(() -> tabOf(text))
                      .orElseThrow(() -> new IllegalStateException("nothing to click: " + text));
              if (found instanceof JTabbedPane tabs) {
                tabs.setSelectedIndex(tabIndex(tabs, text));
              } else if (found instanceof JMenu menu) {
                // a menu in a menu opens beside it
                final MenuSelectionManager menus = MenuSelectionManager.defaultManager();
                final List<MenuElement> path = new ArrayList<>(List.of(menus.getSelectedPath()));
                path.add(menu);
                path.add(menu.getPopupMenu());
                menus.setSelectedPath(path.toArray(MenuElement[]::new));
              } else {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                SwingUtilities.invokeLater(() -> ((AbstractButton) found).doClick(0));
              }
            });
  }

  /** Opens the fold row found by the text, unless it is open: folds stay for the session. */
  private static Step open(final String text) {
    return () -> {
      final boolean[] folded = new boolean[1];
      onSwing(
          () ->
              folded[0] =
                  find(text, AbstractButton.class::isInstance)
                      .filter(fold -> textOf(fold).startsWith("▸"))
                      .isPresent());
      if (folded[0]) {
        click(text).run();
      }
    };
  }

  private static Optional<Component> tabOf(final String text) {
    return shown().stream()
        .filter(c -> c instanceof JTabbedPane tabs && tabIndex(tabs, text) >= 0)
        .findFirst();
  }

  private static int tabIndex(final JTabbedPane tabs, final String text) {
    for (int i = 0; i < tabs.getTabCount(); i++) {
      if (oneLine(String.valueOf(tabs.getTitleAt(i))).startsWith(text)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Chooses the first row with the text in a list, as a click does: in a tool's list a row with
   * it in its title, the line below or its id, as its search finds it.
   */
  private static Step choose(final String text) {
    return () ->
        onSwing(
            () -> {
              final java.lang.reflect.Method searchText =
                  SearchList.class.getDeclaredMethod("searchText", String.class);
              searchText.setAccessible(true);
              for (final Component c : shown()) {
                if (c instanceof JList<?> list) {
                  final SearchList rows =
                      (SearchList) SwingUtilities.getAncestorOfClass(SearchList.class, list);
                  for (int i = 0; i < list.getModel().getSize(); i++) {
                    final String row = String.valueOf(list.getModel().getElementAt(i));
                    // a group's header is shown as another text than it is
                    final boolean has =
                        rows == null
                            ? row.contains(text)
                            : row.equals(rows.rows().get(i))
                                && String.valueOf(searchText.invoke(rows, row))
                                    .contains(text.toLowerCase(Locale.ROOT));
                    if (has) {
                      list.setSelectedIndex(i);
                      list.ensureIndexIsVisible(i);
                      return;
                    }
                  }
                }
              }
              throw new IllegalStateException("no row to choose: " + text);
            });
  }

  /** Shows the territory in the tool, as a click on the map does. */
  private static Step show(final String tool, final String territory) {
    return () ->
        onSwing(
            () -> {
              final Object window = SwingUtilities.getWindowAncestor(root());
              final java.lang.reflect.Field editors =
                  MapMakerWindow.class.getDeclaredField("editors");
              editors.setAccessible(true);
              ((Editor) ((Map<?, ?>) editors.get(window)).get(tool)).show(territory);
            });
  }

  /** The map at 100 %. */
  private static void zoom() throws Exception {
    onSwing(
        () -> {
          final MapCanvas map = (MapCanvas) find("MapCanvas", c -> true).orElseThrow();
          map.zoomBy(1 / map.getZoom());
        });
  }

  /** Chooses the entry at the index in the box found by the text. */
  private static Step pick(final String box, final int index) {
    return () ->
        onSwing(
            () ->
                ((JComboBox<?>) find(box, JComboBox.class::isInstance).orElseThrow())
                    .setSelectedIndex(index));
  }

  /** Turn order: a step of the nation's band chosen. */
  private static void chooseStep() throws Exception {
    onSwing(
        () ->
            ((JTable) find("JTable", c -> true).orElseThrow()).setRowSelectionInterval(5, 5));
  }

  /** Scrolls the control found by the text to the top of its scroll pane. */
  private static Step scroll(final String text) {
    return () ->
        onSwing(
            () -> {
              final Component found =
                  find(text, c -> true)
                      .orElseThrow(() -> new IllegalStateException("not there: " + text));
              final JViewport viewport =
                  (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, found);
              if (viewport != null) {
                final Rectangle at =
                    SwingUtilities.convertRectangle(
                        found.getParent(), found.getBounds(), viewport.getView());
                viewport.setViewPosition(new Point(0, Math.max(0, at.y - PADDING)));
              }
            });
  }

  /** Waits until a control with the text shows, eg what a search in the background found. */
  private static Step await(final String text) {
    return () -> {
      final long until = System.currentTimeMillis() + 30_000;
      final boolean[] there = new boolean[1];
      while (!there[0] && System.currentTimeMillis() < until) {
        Thread.sleep(200);
        onSwing(() -> there[0] = find(text, c -> true).isPresent());
      }
      if (!there[0]) {
        throw new IllegalStateException("never shown: " + text);
      }
    };
  }

  /** Types the text into the first text field, eg of the dialog just opened. */
  private static Step type(final String text) {
    return () ->
        onSwing(
            () ->
                ((JTextField) find("JTextField", c -> true).orElseThrow()).setText(text));
  }

  /** The wizard reads the guide's example picture, as after Choose picture…. */
  private static void readExample() throws Exception {
    onSwing(
        () -> {
          final Component wizard = find("NewMapWizard", c -> true).orElseThrow();
          final java.lang.reflect.Method read =
              NewMapWizard.class.getDeclaredMethod("readPicture", Path.class);
          read.setAccessible(true);
          read.invoke(wizard, IMAGES.resolve("first-map.png"));
        });
  }

  /** Getting started's game: Red's first land is its capital, with infantry and a factory. */
  private static void startTheIslands() throws Exception {
    final MapProject project = MAPS.get(On.ISLANDS);
    onSwing(
        () ->
            project.edit(
                game -> {
                  final String land =
                      MapXml.territories(game).stream()
                          .filter(t -> !MapXml.isWater(game, t))
                          .sorted()
                          .findFirst()
                          .orElseThrow();
                  Game changed = MapXml.withOwner(game, land, "Red");
                  changed = MapXml.withCapital(changed, land, "Red");
                  changed = MapXml.withUnits(changed, land, "Red", "infantry", 2);
                  return MapXml.withUnits(changed, land, "Red", "factory", 1);
                }));
  }

  /** Home's problems: land of a nation that is none, and land units in the sea. */
  private static void breakTheIslands() throws Exception {
    final MapProject project = MAPS.get(On.ISLANDS);
    onSwing(
        () ->
            project.edit(
                game -> {
                  final List<String> all = MapXml.territories(game).stream().sorted().toList();
                  final String sea =
                      all.stream().filter(t -> MapXml.isWater(game, t)).findFirst().orElseThrow();
                  final String land =
                      all.stream().filter(t -> !MapXml.isWater(game, t)).skip(1).findFirst()
                          .orElseThrow();
                  return MapXml.withUnits(
                      MapXml.withOwner(game, land, "Green"), sea, "Red", "infantry", 1);
                }));
  }

  // ---- the picture

  /**
   * Writes the shot's picture: the window, or the dialog over it, with its open menus, cut around
   * the shot's controls. Returns what is not as the shot says.
   */
  private static List<String> cut(final Shot shot, final String name) throws IOException {
    final List<String> missed = new ArrayList<>();
    final JRootPane root = root();
    // no folder or name of this computer in a picture, eg where the wizard puts a new map
    final GuidePrivacy own = GuidePrivacy.ofThisComputer();
    shown().forEach(c -> makeNeutral(c, own));
    final List<String> left =
        shown().stream()
            .flatMap(GuideScreenshots::textsOf)
            .filter(own::shows)
            .distinct()
            .map(text -> name + ": shows a folder or name of this computer: " + oneLine(text))
            .toList();
    if (!left.isEmpty()) {
      // no picture, as it would show them
      return left;
    }
    final List<JPopupMenu> menus =
        Stream.of(MenuSelectionManager.defaultManager().getSelectedPath())
            .filter(JPopupMenu.class::isInstance)
            .map(JPopupMenu.class::cast)
            .filter(Component::isShowing)
            .toList();
    // a menu may reach beyond the window
    final Rectangle all = new Rectangle(root.getSize());
    menus.forEach(menu -> all.add(boundsIn(menu, root)));
    final BufferedImage image =
        new BufferedImage(all.width, all.height, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setColor(root.getBackground());
    g.fillRect(0, 0, all.width, all.height);
    g.translate(-all.x, -all.y);
    // painted, not printed: a table printed leaves out which row is chosen
    root.paint(g);
    for (final JPopupMenu menu : menus) {
      final Rectangle at = boundsIn(menu, root);
      final Graphics2D inMenu = (Graphics2D) g.create(at.x, at.y, at.width, at.height);
      menu.printAll(inMenu);
      inMenu.dispose();
    }
    g.dispose();
    if (!name.endsWith("-dark")) {
      ImageIO.write(image, "png", DUMP.resolve(name + ".png").toFile());
      Files.writeString(DUMP.resolve(name + ".txt"), dump(root, shown()));
    }
    Rectangle around = null;
    for (final String entry : shot.cut()) {
      final Matcher part = PART.matcher(entry);
      final String text = part.matches() ? part.group(1) : entry;
      final Optional<Component> found = find(text, c -> true);
      if (found.isEmpty()) {
        missed.add(name + ": no " + text);
        continue;
      }
      final Rectangle at = boundsIn(found.get(), root);
      if (at.isEmpty()) {
        missed.add(name + ": not in view: " + text);
        continue;
      }
      if (part.matches()) {
        at.setSize(
            Math.min(at.width, Integer.parseInt(part.group(2))),
            Math.min(at.height, Integer.parseInt(part.group(3))));
      }
      around = around == null ? at : around.union(at);
    }
    if (around == null) {
      return missed;
    }
    around.grow(PADDING, PADDING);
    around.translate(-all.x, -all.y);
    if (around.width > shot.most().width + 2 * PADDING
        || around.height > shot.most().height + 2 * PADDING) {
      missed.add(name + ": " + around.width + " x " + around.height + " is too big");
    }
    final Rectangle within = around.intersection(new Rectangle(all.width, all.height));
    ImageIO.write(
        image.getSubimage(within.x, within.y, within.width, within.height),
        "png",
        IMAGES.resolve(name + ".png").toFile());
    return missed;
  }

  /** The control's label, button, field, tab and tool tip texts without this computer's. */
  private static void makeNeutral(final Component c, final GuidePrivacy own) {
    // set only when another, as a text set tells its listeners
    if (c instanceof JLabel label && label.getText() != null && own.shows(label.getText())) {
      label.setText(own.neutral(label.getText()));
    }
    if (c instanceof AbstractButton b && b.getText() != null && own.shows(b.getText())) {
      b.setText(own.neutral(b.getText()));
    }
    if (c instanceof JTextComponent t && t.getText() != null && own.shows(t.getText())) {
      t.setText(own.neutral(t.getText()));
    }
    if (c instanceof JTabbedPane tabs) {
      for (int i = 0; i < tabs.getTabCount(); i++) {
        if (tabs.getTitleAt(i) != null && own.shows(tabs.getTitleAt(i))) {
          tabs.setTitleAt(i, own.neutral(tabs.getTitleAt(i)));
        }
      }
    }
    if (c instanceof JComponent j && j.getToolTipText() != null && own.shows(j.getToolTipText())) {
      j.setToolTipText(own.neutral(j.getToolTipText()));
    }
  }

  /**
   * Every text of the control, whole: what the dump has of it and its tabs, rows, entries and
   * cells, which {@link #makeNeutral} does not change.
   */
  private static Stream<String> textsOf(final Component c) {
    final List<Object> texts = new ArrayList<>();
    texts.add(accessibleNameOf(c));
    if (c instanceof AbstractButton b) {
      texts.add(b.getText());
    }
    if (c instanceof JLabel l) {
      texts.add(l.getText());
    }
    if (c instanceof JTextComponent t) {
      texts.add(t.getText());
    }
    if (c instanceof JComponent j) {
      texts.add(j.getToolTipText());
    }
    if (c instanceof JTabbedPane tabs) {
      for (int i = 0; i < tabs.getTabCount(); i++) {
        texts.add(tabs.getTitleAt(i));
      }
    }
    if (c instanceof JList<?> list) {
      for (int i = 0; i < list.getModel().getSize(); i++) {
        texts.add(list.getModel().getElementAt(i));
      }
    }
    if (c instanceof JComboBox<?> box) {
      for (int i = 0; i < box.getItemCount(); i++) {
        texts.add(box.getItemAt(i));
      }
    }
    if (c instanceof JTable table) {
      for (int row = 0; row < table.getRowCount(); row++) {
        for (int column = 0; column < table.getColumnCount(); column++) {
          texts.add(table.getValueAt(row, column));
        }
      }
    }
    return texts.stream().filter(Objects::nonNull).map(String::valueOf);
  }

  /** The dialog that is open, else the editor's window. */
  private static JRootPane root() {
    final List<Window> windows =
        Stream.of(Window.getWindows()).filter(Component::isShowing).toList();
    return windows.stream()
        .filter(JDialog.class::isInstance)
        .map(w -> ((JDialog) w).getRootPane())
        .reduce((first, last) -> last)
        .orElseGet(
            () ->
                windows.stream()
                    .filter(MapMakerWindow.class::isInstance)
                    .map(w -> ((MapMakerWindow) w).getRootPane())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("the editor's window is gone")));
  }

  /**
   * The part of the component that shows, eg in its scroll pane, as the root pane has it. A menu
   * is where the editor opens it, under its button, a menu in a menu beside its row: Swing shows
   * it on the screen instead, as the window is off it.
   */
  private static Rectangle boundsIn(final Component component, final JRootPane root) {
    if (component instanceof JPopupMenu menu) {
      final Rectangle by = boundsIn(menu.getInvoker(), root);
      return menu.getInvoker() instanceof JMenu
          ? new Rectangle(by.x + by.width, by.y, menu.getWidth(), menu.getHeight())
          : new Rectangle(by.x, by.y + by.height, menu.getWidth(), menu.getHeight());
    }
    final Component menu = SwingUtilities.getAncestorOfClass(JPopupMenu.class, component);
    if (menu != null) {
      final Rectangle in = boundsIn(menu, root);
      final Point at = SwingUtilities.convertPoint(component, 0, 0, menu);
      return new Rectangle(in.x + at.x, in.y + at.y, component.getWidth(), component.getHeight());
    }
    final Rectangle shown =
        component instanceof JComponent j ? j.getVisibleRect() : new Rectangle(component.getSize());
    final Point at = component.getLocationOnScreen();
    final Point origin = root.getLocationOnScreen();
    return new Rectangle(
        at.x - origin.x + shown.x, at.y - origin.y + shown.y, shown.width, shown.height);
  }

  /** Everything showing in the open dialog, else the window, and in the open menus. */
  private static List<Component> shown() {
    final List<Component> roots = new ArrayList<>();
    roots.add(root());
    for (final MenuElement element : MenuSelectionManager.defaultManager().getSelectedPath()) {
      if (element instanceof JPopupMenu menu) {
        roots.add(menu);
      }
    }
    final List<Component> showing =
        roots.stream()
            .flatMap(GuideScreenshots::all)
            .distinct()
            .filter(Component::isShowing)
            .toList();
    // first what is in view, then what its scroll pane hides
    final Predicate<Component> inView =
        c -> !(c instanceof JComponent j) || !j.getVisibleRect().isEmpty();
    return Stream.concat(showing.stream().filter(inView), showing.stream().filter(inView.negate()))
        .toList();
  }

  /**
   * The first shown component of the kind with the text as its text or accessible name, else the
   * first whose text, accessible name or tool tip starts with it, else the first of a class of
   * that name or made from one; a label only when nothing else has the text. Not in the sidebar.
   */
  private static Optional<Component> find(final String text, final Predicate<Component> kind) {
    final Predicate<Component> exact =
        c -> text.equals(textOf(c)) || text.equals(accessibleNameOf(c));
    final Predicate<Component> starts =
        c ->
            textOf(c).startsWith(text)
                || accessibleNameOf(c).startsWith(text)
                || (c instanceof JComponent j
                    && j.getToolTipText() != null
                    && j.getToolTipText().startsWith(text));
    // the sidebar's rows have the tools' names, which are also words in the tools
    final List<Component> shown =
        shown().stream()
            .filter(kind)
            .filter(c -> SwingUtilities.getAncestorOfClass(Sidebar.class, c) == null)
            .toList();
    return shown.stream()
        .filter(exact)
        .filter(
            c ->
                !(c instanceof JLabel)
                    || shown.stream().noneMatch(o -> exact.test(o) && !(o instanceof JLabel)))
        .findFirst()
        .or(() -> shown.stream().filter(starts).findFirst())
        .or(() -> shown.stream().filter(c -> isA(c.getClass(), text)).findFirst());
  }

  private static boolean isA(final Class<?> type, final String name) {
    return type != null && (type.getSimpleName().equals(name) || isA(type.getSuperclass(), name));
  }

  // ---- the maps

  private static void givenMaps(final Path folder) throws Exception {
    MAPS.put(On.PICTURED, MapProject.open(givenMapXml(folder)));
    MAPS.put(On.WW2, MapProject.open(givenWw2Xml(folder)));
    MAPS.put(
        On.ISLANDS,
        MapProject.newMap(
                "Island Wars",
                ImageIO.read(IMAGES.resolve("first-map.png").toFile()),
                List.of("Red", "Blue"),
                Files.createTempDirectory(folder, "new"))
            .project());
  }

  /**
   * The WW2 Global 1940 game of the tests, in a folder of its own: no picture, every rule; with
   * the flags and texts of the downloaded map when it is there, so Nations and Politics show
   * flags and Actions the buttons' texts.
   */
  private static Path givenWw2Xml(final Path in) throws IOException {
    final Path folder = Files.createTempDirectory(in, "ww2");
    final Path games = Files.createDirectories(folder.resolve("games"));
    final Path xml = games.resolve("ww2global40.xml");
    try (java.io.InputStream original =
        GuideScreenshots.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      Files.copy(original, xml);
    }
    final Path downloaded =
        Path.of(System.getProperty("user.home"), "triplea/downloadedMaps/world_war_ii_global/map");
    for (final String name :
        List.of("flags", "politicstext.properties")) {
      if (Files.exists(downloaded.resolve(name))) {
        copy(downloaded.resolve(name), folder.resolve(name));
      }
    }
    return xml;
  }

  /** The first game of a copy of the pictured map, with its map.yml when that is a folder up. */
  private static Path givenMapXml(final Path folder) throws IOException {
    final String named = System.getProperty("guide.map", "");
    final Path content =
        (named.isBlank()
                ? Path.of(System.getProperty("user.home"), "triplea/downloadedMaps/270bc/map")
                : Path.of(named))
            .toAbsolutePath();
    final Path map =
        Files.isRegularFile(content.resolveSibling("map.yml")) ? content.getParent() : content;
    final Path copied = Files.createTempDirectory(folder, "map").resolve(map.getFileName());
    copy(map, copied);
    final Path games = copied.resolve(map.relativize(content)).resolve("games");
    try (Stream<Path> files = Files.list(games)) {
      return files.filter(f -> f.toString().endsWith(".xml")).sorted().findFirst().orElseThrow();
    }
  }

  private static void copy(final Path from, final Path to) throws IOException {
    try (Stream<Path> files = Files.walk(from)) {
      for (final Path file : files.toList()) {
        final Path copied = to.resolve(from.relativize(file).toString());
        if (Files.isDirectory(file)) {
          Files.createDirectories(copied);
        } else {
          Files.copy(file, copied);
        }
      }
    }
  }

  // ---- shared

  /** Work that may fail, run on the Swing thread. */
  @FunctionalInterface
  private interface Work {
    void run() throws Exception;
  }

  private static void onSwing(final Work work) throws Exception {
    final Exception[] failed = new Exception[1];
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            work.run();
          } catch (final Exception e) {
            failed[0] = e;
          }
        });
    if (failed[0] != null) {
      throw failed[0];
    }
  }

  private static Stream<Component> all(final Component component) {
    final Stream<Component> own = Stream.of(component);
    return component instanceof Container container
        ? Stream.concat(own, Stream.of(container.getComponents()).flatMap(GuideScreenshots::all))
        : own;
  }

  private static String textOf(final Component c) {
    if (c instanceof AbstractButton b) {
      return oneLine(Optional.ofNullable(b.getText()).orElse(""));
    }
    if (c instanceof JLabel l) {
      return oneLine(Optional.ofNullable(l.getText()).orElse(""));
    }
    return "";
  }

  private static String accessibleNameOf(final Component c) {
    final AccessibleContext context = c.getAccessibleContext();
    return context == null ? "" : Optional.ofNullable(context.getAccessibleName()).orElse("");
  }

  /** Every shown control with a word on it, with where it is: to write the guide from. */
  private static String dump(final JRootPane root, final List<Component> shown) {
    final StringBuilder out = new StringBuilder();
    final Map<String, Integer> seen = new LinkedHashMap<>();
    for (final Component c : shown) {
      final String kind = c.getClass().getSimpleName();
      final String text = textOf(c);
      final String name = accessibleNameOf(c);
      final String tip =
          c instanceof JComponent j ? Optional.ofNullable(j.getToolTipText()).orElse("") : "";
      final String typed =
          c instanceof JTextComponent t ? Optional.ofNullable(t.getText()).orElse("") : "";
      if (text.isBlank() && name.isBlank() && tip.isBlank() && typed.isBlank()) {
        continue;
      }
      final Rectangle at = boundsIn(c, root);
      final String line =
          kind
              + " @"
              + at.x
              + ","
              + at.y
              + " "
              + at.width
              + "x"
              + at.height
              + (text.isBlank() ? "" : " text=\"" + text + "\"")
              + (name.isBlank() || name.equals(text) ? "" : " name=\"" + oneLine(name) + "\"")
              + (typed.isBlank() ? "" : " typed=\"" + oneLine(typed) + "\"")
              + (tip.isBlank() ? "" : " tip=\"" + oneLine(tip) + "\"");
      if (seen.merge(line, 1, Integer::sum) == 1) {
        out.append(line).append('\n');
      }
    }
    return out.toString();
  }

  private static String oneLine(final String text) {
    final String flat = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
    return flat.length() > 160 ? flat.substring(0, 157) + "…" : flat;
  }
}
