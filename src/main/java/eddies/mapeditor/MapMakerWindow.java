package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.SystemFileChooser;
import games.strategy.engine.ClientFileSystemHelper;
import games.strategy.triplea.EngineImageLoader;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import lombok.extern.slf4j.Slf4j;
import org.triplea.game.client.HeadedGameRunner;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.FileChooser;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapProject.NewMap;

/**
 * The map maker: one window for all changes to a map. It opens on a {@link StartPage} to open a
 * map's folder, create an empty map in a folder of the player's choice, or create one from a
 * picture with {@link NewMapWizard} (name, picture with its territories, nations); a new map is
 * saved as a map folder at once. As in IntelliJ: on the far left a strip of icons, the file browser
 * ({@link FilesEditor}, open beside it at first) and the tools (Overview, Game notes, Territories,
 * Connections, Nations, Game options, Turn order, Units), each telling its name and use when the
 * mouse is over it; in the middle the map, a tool's own preview or the file chosen in the browser;
 * on the right the tool's panel under its title and "?"; at the top the map's name, whether all is
 * saved and the buttons for the whole map; at the bottom how many problems are left.
 */
@Slf4j
public final class MapMakerWindow extends JFrame {
  @Serial private static final long serialVersionUID = 1L;

  private static final String MAP_PREVIEW = "map";
  private static final String FILE_PREVIEW = "file";
  private static final int RIGHT_WIDTH = 380;
  private static final int BROWSER_WIDTH = 260;
  private static final int STRIP_ICON = 20;

  // nothing to save or leave until a map is opened or created; set here, before the Files view
  // below asks for its folder
  private transient MapProject project = MapProject.newEmpty();
  // a map's own game file, kept as .bak on the first save; a map made here has no older version
  private boolean openedFromFile;
  private transient @Nullable OverviewEditor overview;
  private final transient Map<String, Editor> editors = new LinkedHashMap<>();
  private final JLabel mapName = new JLabel();
  private final JLabel saveState = new JLabel();
  private final JButton problemsLabel = new JButton();
  private final JPanel editorCards = new JPanel(new CardLayout());
  private final JButton saveButton = new JButton("Save");
  private final JButton undoButton = new JButton(MakerIcon.small(Glyph.UNDO));
  private final JButton redoButton = new JButton(MakerIcon.small(Glyph.REDO));
  // the middle: the shared map, or a tool's own preview
  private final JPanel previewCards = new JPanel(new CardLayout());
  private transient @Nullable MapView map;
  private transient @Nullable TerritoriesEditor territories;
  // the tools in the strip on the left, a line between the groups
  private transient List<List<Editor>> toolGroups = List.of();
  private final Map<String, JToggleButton> navButtons = new LinkedHashMap<>();
  // one for the window, so the maps added to copy from and the open file stay across reloads
  private final transient FilesEditor files =
      new FilesEditor(
          () -> project.getFolder().map(MapFolder::rootOf),
          this::mayWriteMapFile,
          this::reloadFromFiles,
          this::showFileInMiddle);
  // the file browser beside the tools, open as in IntelliJ until the player closes it
  private boolean browserShown = true;
  private transient @Nullable JSplitPane browserSplit;
  // the middle and the tool's panel; a tool without map or preview takes the middle's room too
  private transient @Nullable JSplitPane columnsSplit;
  private int middleWidth;
  private @Nullable JToggleButton browserButton;
  private boolean mapShown;
  // a file saved in Files does not read: the map in memory is older than its files
  private boolean filesAhead;

  private MapMakerWindow() {
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
    setMinimumSize(new Dimension(1000, 640));
    bindKeys();
    showStart();
  }

  /** Starts the editor on its own, with TripleA's settings and look. */
  static void start() {
    // TripleA's settings hold the maps folder; its look gives the editor its colors and styles
    HeadedGameRunner.initializeClientSettingAndLogging();
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

  /** The page the map maker opens with: open a map, create an empty one, or one from a picture. */
  private void showStart() {
    mapShown = false;
    setTitle("Eddie's Map Editor");
    setContentPane(new StartPage(this::chooseAndOpen, this::createEmptyMap, this::createMap));
    revalidate();
    repaint();
  }

  /**
   * An empty map where the player wants it: the system's folder dialog for the place, then its
   * name; the map's folder with all its files is made there and opened.
   */
  private void createEmptyMap() {
    if (!mayLeaveProject()) {
      return;
    }
    final Optional<Path> location =
        chooseFolder(
            this, "Where should the new map go?", ClientFileSystemHelper.getUserMapsFolder());
    if (location.isEmpty()) {
      return;
    }
    String suggestion = "My map";
    while (true) {
      final String name =
          (String)
              JOptionPane.showInputDialog(
                  this,
                  "Name of the new map; its folder is made in\n" + location.get(),
                  "Create an empty map",
                  JOptionPane.PLAIN_MESSAGE,
                  null,
                  null,
                  suggestion);
      if (name == null) {
        return;
      }
      suggestion = name;
      final Optional<String> refused = MapFolder.whyNoNewMap(location.get(), name);
      if (refused.isPresent()) {
        JOptionPane.showMessageDialog(
            this, refused.get(), "Create an empty map", JOptionPane.WARNING_MESSAGE);
        continue;
      }
      try {
        opened(MapProject.newMap(name, null, List.of(), location.get()));
      } catch (final Exception e) {
        log.warn("Could not create the map {} in {}", name, location.get(), e);
        JOptionPane.showMessageDialog(
            this,
            "Could not create the map:\n" + e.getMessage(),
            "Create an empty map",
            JOptionPane.WARNING_MESSAGE);
        continue;
      }
      JOptionPane.showMessageDialog(
          this,
          "Created "
              + project.getFolder().orElseThrow()
              +
"""

with map.yml, the game file, its notes and map.properties.

"""
              + "Next: load a picture of the map in Territories and press Find territories.\n"
              + "TripleA lists the maps in its maps folder: move it there to play it.",
          "Create an empty map",
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }
  }

  /** The new-map wizard; the map it makes opens, with what was found in its picture. */
  private void createMap() {
    if (mayLeaveProject()) {
      NewMapWizard.show(
          this,
          made -> {
            opened(made);
            if (!made.refusedNations().isEmpty()) {
              JOptionPane.showMessageDialog(
                  this,
                  "These nations were left out: "
                      + String.join(", ", made.refusedNations())
                      + "\nA nation needs a name of its own, not a territory's or a unit's, without"
                      + " : < or (. Add them in Nations.",
                  "New map",
                  JOptionPane.WARNING_MESSAGE);
            }
            if (made.project().getFiles().hasOutlines()) {
              showFound(made.project().game());
            }
          });
    }
  }

  /** Shows a map just made and saved; it has no older version of its game file to keep. */
  private void opened(final NewMap made) {
    showProject(made.project());
    openedFromFile = false;
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
    final int answer =
        JOptionPane.showConfirmDialog(
            this,
            "The map has changes made here that are not saved. Saving this file reads the map"
                + " again from its files.\nSave the map's changes first? No drops them.",
            "Save file",
            JOptionPane.YES_NO_CANCEL_OPTION);
    return answer == JOptionPane.NO_OPTION || (answer == JOptionPane.YES_OPTION && save());
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
      showFileInMiddle();
    } catch (final Exception e) {
      log.info("Could not read the map again from {}", xml.get(), e);
      // the map as it was must not be written over the files the player changed
      filesAhead = true;
      JOptionPane.showMessageDialog(
          this,
          "The file is saved, but the map could not be read from its files:\n"
              + e.getMessage()
              + "\nFix it in Files. Until the map reads again, the other editors show it as it"
              + " was and nothing is saved from them.",
          "Files",
          JOptionPane.WARNING_MESSAGE);
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
      JOptionPane.showMessageDialog(
          this,
          "No game found in "
              + folder
              + ".\nA map folder has its map.yml and its games in a games folder.",
          "Open",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    final String[] names = games.keySet().toArray(String[]::new);
    final Object game =
        names.length == 1
            ? names[0]
            : JOptionPane.showInputDialog(
                this,
                "Which game of the map?",
                "Open",
                JOptionPane.QUESTION_MESSAGE,
                null,
                names,
                names[0]);
    Optional.ofNullable(game).map(games::get).ifPresent(this::openProject);
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
        .parent(JOptionPane.getFrameForComponent(parent))
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
    } catch (final Exception e) {
      log.warn("Could not open {} in the map maker", xml, e);
      JOptionPane.showMessageDialog(
          this,
          "Could not read " + xml.getFileName() + " as a game:\n" + e.getMessage(),
          "Map Maker",
          JOptionPane.WARNING_MESSAGE);
    }
  }

  /** A copy of the saved game under a new name beside it, opened. */
  private void newGame() {
    final Optional<Path> template = project.getXmlFile();
    if (template.isEmpty() || !mayLeaveProject()) {
      return;
    }
    final String name = JOptionPane.showInputDialog(this, "Name of the new game:", "My game");
    if (name != null && !name.isBlank()) {
      try {
        openProject(MapFolder.newGame(template.get(), name));
      } catch (final Exception e) {
        JOptionPane.showMessageDialog(
            this, e.getMessage(), "New game", JOptionPane.WARNING_MESSAGE);
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
    final int answer =
        JOptionPane.showConfirmDialog(
            this,
            "Save the changes to " + project.name() + " first?",
            "Unsaved changes",
            JOptionPane.YES_NO_CANCEL_OPTION);
    return answer == JOptionPane.NO_OPTION || (answer == JOptionPane.YES_OPTION && save());
  }

  // ---- the picture

  /** Asks for a picture of the map; the territories stay until Find territories. */
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
   * Finds the territories in the map's picture in the background, first asking whether to replace
   * the ones there are, then takes them as one step.
   */
  private void findTerritories() {
    final Optional<BufferedImage> picture = project.getFiles().picture();
    if (picture.isEmpty()
        || (project.getFiles().hasOutlines()
            && JOptionPane.showConfirmDialog(
                    this,
                    "Find the territories again? The territories and connections found replace the"
                        + " ones the map has now,\nwith their owners and units. Undo takes it"
                        + " back.",
                    "Find territories",
                    JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION)) {
      return;
    }
    final MapProject findingIn = project;
    inBackground(
        "Could not find the territories",
        () -> MapPicture.detect(picture.get()),
        detected -> {
          // a picture loaded meanwhile keeps its territories until Find territories is pressed
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
    final MapProject redrawing = project;
    final MapFiles from = redrawing.getFiles();
    inBackground(
        "Could not redraw the borders",
        () -> {
          final BufferedImage image = MapPicture.read(file.get());
          return from.withBordersFrom(image, MapPicture.detect(image));
        },
        redrawn -> {
          if (redrawing != project) {
            return;
          }
          if (!redrawing.takeRedrawn(from, redrawn)) {
            JOptionPane.showMessageDialog(
                this,
                "The map changed while the borders were redrawn, so they stay as they are."
                    + "\nChoose Redraw borders again.",
                "Redraw borders",
                JOptionPane.INFORMATION_MESSAGE);
            return;
          }
          final List<String> kept = redrawn.kept();
          JOptionPane.showMessageDialog(
              this,
              "<html><div style='width:420px'>"
                  + (redrawn.changed().isEmpty()
                      ? "No border changed noticeably."
                      : "Borders changed: " + names(redrawn.changed()) + ".")
                  + (kept.isEmpty()
                      ? ""
                      : "<br><br>These kept their border, as the picture has no area there of"
                          + " their own: "
                          + names(kept)
                          + ".")
                  + "<br><br>Look at them on the map; Undo takes it all back.</div></html>",
              "Redraw borders",
              JOptionPane.INFORMATION_MESSAGE);
        });
  }

  /** The names for a message, at most a screen's worth. */
  private static String names(final List<String> names) {
    final int shown = Math.min(names.size(), 30);
    return SearchList.escape(String.join(", ", names.subList(0, shown)))
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
          JOptionPane.showMessageDialog(
              MapMakerWindow.this,
              failure + ":\n" + cause.getMessage(),
              "Picture",
              JOptionPane.WARNING_MESSAGE);
          return;
        }
        then.accept(result);
      }
    }.execute();
  }

  /** What the picture gave and what to do next; then Validate shows what is left to fix. */
  private void showFound(final Game game) {
    final long seas =
        MapXml.territories(game).stream().filter(t -> MapXml.isWater(game, t)).count();
    final long land = MapXml.territories(game).size() - seas;
    JOptionPane.showMessageDialog(
        this,
        "Found "
            + land
            + " territories and "
            + seas
            + " sea zones.\n\n"
            + "Next: add the nations in Nations, name the territories and set their owners in"
            + " Territories,\ncheck the Connections, then Save and Check.",
        "Picture",
        JOptionPane.INFORMATION_MESSAGE);
    validateMap();
  }

  // ---- the editor layout

  private void showProject(final MapProject opened) {
    mapShown = true;
    filesAhead = false;
    // typed text of the map left was dropped when the player agreed to leave it
    files.forgetText();
    project = opened;
    openedFromFile = opened.getXmlFile().isPresent();
    editors.clear();
    navButtons.clear();
    editorCards.removeAll();
    previewCards.removeAll();
    previewCards.setVisible(true);
    map = new MapView(opened, this::choosePicture, this::findTerritories, this::redrawBorders);
    previewCards.add(map, MAP_PREVIEW);
    final TerritoriesEditor territories = new TerritoriesEditor(opened, map);
    this.territories = territories;
    final OverviewEditor overviewEditor =
        new OverviewEditor(
            opened,
            territory -> showEditor(territories, territory),
            title -> Optional.ofNullable(editors.get(title)).ifPresent(this::showEditor),
            file -> {
              showBrowser(true);
              files.reveal(file);
            },
            this::validateMap);
    overview = overviewEditor;
    toolGroups =
        List.of(
            List.of(overviewEditor, new NotesEditor(opened), new TextsEditor(opened)),
            List.of(
                territories,
                new ConnectionsEditor(opened, map),
                new NationsEditor(opened),
                new MapLookEditor(opened)),
            List.of(
                RulesEditors.newGameOptions(opened),
                new TurnOrderEditor(opened),
                new PoliticsEditor(opened),
                new TechnologyEditor(opened)),
            List.of(
                new UnitsEditor(opened),
                new ProductionEditor(opened),
                new AttachmentsEditor(opened)));
    toolGroups.stream().flatMap(List::stream).forEach(this::addEditor);

    files.preview().ifPresent(file -> previewCards.add(file, FILE_PREVIEW));

    final JPanel content = new JPanel(new BorderLayout());
    content.add(newToolStrip(), BorderLayout.WEST);
    final JPanel main = new JPanel(new BorderLayout(0, 12));
    main.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
    main.add(newHeader(), BorderLayout.NORTH);
    // the middle shows what is made, the right the tool's settings; the line between them moves
    editorCards.setPreferredSize(new Dimension(RIGHT_WIDTH, 0));
    editorCards.setMinimumSize(new Dimension(260, 0));
    // a big map would ask for all the room: the right keeps its width, the middle takes the rest
    previewCards.setPreferredSize(new Dimension(400, 0));
    previewCards.setMinimumSize(new Dimension(300, 0));
    final JSplitPane columns =
        new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, previewCards, editorCards);
    columns.setResizeWeight(1);
    columns.setBorder(null);
    columnsSplit = columns;
    final JSplitPane browser =
        new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, newFileBrowser(), columns);
    browser.setDividerLocation(BROWSER_WIDTH);
    browser.setBorder(null);
    browserSplit = browser;
    showBrowser(browserShown);
    main.add(browser, BorderLayout.CENTER);
    content.add(main, BorderLayout.CENTER);
    content.add(newStatusBar(), BorderLayout.SOUTH);
    setContentPane(content);
    opened.onChange(this::updateState);
    updateState();
    files.showing();
    // first the overview: what is done, what comes next, and what the map's folder holds
    showEditor(overviewEditor);
    revalidate();
    repaint();
  }

  /** The file browser beside the tools: the map's folder under its title and "?". */
  private JComponent newFileBrowser() {
    final JLabel heading =
        new JLabel(files.title(), MakerIcon.small(files.glyph()), SwingConstants.LEFT);
    heading.setFont(heading.getFont().deriveFont(Font.BOLD));
    heading.setIconTextGap(8);
    final JPanel browser = new JPanel(new BorderLayout(0, 8));
    browser.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
    browser.setMinimumSize(new Dimension(180, 0));
    browser.add(Help.with(heading, files.hint()), BorderLayout.NORTH);
    browser.add(files.page(), BorderLayout.CENTER);
    return browser;
  }

  /** Opens or closes the file browser, as its icon in the strip does. */
  private void showBrowser(final boolean shown) {
    browserShown = shown;
    Optional.ofNullable(browserButton).ifPresent(button -> button.setSelected(shown));
    Optional.ofNullable(browserSplit)
        .ifPresent(
            split -> {
              split.getLeftComponent().setVisible(shown);
              split.setDividerSize(shown ? UIManager.getInt("SplitPane.dividerSize") : 0);
              if (shown) {
                split.setDividerLocation(BROWSER_WIDTH);
              }
              split.revalidate();
            });
  }

  /** The file chosen in the browser, in the middle; the tools' panel stays on the right. */
  private void showFileInMiddle() {
    showInMiddle(FILE_PREVIEW);
  }

  private void showInMiddle(final String card) {
    ((CardLayout) previewCards.getLayout()).show(previewCards, card);
    Optional.ofNullable(columnsSplit)
        .filter(split -> !previewCards.isVisible())
        .ifPresent(
            split -> {
              previewCards.setVisible(true);
              split.setDividerSize(UIManager.getInt("SplitPane.dividerSize"));
              split.setDividerLocation(middleWidth);
            });
  }

  /** No middle: the tool's settings take the whole room, for a better overview. */
  private void hideMiddle() {
    Optional.ofNullable(columnsSplit)
        .filter(split -> previewCards.isVisible())
        .ifPresent(
            split -> {
              middleWidth = split.getDividerLocation();
              previewCards.setVisible(false);
              split.setDividerSize(0);
              split.revalidate();
            });
  }

  /** The tool's panel on the right under its icon, title and "?"; its preview in the middle. */
  private void addEditor(final Editor editor) {
    editors.put(editor.title(), editor);
    final JLabel heading =
        new JLabel(editor.title(), MakerIcon.small(editor.glyph()), SwingConstants.LEFT);
    heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() * 1.3f));
    heading.setIconTextGap(8);
    final JPanel panel = new JPanel(new BorderLayout(0, 8));
    panel.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
    panel.add(Help.with(heading, editor.hint()), BorderLayout.NORTH);
    panel.add(editor.page(), BorderLayout.CENTER);
    editorCards.add(panel, editor.title());
    editor.preview().ifPresent(preview -> previewCards.add(preview, editor.title()));
  }

  private void showEditor(final Editor editor) {
    showEditor(editor, null);
  }

  private void showEditor(final Editor editor, @Nullable final String territory) {
    // the map in the game view, a click there opening the territory; a tool may take it over
    Optional.ofNullable(map)
        .ifPresent(
            shared ->
                shared.useFor(
                    MapMode.GAME,
                    new JPanel(),
                    clicked ->
                        Optional.ofNullable(territories).ifPresent(t -> showEditor(t, clicked))));
    editor.showing();
    if (editor.preview().isPresent()) {
      showInMiddle(editor.title());
    } else if (editor.usesMap()) {
      showInMiddle(MAP_PREVIEW);
    } else {
      hideMiddle();
    }
    ((CardLayout) editorCards.getLayout()).show(editorCards, editor.title());
    Optional.ofNullable(navButtons.get(editor.title())).ifPresent(b -> b.setSelected(true));
    if (territory != null) {
      editor.show(territory);
    }
  }

  /**
   * The strip on the far left, icons only as in IntelliJ, each telling its name and use when the
   * mouse is over it: the file browser on top, then the tools by group.
   */
  private JComponent newToolStrip() {
    final JPanel strip = new JPanel();
    strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));
    strip.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.getSeparatorColor()),
            BorderFactory.createEmptyBorder(10, 6, 10, 6)));
    // Alt+1 for the file browser, Alt+2 and on for the tools in their order, as in IntelliJ
    final JToggleButton browser =
        newStripButton(
            files.glyph(), files.title() + ": show or hide the file browser (Alt+1)", files.hint());
    browser.setSelected(browserShown);
    browserButton = browser;
    browser.addActionListener(e -> showBrowser(browser.isSelected()));
    bind(
        "browser", KeyStroke.getKeyStroke(KeyEvent.VK_1, KeyEvent.ALT_DOWN_MASK), browser::doClick);
    strip.add(browser);
    final ButtonGroup group = new ButtonGroup();
    int key = 2;
    for (final List<Editor> tools : toolGroups) {
      final JSeparator line = new JSeparator();
      line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
      strip.add(Box.createVerticalStrut(8));
      strip.add(line);
      strip.add(Box.createVerticalStrut(8));
      for (final Editor editor : tools) {
        final String shortcut = key <= 9 ? " (Alt+" + key + ")" : "";
        final JToggleButton button =
            newStripButton(editor.glyph(), editor.title() + shortcut, editor.hint());
        button.addActionListener(e -> showEditor(editor));
        if (key <= 9) {
          bind(
              "tool" + key,
              KeyStroke.getKeyStroke(KeyEvent.VK_0 + key, KeyEvent.ALT_DOWN_MASK),
              button::doClick);
        }
        key++;
        group.add(button);
        navButtons.put(editor.title(), button);
        strip.add(button);
        strip.add(Box.createVerticalStrut(4));
      }
    }
    strip.add(Box.createVerticalGlue());
    final JButton resources = new JButton(new MakerIcon(Glyph.RESOURCES, STRIP_ICON));
    styleStripButton(
        resources, "Resources", "Guides and links for map makers, and maps to copy from");
    final JPopupMenu links =
        MakerResources.menu(
            this,
            () -> {
              showBrowser(true);
              files.addFolder();
            });
    resources.addActionListener(e -> links.show(resources, resources.getWidth(), 0));
    strip.add(resources);
    return strip;
  }

  /** A square icon button of the strip; its name for screen readers, name and use as tool tip. */
  private static JToggleButton newStripButton(
      final Glyph glyph, final String title, final String hint) {
    final JToggleButton button = new JToggleButton(new MakerIcon(glyph, STRIP_ICON));
    styleStripButton(button, title, hint);
    return button;
  }

  private static void styleStripButton(
      final AbstractButton button, final String title, final String hint) {
    button.setToolTipText(
        "<html><div style='width:260px'><b>"
            + SearchList.escape(title)
            + "</b><br>"
            + SearchList.escape(hint)
            + "</div></html>");
    button.getAccessibleContext().setAccessibleName(title);
    button.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    button.setAlignmentX(Component.CENTER_ALIGNMENT);
    final Dimension size = new Dimension(36, 36);
    button.setPreferredSize(size);
    button.setMinimumSize(size);
    button.setMaximumSize(size);
  }

  /**
   * The bar for the whole map: its name and whether all is saved on the left; the Map menu, Undo,
   * Redo, Validate, Playtest and Save on the right.
   */
  private JComponent newHeader() {
    final JPanel header = new JPanel(new BorderLayout(16, 0));
    final JPanel naming = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
    mapName.setFont(mapName.getFont().deriveFont(Font.BOLD, mapName.getFont().getSize2D() * 1.4f));
    saveState.setForeground(Theme.getSecondaryTextColor());
    naming.add(mapName);
    naming.add(saveState);
    header.add(naming, BorderLayout.CENTER);
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    final JButton mapButton = new JButton("Map ▾", MakerIcon.small(Glyph.MENU));
    mapButton.setToolTipText("New, open, the map's games, export");
    mapButton.addActionListener(e -> mapMenu().show(mapButton, 0, mapButton.getHeight()));
    buttons.add(mapButton);
    undoButton.getAccessibleContext().setAccessibleName("Undo");
    redoButton.getAccessibleContext().setAccessibleName("Redo");
    undoButton.setToolTipText("Undo: takes the last change back (Ctrl+Z)");
    undoButton.addActionListener(e -> undo());
    buttons.add(undoButton);
    redoButton.setToolTipText("Redo: makes the change again (Ctrl+Y)");
    redoButton.addActionListener(e -> redo());
    buttons.add(redoButton);
    final JButton validate =
        newButton("Check", "Looks for problems, also by loading the game (F6)", this::validateMap);
    validate.setIcon(MakerIcon.small(Glyph.VALIDATE));
    buttons.add(validate);
    final JButton play =
        newButton("Play", "Saves and starts the game in a new TripleA window (F7)", this::playtest);
    play.setIcon(MakerIcon.small(Glyph.PLAYTEST));
    buttons.add(play);
    saveButton.setIcon(MakerIcon.small(Glyph.SAVE));
    saveButton.setToolTipText("Writes the map's files (Ctrl+S)");
    saveButton.addActionListener(e -> save());
    Theme.setPrimaryButtonStyle(saveButton);
    buttons.add(saveButton);
    header.add(buttons, BorderLayout.EAST);
    return header;
  }

  /**
   * New and open; for a saved map its games, this one ticked, and a new game from this one; export.
   */
  private JPopupMenu mapMenu() {
    final JPopupMenu menu = new JPopupMenu();
    menu.add("Open map folder...").addActionListener(e -> chooseAndOpen());
    menu.add("Create an empty map...").addActionListener(e -> createEmptyMap());
    menu.add("New map from a picture...").addActionListener(e -> createMap());
    if (project.getXmlFile().isPresent()) {
      menu.addSeparator();
      final Optional<Path> open = project.getXmlFile().map(MapMakerWindow::normalized);
      project
          .getFolder()
          .map(MapMakerWindow::gamesOf)
          .orElse(Map.of())
          .forEach(
              (name, file) -> {
                final boolean isOpen = open.equals(Optional.of(normalized(file)));
                final JCheckBoxMenuItem item = new JCheckBoxMenuItem(name, isOpen);
                item.addActionListener(
                    e -> {
                      if (!isOpen && mayLeaveProject()) {
                        openProject(file);
                      }
                    });
                menu.add(item);
              });
      menu.add("New game from this one...").addActionListener(e -> newGame());
    }
    menu.addSeparator();
    menu.add("Export map as zip... (F8)").addActionListener(e -> export());
    menu.add("TripleA for Play...").addActionListener(e -> PlayWith.choose(this));
    return menu;
  }

  private static Path normalized(final Path file) {
    return file.toAbsolutePath().normalize();
  }

  private static JButton newButton(final String text, final String toolTip, final Runnable action) {
    final JButton button = new JButton(text);
    button.setToolTipText(toolTip);
    button.addActionListener(e -> action.run());
    return button;
  }

  private JComponent newStatusBar() {
    final JPanel bar = new JPanel(new BorderLayout(16, 0));
    bar.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.getSeparatorColor()),
            BorderFactory.createEmptyBorder(6, 16, 6, 16)));
    Theme.setBorderlessButtonStyle(problemsLabel);
    problemsLabel.setToolTipText("Shows the problems in Overview");
    problemsLabel.addActionListener(e -> Optional.ofNullable(overview).ifPresent(this::showEditor));
    bar.add(problemsLabel, BorderLayout.WEST);
    return bar;
  }

  private void updateState() {
    saveState.setText(project.hasUnsavedChanges() ? "● Not saved" : "All saved");
    mapName.setText(project.name());
    saveButton.setEnabled(project.hasUnsavedChanges());
    undoButton.setEnabled(project.canUndo());
    redoButton.setEnabled(project.canRedo());
    final int problems = Optional.ofNullable(overview).map(o -> o.problems().size()).orElse(0);
    problemsLabel.setText(
        problems == 0 ? "No problems found" : problems == 1 ? "1 problem" : problems + " problems");
    setTitle((project.hasUnsavedChanges() ? "● " : "") + project.name() + " — Eddie's Map Editor");
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
      JOptionPane.showMessageDialog(
          this,
"""
The map's files were changed in Files and do not read yet. Fix them there first:
saving here would write the map as it was over them.""",
          "Save",
          JOptionPane.WARNING_MESSAGE);
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
        JOptionPane.showMessageDialog(
            this,
"""
Saved. The map maker writes the game file anew: comments in it are not kept.
The file as it was stays beside it as """
                + backup.getFileName()
                + ".",
            "Save",
            JOptionPane.INFORMATION_MESSAGE);
      }
      return true;
    } catch (final Exception e) {
      log.warn("Could not save {}", xml, e);
      JOptionPane.showMessageDialog(
          this, "Could not save the map:\n" + e.getMessage(), "Save", JOptionPane.ERROR_MESSAGE);
      return false;
    }
  }

  private void validateMap() {
    if (overview == null) {
      return;
    }
    final MapProject checked = project;
    final Game checkedGame = checked.game();
    final OverviewEditor shownIn = overview;
    problemsLabel.setText("Checking...");
    new SwingWorker<List<Problem>, Void>() {
      @Override
      protected List<Problem> doInBackground() {
        return MapChecks.all(checkedGame, checked.getFiles());
      }

      @Override
      protected void done() {
        // another map was opened or this one changed meanwhile: the result is of an older one
        if (shownIn != overview || checked.game() != checkedGame) {
          updateState();
          return;
        }
        try {
          shownIn.setValidated(get());
        } catch (final Exception e) {
          log.warn("Validating the map failed", e);
        }
        updateState();
        showEditor(shownIn);
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
      JOptionPane.showMessageDialog(
          this,
          "Starts " + tripleA + " with this game.\nChoose who plays and click Start Game.",
          "Playtest",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (final Exception e) {
      log.warn("Could not start the playtest", e);
      JOptionPane.showMessageDialog(
          this,
          "Could not start TripleA:\n" + e.getMessage(),
          "Playtest",
          JOptionPane.ERROR_MESSAGE);
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
          JOptionPane.showMessageDialog(
              MapMakerWindow.this,
              "Packed " + get() + " files into\n" + zip,
              "Export",
              JOptionPane.INFORMATION_MESSAGE);
        } catch (final Exception e) {
          log.warn("Could not export {}", zip, e);
          JOptionPane.showMessageDialog(
              MapMakerWindow.this,
              "Could not export the map:\n" + e.getMessage(),
              "Export",
              JOptionPane.ERROR_MESSAGE);
        }
      }
    }.execute();
  }

  private void bindKeys() {
    bind("save", KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK), this::save);
    bind("undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), this::undo);
    bind("redo", KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK), this::redo);
    bind("validate", KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), this::validateMap);
    bind("playtest", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), this::playtest);
    bind("export", KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), this::export);
  }

  private void bind(final String name, final KeyStroke key, final Runnable action) {
    getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
    getRootPane()
        .getActionMap()
        .put(
            name,
            new AbstractAction() {
              @Serial private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final ActionEvent e) {
                // the start page has no map to save, undo or test
                if (mapShown) {
                  action.run();
                }
              }
            });
  }
}
