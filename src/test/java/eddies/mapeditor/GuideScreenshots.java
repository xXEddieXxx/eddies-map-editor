package eddies.mapeditor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.accessibility.AccessibleContext;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Writes the user guide's screenshots (docs/guide/img) from the editor as it runs, each tool once,
 * with numbered callouts at the controls the guide describes. Not a test of the editor: it runs
 * only with -Dguide.screenshots=true, on a screen, and shows the map folder -Dguide.map names,
 * else a map of the tests. Beside each picture it leaves a list of the controls it saw
 * (build/guide-dump), to write the guide from.
 */
@EnabledIfSystemProperty(named = "guide.screenshots", matches = "true")
final class GuideScreenshots {
  private static final Path IMAGES = Path.of("docs/guide/img");
  private static final Path DUMP = Path.of("build/guide-dump");
  private static final int WIDTH = 1600;
  private static final int HEIGHT = 900;

  /** A numbered callout: the control found by its text, accessible name or tool tip, labelled. */
  private record Callout(String find, String label) {}

  /**
   * A page of the guide: its picture's name, the tool to show, the map it shows (the pictured
   * one, or the WW2 Global 1940 game of the tests, which has canals, politics, objectives,
   * technologies, actions and triggers) and its callouts in order.
   */
  private record Page(
      String slug, String tool, boolean ww2, String scrollTo, List<Callout> callouts) {
    Page(final String slug, final String tool, final List<Callout> callouts) {
      this(slug, tool, false, "", callouts);
    }

    Page(final String slug, final String tool, final boolean ww2, final List<Callout> callouts) {
      this(slug, tool, ww2, "", callouts);
    }

    /** The page scrolled so that the control found by the text is at the top. */
    Page(final String slug, final String tool, final String scrollTo, final List<Callout> callouts) {
      this(slug, tool, false, scrollTo, callouts);
    }
  }

  private static Callout c(final String find, final String label) {
    return new Callout(find, label);
  }

  /** A menu opened from a button of the tool shown, cut to the button and the menu. */
  private record Menu(String slug, String tool, String button) {}

  private static final List<Menu> MENUS =
      List.of(
          new Menu("menu-file", "Home", "File"),
          new Menu("menu-view", "Territories", "View: Game"),
          new Menu("menu-paint", "Territories", "Paint"),
          new Menu("menu-places", "Territories", "Places"),
          new Menu("menu-picture", "Territories", "Map picture"),
          new Menu("menu-sort", "Territories", "Sort: Name ▾"),
          new Menu("menu-many", "Territories", "Many territories at once: names and pictures as files, remove"));

  private static final List<Page> PAGES =
      List.of(
          new Page(
              "window",
              "Home",
              List.of(
                  c("File", "File menu"),
                  c("All saved", "Save state"),
                  c("Undo (Ctrl+Z)", "Undo"),
                  c("Redo (Ctrl+Y)", "Redo"),
                  c("Check", "Check"),
                  c("Play", "Play"),
                  c("Territories", "A tool in the sidebar"),
                  c("Advanced", "Advanced group"),
                  c("Guides & links", "Guides & links"),
                  c("Icons only", "Icons only"),
                  c("Help: Home", "Help ?"),
                  c("Hide", "Hide the hint"))),
          new Page(
              "home",
              "Home",
              List.of(
                  c("Rename…", "Rename"),
                  c("Check (F6)", "Next step"),
                  c("Steps to a playable map", "Steps"),
                  c("Problems", "Problems"),
                  c("Map overview", "Overview"),
                  c("Territories:", "Tool tiles"))),
          new Page(
              "territories-list",
              "Territories",
              "-",
              List.of(
                  c("Sort: Name ▾", "Sort"),
                  c("Many territories at once", "Many at once"),
                  c("253 territories", "Count"))),
          new Page(
              "territories",
              "Territories",
              List.of(
                  c("View: Game", "View"),
                  c("Select", "Select"),
                  c("Paint", "Paint"),
                  c("Places", "Places"),
                  c("Draw borders", "Draw borders"),
                  c("Zoom out (Ctrl+wheel)", "Zoom"),
                  c("Fit the map (Ctrl+0)", "Fit"),
                  c("Map picture", "Map picture menu"),
                  c("← All territories", "Back to the list"),
                  c("Owner", "Owner"),
                  c("PUs per turn", "PUs"),
                  c("Capital of its owner", "Capital"),
                  c("+ Add units", "Starting units"))),
          new Page(
              "territories-2",
              "Territories",
              "Rules (",
              List.of(
                  c("Rules (", "Rules"),
                  c("Move places", "Move places"),
                  c("Split with a line…", "Split"),
                  c("Merge into this…", "Merge"),
                  c("Find places again", "Find places"),
                  c("More options (", "More options"),
                  c("Edit in Connections", "Neighbours"),
                  c("Remove territory", "Remove"))),
          new Page(
              "connections",
              "Connections",
              List.of(
                  c("View: Connections", "View"),
                  c("Find neighbours again…", "Find neighbours again"))),
          new Page(
              "canals",
              "Canals",
              true,
              List.of(
                  c("← All canals", "Back"),
                  c("Name of the canal", "Name"),
                  c("Take 81 Sea Zone out of the canal", "Sea zones"),
                  c("Take Egypt out of the canal", "Land"),
                  c("Choose the unit types that pass freely", "Units that pass"),
                  c("Remove canal", "Remove"))),
          new Page("terrain-effects", "Terrain effects", List.of(c("Add effect…", "Add effect"))),
          new Page(
              "map-look",
              "Map look",
              List.of(
                  c("Territory names", "On the map"),
                  c("Size", "Unit size"),
                  c("Resize map…", "Resize map"),
                  c("Stack up to", "Stack"),
                  c("Picture size and count position (", "Picture size"),
                  c("A nation's units: tint, brightness", "Tint"))),
          new Page(
              "map-look-2",
              "Map look",
              "Relief",
              List.of(
                  c("Create relief…", "Create relief"),
                  c("Load picture…", "Load relief picture"),
                  c("Show the relief", "Show the relief"),
                  c("Blending", "Blending"),
                  c("Around left and right", "Scrolling"),
                  c("Small map (", "Small map"),
                  c("Update the small map", "Update the small map"),
                  c("Exported map picture (", "Exported picture"),
                  c("Mouse pointer (", "Mouse pointer"))),
          new Page(
              "nations",
              "Nations",
              List.of(
                  c("← All nations", "Back"),
                  c("Change color", "Color"),
                  c("Flag…", "Flag"),
                  c("Alliance", "Alliance"),
                  c("PUs at the start", "PUs at the start"),
                  c("Has no capital", "Has no capital"),
                  c("Played by default", "Played by"),
                  c("Can be switched off at the start", "Switched off"))),
          new Page(
              "nations-2",
              "Nations",
              "Unit pictures",
              List.of(
                  c("In the nation's color", "In its color"),
                  c("Import pictures…", "Import pictures"),
                  c("+ Add limit", "Stacking limits"),
                  c("Capitals needed to keep its PUs", "Capitals"),
                  c("Hands its units over to", "Sharing"),
                  c("Units it holds at the start", "Held units"))),
          new Page(
              "nations-3",
              "Nations",
              "Placing units",
              List.of(
                  c("May place in any land it owns", "Placing"),
                  c("A free unit per this many territories", "Free unit"),
                  c("Movement", "Moving and attacking"),
                  c("+ Add rule…", "Other rules"),
                  c("Remove nation", "Remove"))),
          new Page(
              "politics",
              "Politics",
              true,
              List.of(c("Germans and Russians: Neutrality", "A pair's cell"))),
          new Page(
              "objectives",
              "Objectives",
              true,
              List.of(
                  c("Objectives of which nation", "Nation"),
                  c("+ Add objective…", "Add objective"))),
          new Page(
              "technology",
              "Technology",
              true,
              List.of(
                  c("Open Game options", "Chosen in"),
                  c("+ Add technology…", "Add technology"),
                  c("+ Add bonus…", "What it gives"),
                  c("Rockets, war bonds, factories, airborne forces (", "More"),
                  c("Remove technology", "Remove"))),
          new Page(
              "units",
              "Units",
              List.of(
                  c("+ Add unit…", "Add unit"),
                  c("Upload picture…", "Upload picture"),
                  c("Cost", "Cost"),
                  c("Value", "Value"),
                  c("Attack", "Attack"),
                  c("Hit points", "Hit points"),
                  c("Land", "Kind"),
                  c("Draw it on the map", "Draw it"),
                  c("Search options", "Search options"),
                  c("More combat options (", "More options"),
                  c("Cannot hit", "Choose… lists"))),
          new Page(
              "units-2",
              "Units",
              "Gives support",
              List.of(
                  c("+ Add support", "Add support"),
                  c("Can blitz through empty territories", "Movement"),
                  c("Transport capacity", "Transport"))),
          new Page(
              "units-3",
              "Units",
              "Miscellaneous",
              List.of(
                  c("When placing", "Stacking limits"),
                  c("Not for land units (", "Not for this kind"),
                  c("Destroyer: cancels the first strike of subs", "Sea"))),
          new Page(
              "units-4",
              "Units",
              "Special",
              List.of(
                  c("Damage when captured", "Special"),
                  c("+ Add option…", "Add option"),
                  c("Remove unit", "Remove"))),
          new Page(
              "production",
              "Production",
              List.of(
                  c("+ Add list…", "Add list"),
                  c("Choose nations…", "Used by"),
                  c("Copy from…", "Copy from"),
                  c("Sold here (", "Show"),
                  c("+ Add rule…", "Add rule"),
                  c("Remove list", "Remove list"))),
          new Page(
              "turn-order",
              "Turn order",
              List.of(
                  c("The first round is round", "First round"),
                  c("+ Add step…", "Add step"),
                  c("Move the step up", "Move"),
                  c("Shows one nation's steps", "Nation filter"),
                  c("Shows the steps where this happens", "What filter"))),
          new Page(
              "game-options",
              "Game options",
              List.of(
                  c("Search options", "Search"),
                  c("Only what players may change", "Only changeable"),
                  c("+ Add option…", "Add option"),
                  c("Dice sides", "A number option"),
                  c("8 Capital Victory", "A yes/no option"),
                  c("8 Capital Victory: Players may change it before a game", "Lock"),
                  c("Remove option from the game", "Remove"),
                  c("The least and most players may set", "Range"))),
          new Page(
              "actions",
              "Actions",
              true,
              List.of(
                  c("+ Add action…", "Add action"),
                  c("Button", "Button text"),
                  c("Text key", "Text key"),
                  c("+ Add condition…", "Conditions"))),
          new Page(
              "actions-2",
              "Actions",
              true,
              "What it does",
              List.of(
                  c("+ Add change", "Changes"),
                  c("+ Add cost", "Costs"),
                  c("Attempts per turn", "Attempts"),
                  c("Nobody", "Must be accepted by"),
                  c("Remove action", "Remove"))),
          new Page(
              "triggers",
              "Triggers",
              true,
              List.of(
                  c("Group the triggers by", "Group by"),
                  c("+ Add trigger…", "Add trigger"))),
          new Page(
              "game-notes",
              "Game notes",
              List.of(
                  c("Bold (Ctrl+B)", "Bold"),
                  c("Heading", "Heading"),
                  c("List", "List"),
                  c("Link…", "Link"),
                  c("Picture…", "Picture"),
                  c("Text", "Text"),
                  c("Split", "Split"),
                  c("Preview", "Preview"))),
          new Page("texts", "Texts", List.of(c("+ Add text…", "Add text"))),
          new Page(
              "sounds",
              "Sounds",
              List.of(c("All 48", "All"), c("Your own 3", "Your own"), c("Folder", "Default folder"))),
          new Page(
              "publish",
              "Publish",
              List.of(
                  c("Map name", "Map name"),
                  c("Raise", "Version"),
                  c("5 of 7 are ready.", "Before you upload"),
                  c("Description", "Description"))),
          new Page(
              "publish-2",
              "Publish",
              "Upload",
              List.of(c("Open github.com in the browser", "Upload steps"))),
          new Page(
              "attachments",
              "Attachments",
              List.of(
                  c("Which kind of attachments are listed", "Kind"),
                  c("+ Add attachment…", "Add attachment"),
                  c("Attached to", "Attached to"),
                  c("+ Add option…", "Add option"),
                  c("Remove attachment", "Remove"))),
          new Page(
              "files",
              "Files",
              List.of(
                  c("More", "More"),
                  c("map.yml", "A file"),
                  c("Games…", "Games"),
                  c("Edit in Territories", "Edit in a tool"))));

  @Test
  void writeScreenshots() throws Exception {
    Files.createDirectories(IMAGES);
    Files.createDirectories(DUMP);
    final MapProject project = MapProject.open(givenMapXml());
    final MapProject ww2 = MapProject.open(givenWw2Xml());
    final MapMakerWindow[] window = new MapMakerWindow[1];
    SwingUtilities.invokeAndWait(
        () -> {
          // the dark theme, as the editor shows it on a dark TripleA; -Dguide.dark=false for light
          Theme.install();
          if (!"false".equals(System.getProperty("guide.dark", "true"))) {
            com.formdev.flatlaf.FlatDarkLaf.setup();
          } else {
            com.formdev.flatlaf.FlatLightLaf.setup();
          }
          window[0] = new MapMakerWindow();
          window[0].setSize(WIDTH, HEIGHT);
          window[0].setLocation(0, 0);
          window[0].setVisible(true);
          window[0].showProject(project);
        });
    try {
      settle(2500);
      // every "More" row open, so the guide sees every option
      SwingUtilities.invokeAndWait(
          () -> {
            if (!Folds.showsAll()) {
              Folds.toggleShowAll();
              project.showAgain();
            }
          });
      boolean showingWw2 = false;
      for (final Page page : PAGES) {
        if (page.ww2() != showingWw2) {
          showingWw2 = page.ww2();
          final MapProject shown = showingWw2 ? ww2 : project;
          SwingUtilities.invokeAndWait(() -> window[0].showProject(shown));
          settle(2000);
        }
        SwingUtilities.invokeAndWait(() -> click(window[0], page.tool()));
        settle(900);
        if (!"-".equals(page.scrollTo())) {
          SwingUtilities.invokeAndWait(() -> chooseFirst(window[0]));
          settle(900);
        }
        if (!page.scrollTo().isEmpty() && !"-".equals(page.scrollTo())) {
          SwingUtilities.invokeAndWait(() -> scrollTo(window[0], page.scrollTo()));
          settle(400);
        }
        SwingUtilities.invokeAndWait(() -> shootQuietly(window[0], page));
      }
      if (showingWw2) {
        SwingUtilities.invokeAndWait(() -> window[0].showProject(project));
        settle(2000);
      }
      for (final Menu menu : MENUS) {
        SwingUtilities.invokeAndWait(() -> click(window[0], menu.tool()));
        settle(600);
        SwingUtilities.invokeAndWait(() -> shootMenuQuietly(window[0], menu));
        settle(200);
      }
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              shootComponent(new ResizeDialog(project.getFiles()).content(), "dialog-resize", 520);
            } catch (final IOException e) {
              throw new IllegalStateException(e);
            }
          });
      shootWizard();
    } finally {
      SwingUtilities.invokeAndWait(window[0]::dispose);
    }
  }

  private static void shootQuietly(final JFrame window, final Page page) {
    try {
      shoot(window, page);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The first row of the tool's list chosen as a click chooses it, so the tool shows it. */
  private static void chooseFirst(final JFrame window) {
    all(window.getContentPane())
        .filter(SearchList.class::isInstance)
        .filter(Component::isShowing)
        .findFirst()
        .flatMap(list -> all(list).filter(javax.swing.JList.class::isInstance).findFirst())
        .map(javax.swing.JList.class::cast)
        .ifPresent(
            list -> {
              for (int i = 0; i < list.getModel().getSize(); i++) {
                final String row = String.valueOf(list.getModel().getElementAt(i));
                if (!row.startsWith("▸") && !row.startsWith("▾") && !row.startsWith("\u0000")) {
                  list.setSelectedIndex(i);
                  return;
                }
              }
            });
  }

  /** The menu a button opens, with the button, cut from the window with a margin. */
  private static void shootMenuQuietly(final JFrame window, final Menu menu) {
    try {
      final JComponent content = (JComponent) window.getContentPane();
      final Optional<Component> button =
          find(content, menu.button()).filter(AbstractButton.class::isInstance);
      if (button.isEmpty()) {
        return;
      }
      ((AbstractButton) button.get()).doClick();
      settle(400);
      final Optional<Component> popup =
          all(window.getRootPane()).filter(JPopupMenu.class::isInstance).filter(Component::isShowing).findFirst();
      final JComponent root = window.getRootPane();
      final BufferedImage whole =
          new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
      final Graphics2D g = whole.createGraphics();
      root.printAll(g);
      g.dispose();
      final Rectangle at =
          SwingUtilities.convertRectangle(button.get().getParent(), button.get().getBounds(), root);
      final Rectangle cut = new Rectangle(at);
      popup.ifPresent(
          m -> cut.add(SwingUtilities.convertRectangle(m.getParent(), m.getBounds(), root)));
      cut.grow(24, 24);
      final Rectangle within = cut.intersection(new Rectangle(whole.getWidth(), whole.getHeight()));
      ImageIO.write(
          whole.getSubimage(within.x, within.y, within.width, within.height),
          "png",
          IMAGES.resolve(menu.slug() + ".png").toFile());
      if (popup.isPresent()) {
        Files.writeString(DUMP.resolve(menu.slug() + ".txt"), dump((Container) popup.get()));
      }
      javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
      popup.ifPresent(m -> m.setVisible(false));
      // a toggle pressed to open its menu is released again
      if (button.get() instanceof javax.swing.JToggleButton toggle && toggle.isSelected()) {
        find(content, "Select").filter(AbstractButton.class::isInstance).ifPresent(b -> ((AbstractButton) b).doClick());
      }
    } catch (final IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The New map wizard's three steps with the guide's example picture, which is written beside
   * the pictures for readers to follow the Getting started page with.
   */
  private static void shootWizard() throws Exception {
    final Path example = IMAGES.resolve("first-map.png");
    ImageIO.write(examplePicture(), "png", example.toFile());
    final NewMapWizard[] wizard = new NewMapWizard[1];
    final JFrame holder = new JFrame();
    SwingUtilities.invokeAndWait(
        () -> {
          wizard[0] =
              new NewMapWizard(
                  Path.of(System.getProperty("user.home"), "triplea", "downloadedMaps"),
                  made -> {});
          holder.setUndecorated(true);
          holder.getContentPane().add(wizard[0]);
          holder.setSize(760, 560);
          holder.setLocation(0, 0);
          holder.setVisible(true);
        });
    try {
      onSwing(() -> set(wizard[0], "name", "Island Wars"));
      onSwing(() -> shootShown(holder, "dialog-new-map"));
      onSwing(() -> wizard[0].next.doClick());
      onSwing(() -> call(wizard[0], "readPicture", Path.class, example));
      final java.lang.reflect.Field progress = NewMapWizard.class.getDeclaredField("progress");
      progress.setAccessible(true);
      final long until = System.currentTimeMillis() + 30_000;
      Thread.sleep(500);
      while (System.currentTimeMillis() < until
          && ((Component) progress.get(wizard[0])).isVisible()) {
        Thread.sleep(200);
      }
      Thread.sleep(500);
      onSwing(() -> shootShown(holder, "dialog-new-map-2"));
      onSwing(() -> wizard[0].next.doClick());
      Thread.sleep(300);
      onSwing(() -> set(wizard[0], "nations", "Red\nBlue"));
      Thread.sleep(300);
      onSwing(() -> shootShown(holder, "dialog-new-map-3"));
    } finally {
      SwingUtilities.invokeAndWait(holder::dispose);
    }
  }

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

  private static void shootShown(final JFrame holder, final String slug) throws IOException {
    holder.validate();
    holder.repaint();
    final JComponent content = (JComponent) holder.getContentPane();
    final BufferedImage image =
        new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    content.printAll(g);
    g.dispose();
    ImageIO.write(image, "png", IMAGES.resolve(slug + ".png").toFile());
    Files.writeString(DUMP.resolve(slug + ".txt"), dump(content));
  }

  private static void set(final Object target, final String field, final String text)
      throws ReflectiveOperationException {
    final java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
    f.setAccessible(true);
    ((javax.swing.text.JTextComponent) f.get(target)).setText(text);
  }

  private static void call(
      final Object target, final String method, final Class<?> type, final Object argument)
      throws ReflectiveOperationException {
    final java.lang.reflect.Method m = target.getClass().getDeclaredMethod(method, type);
    m.setAccessible(true);
    m.invoke(target, argument);
  }

  /**
   * The guide's example picture: an island world of 800 x 600 with two islands of three land
   * territories each, drawn in flat colors with black borders, as a map picture is drawn.
   */
  private static BufferedImage examplePicture() {
    final BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    g.setColor(new Color(70, 120, 200));
    g.fillRect(0, 0, 800, 600);
    // two islands, each a rounded shape cut into three by lines
    final Color land = new Color(236, 224, 190);
    final int[][] west = {{80, 120}, {340, 90}, {380, 300}, {300, 480}, {110, 450}, {60, 280}};
    final int[][] east = {{450, 150}, {720, 110}, {760, 330}, {680, 520}, {470, 480}, {420, 320}};
    for (final int[][] island : List.of(west, east)) {
      final java.awt.Polygon shape = new java.awt.Polygon();
      for (final int[] point : island) {
        shape.addPoint(point[0], point[1]);
      }
      g.setColor(land);
      g.fillPolygon(shape);
      g.setColor(Color.BLACK);
      g.setStroke(new BasicStroke(3f));
      g.drawPolygon(shape);
    }
    g.setColor(Color.BLACK);
    g.setStroke(new BasicStroke(3f));
    // the west island: a line across and one down from it
    g.drawLine(60, 280, 380, 300);
    g.drawLine(220, 290, 212, 468);
    // the east island: a line down, and one across its eastern half only: three territories
    g.drawLine(590, 126, 581, 503);
    g.drawLine(585, 320, 760, 330);
    g.dispose();
    return image;
  }

  /** A component on its own, eg a dialog's content, laid out at the width. */
  private static void shootComponent(final JComponent component, final String slug, final int width)
      throws IOException {
    final JFrame holder = new JFrame();
    holder.setUndecorated(true);
    holder.getContentPane().add(component);
    holder.pack();
    holder.setSize(width, Math.max(component.getPreferredSize().height + 20, holder.getHeight()));
    holder.validate();
    final BufferedImage image =
        new BufferedImage(holder.getWidth(), holder.getHeight(), BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setColor(javax.swing.UIManager.getColor("Panel.background"));
    g.fillRect(0, 0, image.getWidth(), image.getHeight());
    holder.getContentPane().printAll(g);
    g.dispose();
    ImageIO.write(image, "png", IMAGES.resolve(slug + ".png").toFile());
    Files.writeString(DUMP.resolve(slug + ".txt"), dump(holder.getContentPane()));
    holder.dispose();
  }

  /** The WW2 Global 1940 game of the tests, in a folder of its own: no picture, every rule. */
  private static Path givenWw2Xml() throws IOException {
    final Path folder = Files.createTempDirectory("eddies-guide-ww2");
    final Path games = Files.createDirectories(folder.resolve("games"));
    final Path xml = games.resolve("ww2global40.xml");
    try (java.io.InputStream original =
        GuideScreenshots.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      Files.copy(original, xml);
    }
    return xml;
  }

  private static Path givenMapXml() throws IOException {
    final String named = System.getProperty("guide.map", "");
    final Path folder =
        named.isBlank()
            ? Path.of(System.getProperty("user.home"), "triplea/downloadedMaps/270bc/map")
            : Path.of(named);
    try (Stream<Path> games = Files.list(folder.resolve("games"))) {
      return games.filter(f -> f.toString().endsWith(".xml")).sorted().findFirst().orElseThrow();
    }
  }

  private static void settle(final long millis) throws InterruptedException {
    Thread.sleep(millis);
  }

  /** Clicks the sidebar's row of the tool. */
  private static void click(final JFrame window, final String tool) {
    all(window.getContentPane())
        .filter(AbstractButton.class::isInstance)
        .map(AbstractButton.class::cast)
        .filter(b -> tool.equals(b.getAccessibleContext().getAccessibleName()))
        .filter(b -> "Sidebar".equals(ownerName(b)))
        .findFirst()
        .or(
            () ->
                all(window.getContentPane())
                    .filter(AbstractButton.class::isInstance)
                    .map(AbstractButton.class::cast)
                    .filter(b -> tool.equals(b.getAccessibleContext().getAccessibleName()))
                    .findFirst())
        .ifPresent(AbstractButton::doClick);
  }

  private static String ownerName(final Component component) {
    for (Component c = component; c != null; c = c.getParent()) {
      if (c instanceof Sidebar) {
        return "Sidebar";
      }
    }
    return "";
  }

  private static void shoot(final JFrame window, final Page page) throws IOException {
    final JComponent content = (JComponent) window.getContentPane();
    final BufferedImage image =
        new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    content.printAll(g);
    g.dispose();
    final List<Rectangle> marked = new ArrayList<>();
    int number = 1;
    for (final Callout callout : page.callouts()) {
      final Optional<Component> found = find(content, callout.find());
      if (found.isPresent()) {
        final Rectangle bounds =
            SwingUtilities.convertRectangle(
                found.get().getParent(), found.get().getBounds(), content);
        drawCallout(image, bounds, number, marked);
      }
      number++;
    }
    ImageIO.write(image, "png", IMAGES.resolve(page.slug() + ".png").toFile());
    Files.writeString(DUMP.resolve(page.slug() + ".txt"), dump(content));
  }

  /**
   * The first shown component with the text as its text or accessible name, else the first
   * whose text, accessible name or tool tip starts with it; the sidebar's rows and the hint
   * line's parts are skipped unless asked for by their own words.
   */
  private static Optional<Component> find(final Container in, final String text) {
    final Predicate<Component> exact =
        c -> c.isShowing() && (text.equals(textOf(c)) || text.equals(accessibleNameOf(c)));
    final Predicate<Component> starts =
        c ->
            c.isShowing()
                && (textOf(c).startsWith(text)
                    || accessibleNameOf(c).startsWith(text)
                    || (c instanceof JComponent j
                        && j.getToolTipText() != null
                        && j.getToolTipText().startsWith(text)));
    final List<Component> shown = all(in).toList();
    return shown.stream()
        .filter(exact)
        .filter(c -> !(c instanceof JLabel) || shown.stream().noneMatch(o -> exact.test(o) && !(o instanceof JLabel)))
        .findFirst()
        .or(() -> shown.stream().filter(starts).findFirst());
  }

  /** Scrolls the control found by the text to the top of its scroll pane. */
  private static void scrollTo(final JFrame window, final String text) {
    find(window.getContentPane(), text)
        .ifPresent(
            c -> {
              if (c instanceof JComponent j) {
                final javax.swing.JViewport viewport =
                    (javax.swing.JViewport)
                        SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, j);
                if (viewport != null) {
                  final Rectangle at =
                      SwingUtilities.convertRectangle(j.getParent(), j.getBounds(), viewport.getView());
                  viewport.setViewPosition(new Point(0, Math.max(0, at.y - 12)));
                }
              }
            });
  }

  private static Stream<Component> all(final Component component) {
    final Stream<Component> own = Stream.of(component);
    return component instanceof Container container
        ? Stream.concat(own, Stream.of(container.getComponents()).flatMap(GuideScreenshots::all))
        : own;
  }

  private static String textOf(final Component c) {
    if (c instanceof AbstractButton b) {
      return Optional.ofNullable(b.getText()).orElse("");
    }
    if (c instanceof JLabel l) {
      return Optional.ofNullable(l.getText()).orElse("");
    }
    return "";
  }

  private static String accessibleNameOf(final Component c) {
    final AccessibleContext context = c.getAccessibleContext();
    return context == null ? "" : Optional.ofNullable(context.getAccessibleName()).orElse("");
  }

  /** Every shown control with a word on it, with where it is: to write the guide from. */
  private static String dump(final Container content) {
    final StringBuilder out = new StringBuilder();
    final Map<String, Integer> seen = new LinkedHashMap<>();
    all(content)
        .filter(c -> c.isShowing() || content instanceof JPopupMenu)
        .forEach(
            c -> {
              final String kind = c.getClass().getSimpleName();
              final String text = textOf(c);
              final String name = accessibleNameOf(c);
              final String tip = c instanceof JComponent j ? Optional.ofNullable(j.getToolTipText()).orElse("") : "";
              final String typed = c instanceof JTextComponent t ? Optional.ofNullable(t.getText()).orElse("") : "";
              if (text.isBlank() && name.isBlank() && tip.isBlank() && typed.isBlank()) {
                return;
              }
              final Rectangle at = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), content);
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
                      + (text.isBlank() ? "" : " text=\"" + oneLine(text) + "\"")
                      + (name.isBlank() || name.equals(text) ? "" : " name=\"" + oneLine(name) + "\"")
                      + (typed.isBlank() ? "" : " typed=\"" + oneLine(typed) + "\"")
                      + (tip.isBlank() ? "" : " tip=\"" + oneLine(tip) + "\"");
              if (seen.merge(line, 1, Integer::sum) == 1) {
                out.append(line).append('\n');
              }
            });
    return out.toString();
  }

  private static String oneLine(final String text) {
    final String flat = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
    return flat.length() > 160 ? flat.substring(0, 157) + "…" : flat;
  }

  /**
   * A red ring around the control and a numbered badge beside it, above it where there is room,
   * else below, right or left, never over another ring or badge, with a line between.
   */
  private static void drawCallout(
      final BufferedImage image, final Rectangle bounds, final int number, final List<Rectangle> marked) {
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    final Color red = new Color(230, 60, 50);
    final Rectangle ring = new Rectangle(bounds.x - 3, bounds.y - 3, bounds.width + 6, bounds.height + 6);
    g.setColor(red);
    g.setStroke(new BasicStroke(2.5f));
    g.drawRoundRect(ring.x, ring.y, ring.width, ring.height, 8, 8);
    final int size = 24;
    final int gap = 10;
    final List<Point> places =
        List.of(
            new Point(ring.x + ring.width / 2 - size / 2, ring.y - size - gap),
            new Point(ring.x + ring.width / 2 - size / 2, ring.y + ring.height + gap),
            new Point(ring.x + ring.width + gap, ring.y + ring.height / 2 - size / 2),
            new Point(ring.x - size - gap, ring.y + ring.height / 2 - size / 2));
    final Rectangle within = new Rectangle(2, 2, image.getWidth() - 4, image.getHeight() - 4);
    Rectangle badge = null;
    for (final Point place : places) {
      final Rectangle candidate = new Rectangle(place.x, place.y, size, size);
      if (within.contains(candidate) && marked.stream().noneMatch(candidate::intersects)) {
        badge = candidate;
        break;
      }
    }
    if (badge == null) {
      // no room: below, pushed right until free
      badge = new Rectangle(ring.x, ring.y + ring.height + gap, size, size);
      while (marked.stream().anyMatch(badge::intersects) && badge.x + size < image.getWidth()) {
        badge.translate(size + 4, 0);
      }
    }
    marked.add(ring);
    marked.add(badge);
    final Point from = new Point(badge.x + size / 2, badge.y + size / 2);
    final Point to =
        new Point(
            Math.max(ring.x, Math.min(ring.x + ring.width, from.x)),
            Math.max(ring.y, Math.min(ring.y + ring.height, from.y)));
    g.setStroke(new BasicStroke(2f));
    g.drawLine(from.x, from.y, to.x, to.y);
    g.fillOval(badge.x, badge.y, size, size);
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
    final String digits = String.valueOf(number);
    final int textWidth = g.getFontMetrics().stringWidth(digits);
    g.drawString(digits, badge.x + (size - textWidth) / 2, badge.y + size / 2 + 5);
    g.dispose();
  }
}
