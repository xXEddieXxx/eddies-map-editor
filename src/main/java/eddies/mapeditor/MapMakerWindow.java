package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.SystemFileChooser;
import games.strategy.engine.ClientFileSystemHelper;
import games.strategy.triplea.EngineImageLoader;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import lombok.extern.slf4j.Slf4j;
import org.triplea.game.client.HeadedGameRunner;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.FileChooser;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapPicture.DetectedTerritory;
import eddies.mapeditor.MapProject.NewMap;
import eddies.mapeditor.MapProject.RedrawChoices;
import eddies.mapeditor.Theme.Status;

/**
 * The map maker: one window for all changes to a map. It opens on a {@link StartPage} to open a
 * map's folder, one of the maps opened last, or create one with {@link NewMapWizard} (name,
 * picture with its territories, nations); a new map is saved as a map folder at once. On the left
 * the tools by name in their groups ({@link Sidebar}); the chosen tool takes the rest, under its
 * title and "?": a tool that works on the map shows it left of its panel. At the top the map's
 * name (its menu), whether all is saved, its problems and the buttons for the whole map.
 */
@Slf4j
public final class MapMakerWindow extends JFrame {
  @Serial private static final long serialVersionUID = 1L;

  private static final int INSPECTOR_WIDTH = 340;
  private static final int INSPECTOR_MIN_WIDTH = 300;
  private static final int NAME_MIN_WIDTH = 160;
  private static final String SAVE = "Save";

  /** How far Check is with the map as it is now. */
  enum Check {
    NOT_YET,
    RUNNING,
    DONE
  }

  /** The header's problems pill: its words and how it looks. */
  record Pill(String text, Status status) {}

  // nothing to save or leave until a map is opened or created; set here, before the Files view
  // below asks for its folder
  private transient MapProject project = MapProject.newEmpty();
  // a map's own game file, kept as .bak on the first save; a map made here has no older version
  private boolean openedFromFile;
  private transient @Nullable HomeEditor home;
  private final transient Map<String, Editor> editors = new LinkedHashMap<>();
  // the place of the shared map in each tool that works on it
  private final transient Map<Editor, JPanel> mapSlots = new HashMap<>();
  private final JButton mapButton = new JButton();
  private final JLabel saveState = new JLabel();
  private final JButton pill = new JButton();
  private final JPanel workspace = new JPanel(new CardLayout());
  private final JButton saveButton = new JButton(SAVE, MakerIcon.small(Glyph.SAVE));
  private final JButton playButton = new JButton("Play", MakerIcon.small(Glyph.PLAYTEST));
  private final JButton undoButton =
      Theme.newIconButton(Glyph.UNDO, "Undo (Ctrl+Z)", this::undo);
  private final JButton redoButton =
      Theme.newIconButton(Glyph.REDO, "Redo (Ctrl+Y)", this::redo);
  private transient @Nullable MapView map;
  private transient @Nullable TerritoriesEditor territories;
  private transient @Nullable Sidebar sidebar;
  // the game's name after the map's, when the map has more than one
  private transient Optional<String> gameName = Optional.empty();
  private Check check = Check.NOT_YET;
  // one for the window, so the maps added to copy from and the open file stay across reloads
  private final transient FilesEditor files =
      new FilesEditor(() -> project, this::mayWriteMapFile, this::reloadFromFiles, this::showTool);
  private boolean mapShown;
  // a file saved in Files does not read: the map in memory is older than its files
  private boolean filesAhead;

  MapMakerWindow() {
    super("Eddie's Map Editor");
    // TripleA's icon in all its sizes, so the title bar and the task bar each take a sharp one
    setIconImages(
        IntStream.of(16, 32, 48, 64, 128, 256)
            .mapToObj(
                size ->
                    EngineImageLoader.loadImage(
                        "icons", "triplea_icon_" + size + "_" + size + ".png"))
            .toList());
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(final WindowEvent e) {
            if (mayLeaveProject()) {
              dispose();
            }
          }
        });
    // the sidebar shows only icons in a narrow window
    addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(final ComponentEvent e) {
            Optional.ofNullable(sidebar).ifPresent(s -> s.fitTo(getWidth()));
          }
        });
    setMinimumSize(new Dimension(1000, 640));
    newHeaderButtons();
    bindKeys();
    showStart();
  }

  /** Starts the editor on its own, with TripleA's settings and look. */
  static void start() {
    // TripleA's settings hold the maps folder; its look gives the editor its colors and styles
    HeadedGameRunner.initializeClientSettingAndLogging();
    // before the look and feel is set up, which reads the editor's colors then
    Theme.install();
    HeadedGameRunner.initializeLookAndFeel();
    SwingUtilities.invokeLater(MapMakerWindow::openStandalone);
  }

  /** Opens the editor on its start page; closing it ends the program. */
  private static void openStandalone() {
    final MapMakerWindow window = new MapMakerWindow();
    window.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(final WindowEvent e) {
            // TripleA's settings and logging keep threads that would keep the program running
            System.exit(0);
          }
        });
    window.setSize(1500, 920);
    window.setLocationRelativeTo(null);
    window.setExtendedState(JFrame.MAXIMIZED_BOTH);
    window.setVisible(true);
  }

  // ---- new, opening and leaving a map

  /** The page the map maker opens with: open a map, one opened last, or a new one. */
  private void showStart() {
    mapShown = false;
    sidebar = null;
    setTitle("Eddie's Map Editor");
    setContentPane(new StartPage(this::chooseAndOpen, this::createMap, this::openProject));
    revalidate();
    repaint();
  }

  /** Back to the start page, asking first about unsaved changes. */
  private void closeMap() {
    if (!mayLeaveProject()) {
      return;
    }
    project = MapProject.newEmpty();
    editors.clear();
    mapSlots.clear();
    workspace.removeAll();
    home = null;
    map = null;
    territories = null;
    filesAhead = false;
    // the map's typed text was dropped when the player agreed to leave it
    files.forgetText();
    showStart();
  }

  /** The new-map wizard; the map it makes opens on Home, with what was found in its picture. */
  private void createMap() {
    if (mayLeaveProject()) {
      NewMapWizard.show(
          this,
          made -> {
            opened(made);
            // Home tells what was found and left out; the pill tells Check's result
            Optional.ofNullable(home).ifPresent(shown -> shown.welcome(made));
            if (made.project().getFiles().hasOutlines()) {
              check(problems -> {});
            }
          });
    }
  }

  /** Shows a map just made and saved; it has no older version of its game file to keep. */
  private void opened(final NewMap made) {
    showProject(made.project());
    openedFromFile = false;
    made.project().getXmlFile().ifPresent(RecentMaps::add);
  }

  /** The system's own folder dialog for a map's folder, then its game, asking when it has more. */
  private void chooseAndOpen() {
    if (!mayLeaveProject()) {
      return;
    }
    chooseFolder(
            this,
            "Open a map folder",
            project
                .getFolder()
                .map(MapFolder::rootOf)
                // the folder around the map; a map at the top of a drive starts in itself
                .map(root -> Optional.ofNullable(root.getParent()).orElse(root))
                .orElseGet(ClientFileSystemHelper::getUserMapsFolder))
        .ifPresent(this::openMapFolder);
  }

  /**
   * Before a file of the map is written in Files: the changes made in the other editors are saved
   * first or dropped, as the player chooses, since the map is read again from its files after.
   */
  private boolean mayWriteMapFile() {
    editors.values().forEach(Editor::commit);
    // with the files ahead, the next file saved is the fix: nothing of the old map to keep
    if (filesAhead || !project.hasUnsavedChanges()) {
      return true;
    }
    return switch (Dialogs.askToSave(
        this,
        "Save file",
        "Save the map's changes first?\nSaving this file reads the map again from its files;"
            + " Don't save drops the changes made in the other tools.")) {
      case SAVE -> save();
      case DONT_SAVE -> true;
      case CANCEL -> false;
    };
  }

  /** Reads the open map again from its files after one was changed in Files, staying there. */
  private void reloadFromFiles() {
    final Optional<Path> xml = project.getXmlFile();
    if (xml.isEmpty()) {
      return;
    }
    try {
      showProject(MapProject.open(xml.get()));
      // back to the file just saved, which showProject read again
      showFiles();
    } catch (final Exception e) {
      log.info("Could not read the map again from {}", xml.get(), e);
      // the map as it was must not be written over the files the player changed
      filesAhead = true;
      Dialogs.warn(
          this,
          "Files",
          "The file is saved, but the map could not be read from its files\n"
              + e.getMessage()
              + "\nFix it in Files. Until the map reads again, the other tools show it as it"
              + " was and nothing is saved from them.");
    }
  }

  /** The system's own dialog to choose a folder. */
  static Optional<Path> chooseFolder(
      final Component parent, final String dialogTitle, final Path start) {
    final SystemFileChooser chooser = new SystemFileChooser(start.toFile());
    chooser.setDialogTitle(dialogTitle);
    chooser.setFileSelectionMode(SystemFileChooser.DIRECTORIES_ONLY);
    return chooser.showOpenDialog(parent) == SystemFileChooser.APPROVE_OPTION
        ? Optional.of(chooser.getSelectedFile().toPath())
        : Optional.empty();
  }

  private void openMapFolder(final Path folder) {
    final Map<String, Path> games = gamesOf(folder);
    if (games.isEmpty()) {
      Dialogs.warn(
          this,
          "Open",
          "No game found in "
              + folder
              + "\nA map folder has its map.yml and its games in a games folder.");
      return;
    }
    final List<String> names = List.copyOf(games.keySet());
    final Optional<String> game =
        names.size() == 1
            ? Optional.of(names.getFirst())
            : Dialogs.pick(this, "Open", "Which game of the map?", names, name -> name, "Open");
    game.map(games::get).ifPresent(this::openProject);
  }

  /** The games of the map in this folder; none when its folders cannot be read. */
  private static Map<String, Path> gamesOf(final Path folder) {
    try {
      return MapFolder.gamesOf(folder);
    } catch (final IOException e) {
      // the map maker says no game was found; a warning would show a second dialog
      log.info("Could not look for games in {}", folder, e);
      return Map.of();
    }
  }

  /**
   * The system's own dialog to pick a file of these kinds; Windows filters by the pattern given as
   * the file name, the other systems by the filter.
   */
  static Optional<Path> chooseFileToOpen(
      final Component parent,
      final String dialogTitle,
      final Path folder,
      final String... extensions) {
    return FileChooser.builder()
        .parent(
            parent instanceof Frame frame
                ? frame
                : (Frame) SwingUtilities.getAncestorOfClass(Frame.class, parent))
        .title(dialogTitle)
        .mode(FileDialog.LOAD)
        .directory(folder)
        .fileName(Arrays.stream(extensions).map(e -> "*." + e).collect(Collectors.joining(";")))
        // no kinds given: any file
        .filenameFilter(
            (dir, name) ->
                extensions.length == 0
                    || Arrays.stream(extensions)
                        .anyMatch(e -> name.toLowerCase(Locale.ROOT).endsWith("." + e)))
        .build()
        .chooseFile();
  }

  private void openProject(final Path xml) {
    try {
      showProject(MapProject.open(xml));
      RecentMaps.add(xml);
    } catch (final Exception e) {
      log.warn("Could not open {} in the map maker", xml, e);
      Dialogs.warn(
          this, "Open", "Could not read " + xml.getFileName() + " as a game\n" + e.getMessage());
    }
  }

  /** A copy of the saved game under a new name beside it, opened. */
  private void newGame() {
    final Optional<Path> template = project.getXmlFile();
    if (template.isEmpty() || !mayLeaveProject()) {
      return;
    }
    final Map<String, Path> games = gamesOf(template.get().getParent());
    final Optional<String> name =
        Dialogs.askName(
            this,
            "New game",
            "Name",
            "My game",
            "Create",
            typed ->
                games.containsKey(typed)
                    ? Optional.of("A game of that name exists")
                    : Optional.empty());
    if (name.isPresent()) {
      try {
        openProject(MapFolder.newGame(template.get(), name.get()));
      } catch (final Exception e) {
        Dialogs.warn(this, "New game", "Could not make the game\n" + e.getMessage());
      }
    }
  }

  /** Asks whether to save first; false if the player cancelled. */
  private boolean mayLeaveProject() {
    // text typed in Files is not part of the map until it is saved there
    if (!files.mayDropText()) {
      return false;
    }
    editors.values().forEach(Editor::commit);
    // with the files ahead, the editors' changes cannot be saved: they are left with the map
    if (filesAhead || !project.hasUnsavedChanges()) {
      return true;
    }
    return switch (Dialogs.askToSave(
        this, "Unsaved changes", "Save the changes to " + project.name() + " first?")) {
      case SAVE -> save();
      case DONT_SAVE -> true;
      case CANCEL -> false;
    };
  }

  // ---- the picture

  /** Asks for a picture of the map; the territories stay until Make territories. */
  private void choosePicture() {
    chooseFileToOpen(
            this,
            "Choose the picture of your map",
            Path.of(System.getProperty("user.home")),
            "png",
            "jpg",
            "jpeg",
            "gif")
        .ifPresent(this::loadPicture);
  }

  /** Reads the picture in the background, then takes it as the map's. */
  private void loadPicture(final Path file) {
    final MapProject loadingInto = project;
    inBackground(
        "Could not load the picture",
        () -> MapPicture.read(file),
        image -> {
          if (loadingInto == project) {
            loadingInto.loadPicture(image);
          }
        });
  }

  /**
   * Makes the territories from the map's picture in the background, first asking whether to replace
   * the ones there are, then takes them as one step.
   */
  private void findTerritories() {
    final Optional<BufferedImage> picture = project.getFiles().picture();
    if (picture.isEmpty()
        || (project.getFiles().hasOutlines()
            && !Dialogs.confirm(
                this,
                "Make territories",
                "Make the territories again?\nThe territories and connections found replace the"
                    + " ones the map has now, with their owners and units. Undo takes it back.",
                "Make again"))) {
      return;
    }
    final MapProject findingIn = project;
    inBackground(
        "Could not make the territories",
        () -> MapPicture.detect(picture.get()),
        detected -> {
          // a picture loaded meanwhile keeps its territories until Make territories is pressed
          if (findingIn == project && findingIn.getFiles().picture().equals(picture)) {
            findingIn.loadPicture(picture.get(), detected);
            showFound(findingIn.game());
          }
        });
  }

  /**
   * Asks for a picture with changed borders, finds its areas in the background, then redraws the
   * territories' borders from it as one step and tells which kept theirs.
   */
  private void redrawBorders() {
    if (!RedrawDialog.howTo(this)) {
      return;
    }
    final Optional<Path> file =
        chooseFileToOpen(
            this,
            "Choose the picture with the new borders",
            project.getFolder().orElseGet(() -> Path.of(System.getProperty("user.home"))),
            "png",
            "jpg",
            "jpeg",
            "gif");
    if (file.isEmpty()) {
      return;
    }
    redrawFrom(() -> MapPicture.read(file.get()));
  }

  /** The map's borders as the picture shows them: new areas, merged ones and moved borders. */
  private void redrawFrom(final Callable<BufferedImage> picture) {
    final MapProject redrawing = project;
    final MapFiles from = redrawing.getFiles();
    inBackground(
        "Could not redraw the borders",
        () -> {
          final BufferedImage image = picture.call();
          return from.withBordersFrom(image, MapPicture.detect(image));
        },
        redrawn -> {
          if (redrawing != project) {
            return;
          }
          if (redrawing.getFiles() != from) {
            tellMapChangedWhileRedrawing();
            return;
          }
          final Optional<RedrawChoices> choices =
              redrawn.added().isEmpty() && redrawn.merged().isEmpty()
                  ? Optional.of(RedrawChoices.none())
                  : RedrawDialog.ask(this, redrawing, from, redrawn);
          if (choices.isEmpty()) {
            return;
          }
          final List<CanalXml.Canal> canalsBefore = CanalXml.canals(redrawing.game());
          final Optional<List<String>> stillNamed =
              redrawing.takeRedrawn(from, redrawn, choices.get());
          if (stillNamed.isEmpty()) {
            tellMapChangedWhileRedrawing();
            return;
          }
          final List<String> territories = MapXml.territories(redrawing.game());
          // a removed territory takes the canals through it along
          final List<CanalXml.Canal> canalsAfter = CanalXml.canals(redrawing.game());
          final List<String> canalsGone =
              canalsBefore.stream()
                  .filter(c -> !canalsAfter.contains(c))
                  .map(CanalXml.Canal::name)
                  .toList();
          final List<String> changed =
              redrawn.changed().stream().filter(territories::contains).toList();
          final List<String> mergedInto = new ArrayList<>();
          redrawn
              .merged()
              .forEach(
                  (area, members) -> {
                    final String keeper =
                        choices.get().keepers().getOrDefault(area, members.getFirst());
                    members.stream()
                        .filter(m -> !m.equals(keeper) && !territories.contains(m))
                        .forEach(m -> mergedInto.add(m + " into " + keeper));
                  });
          final List<String> added =
              choices.get().added().values().stream().map(DetectedTerritory::name).toList();
          final List<String> merged =
              redrawn.merged().values().stream().flatMap(List::stream).toList();
          // a merged territory is told below: it was removed, or kept its border as still named
          final List<String> kept =
              redrawn.kept().stream().filter(t -> !merged.contains(t)).toList();
          Dialogs.warn(
              this,
              "Redraw borders",
              (changed.isEmpty()
                      ? "No border changed noticeably."
                      : "Borders changed: " + names(changed) + ".")
                  + (added.isEmpty() ? "" : "\n\nAdded: " + names(added) + ".")
                  + (mergedInto.isEmpty() ? "" : "\n\nMerged: " + names(mergedInto) + ".")
                  + (canalsGone.isEmpty()
                      ? ""
                      : "\n\nCanals removed with them: " + names(canalsGone) + ".")
                  + (stillNamed.get().isEmpty()
                      ? ""
                      : "\n\nKept their old border, still named: "
                          + names(stillNamed.get())
                          + ".")
                  + (kept.isEmpty()
                      ? ""
                      : "\n\nThese kept their border, as the picture has no area there of"
                          + " their own: "
                          + names(kept)
                          + ".")
                  + "\n\nLook at them on the map; Undo takes it all back.");
        });
  }

  private void tellMapChangedWhileRedrawing() {
    Dialogs.warn(
        this,
        "Redraw borders",
        "The map changed while the borders were redrawn, so they stay as they are."
            + "\nChoose Redraw borders again.");
  }

  /** The names for a message, at most a screen's worth. */
  private static String names(final List<String> names) {
    final int shown = Math.min(names.size(), 30);
    return String.join(", ", names.subList(0, shown))
        + (names.size() > shown ? " and " + (names.size() - shown) + " more" : "");
  }

  /** Runs the work with the wait cursor, then hands its result on or tells why it failed. */
  private <T> void inBackground(
      final String failure, final Callable<T> work, final Consumer<T> then) {
    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    new SwingWorker<T, Void>() {
      @Override
      protected T doInBackground() throws Exception {
        return work.call();
      }

      @Override
      protected void done() {
        setCursor(Cursor.getDefaultCursor());
        final T result;
        try {
          result = get();
        } catch (final Exception e) {
          final Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
          log.warn(failure, cause);
          Dialogs.warn(MapMakerWindow.this, "Picture", failure + "\n" + cause.getMessage());
          return;
        }
        then.accept(result);
      }
    }.execute();
  }

  /** What the picture gave, then Check, staying in the tool. */
  private void showFound(final Game game) {
    final long seas =
        MapXml.territories(game).stream().filter(t -> MapXml.isWater(game, t)).count();
    final long land = MapXml.territories(game).size() - seas;
    Dialogs.toast(this, "Found " + land + " territories and " + seas + " sea zones.");
    // the pill tells the result; a toast of its own would hide the one above
    check(problems -> {});
  }

  // ---- the editor layout

  void showProject(final MapProject opened) {
    mapShown = true;
    filesAhead = false;
    check = Check.NOT_YET;
    // typed text of the map left was dropped when the player agreed to leave it
    files.forgetText();
    project = opened;
    openedFromFile = opened.getXmlFile().isPresent();
    gameName = gameNameOf(opened);
    editors.clear();
    mapSlots.clear();
    workspace.removeAll();
    final MapView shared =
        new MapView(opened, this::choosePicture, this::findTerritories, this::redrawBorders);
    map = shared;
    final TerritoriesEditor territoriesEditor =
        new TerritoriesEditor(
            opened,
            shared,
            (title, item) ->
                Optional.ofNullable(editors.get(title)).ifPresent(e -> showEditor(e, item)),
            picture -> redrawFrom(() -> picture));
    territories = territoriesEditor;
    final HomeEditor homeEditor =
        new HomeEditor(
            opened,
            territory -> showEditor(territoriesEditor, territory),
            this::showTool,
            this::followStep,
            this::validateMap,
            this::save,
            this::playtest);
    home = homeEditor;
    shared.onShowProblems(this::showProblems);
    shared.onFollowStep(this::followStep);
    final List<Sidebar.Group> groups =
        List.of(
            new Sidebar.Group("Home", List.of(homeEditor)),
            new Sidebar.Group(
                "Map",
                List.of(
                    territoriesEditor,
                    new ConnectionsEditor(opened, shared),
                    new CanalsEditor(opened, shared),
                    new TerritoryEffectsEditor(
                        opened,
                        effect -> {
                          showEditor(territoriesEditor);
                          territoriesEditor.paintEffect(effect);
                        }),
                    new MapLookEditor(opened, () -> showTool("Territories")))),
            new Sidebar.Group(
                "Players",
                List.of(
                    new NationsEditor(opened, shared),
                    new PoliticsEditor(opened, this::showTool),
                    new TechnologyEditor(opened, this::showTool))),
            new Sidebar.Group(
                "Units & economy", List.of(new UnitsEditor(opened), new ProductionEditor(opened))),
            new Sidebar.Group(
                "Game flow",
                List.of(
                    new TurnOrderEditor(opened),
                    RulesEditors.newGameOptions(opened),
                    new ActionsEditor(opened),
                    new TriggersEditor(opened))),
            new Sidebar.Group(
                "Texts & sound",
                List.of(
                    new NotesEditor(opened),
                    new TextsEditor(opened, this::showTool),
                    new SoundsEditor(opened))));
    final Sidebar.Group advanced =
        new Sidebar.Group("Advanced", List.of(new AttachmentsEditor(opened), files));
    final List<Editor> inOrder = new ArrayList<>();
    groups.forEach(group -> inOrder.addAll(group.tools()));
    inOrder.addAll(advanced.tools());
    inOrder.forEach(this::addEditor);
    final Sidebar tools =
        new Sidebar(
            groups,
            advanced,
            this::showEditor,
            MakerResources.menu(
                this,
                () -> {
                  showFiles();
                  files.addFolder();
                }));
    tools.fitTo(getWidth());
    sidebar = tools;
    // Alt+1 Home, Alt+2 to 9 the tools after it in the sidebar's order
    for (int key = 1; key <= 9 && key <= inOrder.size(); key++) {
      final Editor tool = inOrder.get(key - 1);
      bind(
          "tool" + key,
          KeyStroke.getKeyStroke(KeyEvent.VK_0 + key, InputEvent.ALT_DOWN_MASK),
          () -> showEditor(tool));
    }

    final JPanel content = new JPanel(new BorderLayout());
    content.add(newHeader(), BorderLayout.NORTH);
    content.add(tools, BorderLayout.WEST);
    content.add(workspace, BorderLayout.CENTER);
    setContentPane(content);
    opened.onChange(
        () -> {
          // a Check was of the map before this change
          if (check == Check.DONE) {
            check = Check.NOT_YET;
          }
          updateState();
        });
    updateState();
    // first Home: what is done, what comes next, the problems
    showEditor(homeEditor);
    revalidate();
    repaint();
  }

  /** The open game's name, when the map has more than one game to tell apart. */
  private static Optional<String> gameNameOf(final MapProject opened) {
    final Optional<Path> xml = opened.getXmlFile().map(MapMakerWindow::normalized);
    final Map<String, Path> games =
        opened.getFolder().map(MapMakerWindow::gamesOf).orElse(Map.of());
    return games.size() < 2
        ? Optional.empty()
        : games.entrySet().stream()
            .filter(game -> xml.equals(Optional.of(normalized(game.getValue()))))
            .map(Map.Entry::getKey)
            .findFirst();
  }

  private void showTool(final String title) {
    Optional.ofNullable(editors.get(title)).ifPresent(this::showEditor);
  }

  /**
   * Shows the step's tool with the step as the map's goal; for land and capitals the matching Paint
   * brush, the land for the first nation without; Check runs.
   */
  void followStep(final MapGuide.Step.Id step) {
    if (step == MapGuide.Step.Id.CHECK) {
      validateMap();
      return;
    }
    final Game game = project.game();
    MapGuide.steps(project).stream()
        .filter(s -> s.id() == step)
        .findFirst()
        .ifPresent(s -> showTool(s.tool()));
    // Territories' empty state, a picture to load, says what to do
    if (step != MapGuide.Step.Id.TERRITORIES) {
      Objects.requireNonNull(map).showStep(step);
    }
    final TerritoriesEditor territoriesTool = Objects.requireNonNull(territories);
    if (step == MapGuide.Step.Id.LAND) {
      MapGuide.withoutLand(game).stream()
          .findFirst()
          .or(() -> MapXml.nations(game).stream().findFirst())
          .ifPresent(territoriesTool::paintOwner);
    } else if (step == MapGuide.Step.Id.CAPITALS) {
      territoriesTool.paintCapitals();
    }
  }

  /**
   * Shows the tool of this title, and the item in it if given, in the window the component is in,
   * eg from a link to where a setting is made; outside the editor's window nothing happens.
   */
  static void openTool(
      final Component inWindow, final String title, @Nullable final String item) {
    if (SwingUtilities.getWindowAncestor(inWindow) instanceof MapMakerWindow window) {
      Optional.ofNullable(window.editors.get(title)).ifPresent(e -> window.showEditor(e, item));
    }
  }

  /** Brings Files to the front, eg when a file is chosen in its tree. */
  private void showFiles() {
    if (!files.page().isShowing()) {
      showEditor(files);
    }
  }

  /**
   * The tool's screen, a card of the workspace: a tool on the map gets the map left of its panel;
   * the others take the whole width.
   */
  private void addEditor(final Editor editor) {
    editors.put(editor.title(), editor);
    final JComponent screen;
    if (editor.usesMap()) {
      final JPanel mapSlot = new JPanel(new BorderLayout());
      // a big map would ask for all the room: the panel keeps its width, the map takes the rest
      mapSlot.setPreferredSize(new Dimension(400, 0));
      mapSlot.setMinimumSize(new Dimension(300, 0));
      mapSlots.put(editor, mapSlot);
      final JComponent inspector = titled(editor, editor.page());
      inspector.setPreferredSize(new Dimension(INSPECTOR_WIDTH, 0));
      inspector.setMinimumSize(new Dimension(INSPECTOR_MIN_WIDTH, 0));
      final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mapSlot, inspector);
      split.setResizeWeight(1);
      split.setBorder(null);
      screen = split;
    } else {
      screen = titled(editor, editor.page());
    }
    workspace.add(screen, editor.title());
  }

  /** The tool's title with its one "?", its first-visit line, then the content. */
  private static JComponent titled(final Editor editor, final JComponent content) {
    final JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEADING, Theme.GAP_XS, 0));
    titleRow.add(Theme.newTitle(editor.title()));
    titleRow.add(Help.button(editor.title(), editor.hint()));
    final JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    for (final JComponent part : List.of(titleRow, Help.intro(editor.title(), editor.hint()))) {
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      top.add(part);
    }
    final JPanel screen = new JPanel(new BorderLayout(0, Theme.GAP_M));
    screen.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_L, Theme.GAP_L, Theme.GAP_L, Theme.GAP_L));
    screen.add(top, BorderLayout.NORTH);
    screen.add(content, BorderLayout.CENTER);
    return screen;
  }

  private void showEditor(final Editor editor) {
    showEditor(editor, null);
  }

  /** Home with all the map's problems, eg from the header's pill. */
  private void showProblems() {
    Optional.ofNullable(home)
        .ifPresent(
            shown -> {
              showEditor(shown);
              shown.showProblems();
            });
  }

  private void showEditor(final Editor editor, @Nullable final String territory) {
    // the map in the game view, a click there opening the territory; a tool may take it over
    Optional.ofNullable(map)
        .ifPresent(
            shared -> {
              shared.useFor(
                  MapMode.GAME,
                  new JPanel(),
                  clicked ->
                      Optional.ofNullable(territories).ifPresent(t -> showEditor(t, clicked)));
              // the one map moves into the tool that works on it
              Optional.ofNullable(mapSlots.get(editor))
                  .filter(slot -> shared.getParent() != slot)
                  .ifPresent(
                      slot -> {
                        slot.add(shared, BorderLayout.CENTER);
                        slot.revalidate();
                      });
            });
    editor.showing();
    ((CardLayout) workspace.getLayout()).show(workspace, editor.title());
    Optional.ofNullable(sidebar).ifPresent(s -> s.select(editor));
    if (territory != null) {
      editor.show(territory);
    }
  }

  // ---- the header

  private void newHeaderButtons() {
    Theme.setBorderlessButtonStyle(mapButton);
    mapButton.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3");
    mapButton.setToolTipText("The map's games, open, new, export, close");
    mapButton.setMinimumSize(new Dimension(NAME_MIN_WIDTH, 0));
    mapButton.addActionListener(e -> mapMenu().show(mapButton, 0, mapButton.getHeight()));
    pill.setToolTipText("Shows the problems in Home");
    pill.addActionListener(e -> showProblems());
    saveButton.setToolTipText("Writes the map's files (Ctrl+S)");
    saveButton.addActionListener(e -> save());
    playButton.setToolTipText("Saves and starts the game in a new TripleA window (F7)");
    playButton.addActionListener(e -> playtest());
  }

  /**
   * The bar for the whole map: its name (the Map menu) and whether all is saved on the left; its
   * problems, Undo, Redo, Check, Save and Play on the right.
   */
  private JComponent newHeader() {
    final JPanel header = new JPanel(new BorderLayout(Theme.GAP_L, 0));
    header.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.getSeparatorColor()),
            BorderFactory.createEmptyBorder(Theme.GAP_M, Theme.GAP_L, Theme.GAP_M, Theme.GAP_L)));
    // a long name gives way first, the save state stays
    final JPanel naming = new JPanel();
    naming.setLayout(new BoxLayout(naming, BoxLayout.X_AXIS));
    naming.add(mapButton);
    naming.add(Box.createHorizontalStrut(Theme.GAP_M));
    naming.add(saveState);
    naming.add(Box.createHorizontalGlue());
    header.add(naming, BorderLayout.CENTER);
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
    buttons.add(pill);
    buttons.add(Box.createHorizontalStrut(Theme.GAP_S));
    buttons.add(undoButton);
    buttons.add(redoButton);
    buttons.add(Box.createHorizontalStrut(Theme.GAP_S));
    final JButton checkButton = new JButton("Check", MakerIcon.small(Glyph.VALIDATE));
    checkButton.setToolTipText("Looks for problems, also by loading the game (F6)");
    Theme.setSecondaryButtonStyle(checkButton);
    checkButton.addActionListener(e -> validateMap());
    buttons.add(checkButton);
    buttons.add(saveButton);
    buttons.add(playButton);
    header.add(buttons, BorderLayout.EAST);
    return header;
  }

  /**
   * The map's games, this one chosen, and a new game from this one; open, new, recent maps; export,
   * the TripleA for Play, the folder; close.
   */
  private JPopupMenu mapMenu() {
    final JPopupMenu menu = new JPopupMenu();
    if (project.getXmlFile().isPresent()) {
      final JMenuItem gamesTitle = new JMenuItem("Games of this map");
      gamesTitle.setEnabled(false);
      menu.add(gamesTitle);
      final Optional<Path> open = project.getXmlFile().map(MapMakerWindow::normalized);
      final ButtonGroup games = new ButtonGroup();
      project
          .getFolder()
          .map(MapMakerWindow::gamesOf)
          .orElse(Map.of())
          .forEach(
              (name, file) -> {
                final boolean isOpen = open.equals(Optional.of(normalized(file)));
                final JRadioButtonMenuItem item = new JRadioButtonMenuItem(name, isOpen);
                games.add(item);
                item.addActionListener(
                    e -> {
                      if (!isOpen && mayLeaveProject()) {
                        openProject(file);
                      }
                    });
                menu.add(item);
              });
      menu.add("New game from this one…").addActionListener(e -> newGame());
      menu.addSeparator();
    }
    menu.add(menuItem("Open a map…", KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK))
        .addActionListener(e -> chooseAndOpen());
    menu.add(menuItem("New map…", KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK))
        .addActionListener(e -> createMap());
    menu.add(recentMaps());
    menu.addSeparator();
    menu.add(menuItem("Export as zip…", KeyEvent.VK_F8, 0)).addActionListener(e -> export());
    final String tripleA = PlayWith.describe();
    menu.add(tripleA.isEmpty() ? "Play with…" : "Play with: " + tripleA + "…")
        .addActionListener(e -> PlayWith.choose(this));
    final boolean mac = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("mac");
    final JMenuItem show = new JMenuItem(mac ? "Show in Finder" : "Show in Explorer");
    show.setEnabled(project.getFolder().isPresent() && Desktop.isDesktopSupported());
    show.addActionListener(e -> showFolder());
    menu.add(show);
    menu.addSeparator();
    menu.add(menuItem("Close map", KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK))
        .addActionListener(e -> closeMap());
    return menu;
  }

  // the key shows in the menu; the window's own bindings do the work
  private static JMenuItem menuItem(final String text, final int key, final int modifiers) {
    final JMenuItem item = new JMenuItem(text);
    item.setAccelerator(KeyStroke.getKeyStroke(key, modifiers));
    return item;
  }

  private JMenu recentMaps() {
    final JMenu recent = new JMenu("Recent maps");
    final Optional<Path> open = project.getXmlFile().map(MapMakerWindow::normalized);
    for (final Path xml : RecentMaps.list()) {
      if (open.equals(Optional.of(normalized(xml)))) {
        continue;
      }
      final Path folder = MapFolder.rootOf(xml.getParent());
      final String game = xml.getFileName().toString().replaceFirst("\\.xml$", "");
      final JMenuItem item = new JMenuItem(folder.getFileName() + " · " + game);
      item.setToolTipText(xml.toString());
      item.addActionListener(
          e -> {
            if (mayLeaveProject()) {
              openProject(xml);
            }
          });
      recent.add(item);
    }
    recent.setEnabled(recent.getItemCount() > 0);
    return recent;
  }

  private void showFolder() {
    final Path root = MapFolder.rootOf(project.getFolder().orElseThrow());
    try {
      Desktop.getDesktop().open(root.toFile());
    } catch (final IOException | UnsupportedOperationException e) {
      log.info("Could not show {}", root, e);
      Dialogs.warn(this, "Show the map's folder", "Could not open " + root + "\n" + e.getMessage());
    }
  }

  /** The map's name, with the game's when the map has another game and it is named apart. */
  static String headerTitle(final String mapName, final Optional<String> gameName) {
    return mapName + gameName.filter(game -> !game.equals(mapName)).map(g -> " · " + g).orElse("");
  }

  private static Path normalized(final Path file) {
    return file.toAbsolutePath().normalize();
  }

  private void updateState() {
    final boolean unsaved = project.hasUnsavedChanges();
    saveState.setText(unsaved ? "• Not saved" : "All saved");
    saveState.setForeground(unsaved ? Theme.getWarningColor() : Theme.getSecondaryTextColor());
    mapButton.setText(headerTitle(project.name(), gameName) + " ▾");
    mapButton.getAccessibleContext().setAccessibleName("Map: " + project.name());
    showSaveAndPlay(project, saveButton, playButton);
    undoButton.setEnabled(project.canUndo());
    redoButton.setEnabled(project.canRedo());
    final Pill shown =
        pill(Optional.ofNullable(home).map(h -> h.problems().size()).orElse(0), check);
    pill.setText(shown.text());
    Theme.setPillStyle(pill, shown.status());
    setTitle((unsaved ? "● " : "") + project.name() + " — Eddie's Map Editor");
  }

  /** One primary in the header: Save while there are changes, else Play. */
  static void showSaveAndPlay(final MapProject project, final JButton save, final JButton play) {
    final boolean unsaved = project.hasUnsavedChanges();
    save.setVisible(unsaved);
    Theme.setPrimaryButtonStyle(save);
    if (unsaved) {
      Theme.setSecondaryButtonStyle(play);
    } else {
      Theme.setPrimaryButtonStyle(play);
    }
  }

  /** The pill for the problems known and how far Check is. */
  static Pill pill(final int problems, final Check check) {
    if (check == Check.RUNNING) {
      return new Pill("Checking…", Status.TODO);
    }
    if (problems > 0) {
      return new Pill("! " + problems + (problems == 1 ? " problem" : " problems"), Status.PROBLEM);
    }
    return check == Check.DONE
        ? new Pill("✓ No problems", Status.DONE)
        : new Pill("Not checked yet", Status.TODO);
  }

  // ---- actions on the whole map

  /** Takes the last change back; typing not yet a step becomes one first, so that is undone. */
  private void undo() {
    editors.values().forEach(Editor::commit);
    project.undo();
  }

  private void redo() {
    editors.values().forEach(Editor::commit);
    project.redo();
  }

  /** Saves; false, with a message, if it failed. */
  private boolean save() {
    if (filesAhead) {
      Dialogs.warn(
          this,
          SAVE,
          "The map's files were changed in Files and do not read yet\nFix them there first:"
              + " saving here would write the map as it was over them.");
      return false;
    }
    editors.values().forEach(Editor::commit);
    // every map shown has its game file: opened from one, or made and saved by the wizard
    final Path xml = project.getXmlFile().orElseThrow();
    final Path backup = xml.resolveSibling(xml.getFileName() + ".bak");
    final boolean firstBackup = !Files.exists(backup);
    try {
      project.save();
      if (openedFromFile && firstBackup && Files.exists(backup)) {
        Dialogs.toast(
            this,
            "✓ Saved. The old file stays as " + backup.getFileName(),
            "Show",
            () -> {
              showFiles();
              files.reveal(backup);
            });
      }
      return true;
    } catch (final Exception e) {
      log.warn("Could not save {}", xml, e);
      Dialogs.warn(this, SAVE, "Could not save the map\n" + e.getMessage());
      return false;
    }
  }

  /** Check, then a toast with what it found; the tool stays. */
  private void validateMap() {
    check(
        problems -> {
          if (problems.isEmpty()) {
            Dialogs.toast(this, "✓ Check found no problems");
          } else {
            Dialogs.toast(
                this,
                "! Check found "
                    + problems.size()
                    + (problems.size() == 1 ? " problem" : " problems"),
                "Show",
                this::showProblems);
          }
        });
  }

  /** Checks the map in the background; the result counts only for the map as it still is. */
  private void check(final Consumer<List<Problem>> then) {
    if (home == null) {
      return;
    }
    final MapProject checked = project;
    final Game checkedGame = checked.game();
    final HomeEditor shownIn = home;
    check = Check.RUNNING;
    updateState();
    new SwingWorker<List<Problem>, Void>() {
      @Override
      protected List<Problem> doInBackground() {
        return MapChecks.all(checkedGame, checked.getFiles());
      }

      @Override
      protected void done() {
        // another map was opened or this one changed meanwhile: the result is of an older one
        if (shownIn != home || checked.game() != checkedGame) {
          if (shownIn == home) {
            check = Check.NOT_YET;
            updateState();
          }
          return;
        }
        final List<Problem> problems;
        try {
          problems = get();
        } catch (final Exception e) {
          log.warn("Validating the map failed", e);
          check = Check.NOT_YET;
          updateState();
          return;
        }
        shownIn.setValidated(problems);
        check = Check.DONE;
        updateState();
        then.accept(problems);
      }
    }.execute();
  }

  /** Saves the changes for an action that needs the map's files; false if it is not saved. */
  private boolean savedFor() {
    // notes still being typed count as a change to save; with the files ahead save() says why not
    editors.values().forEach(Editor::commit);
    return (!filesAhead && !project.hasUnsavedChanges()) || save();
  }

  private void playtest() {
    if (!savedFor()) {
      return;
    }
    try {
      final String tripleA = PlayWith.play(project.getXmlFile().orElseThrow());
      Dialogs.toast(this, "Starting " + tripleA + ": choose who plays and click Start Game.");
    } catch (final Exception e) {
      log.warn("Could not start the playtest", e);
      Dialogs.warn(this, "Play", "Could not start TripleA\n" + e.getMessage());
    }
  }

  private void export() {
    if (!savedFor()) {
      return;
    }
    // with its map.yml, also when the map keeps its files in a folder below it
    final Path folder = MapFolder.rootOf(project.getFolder().orElseThrow());
    // the system's own save dialog, which also asks before replacing a file
    final Optional<Path> chosenZip =
        FileChooser.builder()
            .parent(this)
            .title("Export the map as a zip")
            .mode(FileDialog.SAVE)
            .directory(ClientFileSystemHelper.getUserRootFolder())
            .fileName(folder.getFileName() + ".zip")
            .fileExtension(".zip")
            .build()
            .chooseFile();
    if (chosenZip.isEmpty()) {
      return;
    }
    final Path zip = chosenZip.get();
    new SwingWorker<Integer, Void>() {
      @Override
      protected Integer doInBackground() throws Exception {
        return MapFolder.zip(folder, zip);
      }

      @Override
      protected void done() {
        try {
          Dialogs.toast(MapMakerWindow.this, "✓ Exported " + get() + " files to " + zip);
        } catch (final Exception e) {
          log.warn("Could not export {}", zip, e);
          Dialogs.warn(
              MapMakerWindow.this, "Export", "Could not export the map\n" + e.getMessage());
        }
      }
    }.execute();
  }

  private void bindKeys() {
    final int ctrl = InputEvent.CTRL_DOWN_MASK;
    bind("save", KeyStroke.getKeyStroke(KeyEvent.VK_S, ctrl), this::save);
    bind("undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl), this::undo);
    bind("redo", KeyStroke.getKeyStroke(KeyEvent.VK_Y, ctrl), this::redo);
    bind("validate", KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), this::validateMap);
    bind("playtest", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), this::playtest);
    bind("export", KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), this::export);
    bind("close", KeyStroke.getKeyStroke(KeyEvent.VK_W, ctrl), this::closeMap);
    bindAlways("open", KeyStroke.getKeyStroke(KeyEvent.VK_O, ctrl), this::chooseAndOpen);
    bindAlways("new", KeyStroke.getKeyStroke(KeyEvent.VK_N, ctrl), this::createMap);
  }

  /** A key for an open map; the start page has no map to save, undo or test. */
  private void bind(final String name, final KeyStroke key, final Runnable action) {
    bindAlways(
        name,
        key,
        () -> {
          if (mapShown) {
            action.run();
          }
        });
  }

  private void bindAlways(final String name, final KeyStroke key, final Runnable action) {
    getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
    getRootPane()
        .getActionMap()
        .put(
            name,
            new AbstractAction() {
              @Serial private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final ActionEvent e) {
                action.run();
              }
            });
  }
}
