package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import games.strategy.engine.ClientFileSystemHelper;
import games.strategy.engine.framework.ui.GameNotesView;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import lombok.extern.slf4j.Slf4j;
import org.triplea.map.data.elements.Game;
import org.triplea.map.description.file.MapDescriptionYaml;
import org.triplea.map.description.file.MapDescriptionYaml.MapGame;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.FileLint.Lint;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapGuide.Part;
import eddies.mapeditor.Theme.Status;

/**
 * The map's folder as in an editor for code: its files in a tree left, the chosen one right to
 * read and change, checked as the game reads it while it is typed ({@link FileLint}). With no file
 * open, the right side explains what a map folder holds. Folders of other maps can be added to the
 * tree to copy from; they are read only. Saving a file of the open map reloads the map from its
 * files.
 */
@Slf4j
final class FilesEditor implements Editor {
  private static final List<String> TEXT_FILES =
      List.of(".xml", ".txt", ".properties", ".yml", ".yaml", ".html", ".htm", ".csv", ".md");
  private static final List<String> PICTURES = List.of(".png", ".jpg", ".jpeg", ".gif");
  // larger text is shown read only: a text area of tens of megabytes is too slow to type in
  private static final long MAX_EDITED_SIZE = 20L * 1024 * 1024;
  private static final int PREVIEW_SIZE = 640;
  private static final String POLYGONS = "polygons.txt";
  private static final int THUMBNAIL = 32;
  private static final int TREE_WIDTH = 260;
  private static final int MAX_PROBLEM_ROWS = 6;
  private static final int INDENT = 16;
  private static final String OTHER_MAPS = "Other maps (read only)";
  private static final String BACKUP = ".bak";

  private final Supplier<Optional<Path>> mapFolder;
  private final Supplier<Game> game;
  private final Supplier<List<Part>> anatomy;
  private final BooleanSupplier mayWriteMapFile;
  private final Runnable reloadMap;
  private final Consumer<String> openTool;
  private final List<Path> otherFolders = new ArrayList<>();
  // backups are left out of the tree, but for those revealed, eg by the toast after a first save
  private final Set<Path> revealedBackups = new HashSet<>();
  private final Folders folders = new Folders();
  // made once the map's folder is known, as the tree asks for the folders at once
  private final JTree tree;
  private final JSplitPane page;
  private final JPanel fileSlot = new JPanel(new BorderLayout());
  private final JTextField search = new JTextField();
  private final JLabel breadcrumb = new JLabel(" ");
  private final JLabel readOnly = Theme.newSecondaryLabel("");
  private final JLabel changed = new JLabel("• Changed");
  private final JButton save = new JButton("Save file");
  private final JButton revert = new JButton("Revert");
  private final XmlTextArea text = new XmlTextArea();
  private final JScrollPane textScroll = SwingComponents.newJScrollPane(text);
  private final JCheckBox wrap = new JCheckBox("Wrap lines");
  // an HTML file as the game shows it, right of its text
  private final GameNotesView htmlPreview = new GameNotesView();
  private final DefaultListModel<Lint> problems = new DefaultListModel<>();
  private final JList<Lint> problemList = new JList<>(problems);
  private final JScrollPane problemScroll = SwingComponents.newJScrollPane(problemList);
  // "✓ The game reads this file" or "! n problems ▾", which opens the list
  private final JButton status = new JButton();
  // a pause in typing: check the text and show an HTML file again
  private final Timer lintLater =
      new Timer(
          600,
          e -> {
            previewHtml();
            lint();
          });
  // a pause in typing the search: walk the folders once for it
  private final Timer searchLater = new Timer(300, e -> searchChanged());
  private @Nullable Path open;
  private boolean editable;
  private boolean dirty;
  private boolean filling;
  private boolean html;
  // counts the edits, so a check of an older text is not shown
  private int edits;
  // the names the tree shows, in lower case; empty for all
  private String filter = "";
  // with a search: the matching files and the folders they are in
  private Set<Path> matching = Set.of();

  /**
   * @param project the open map, for its folder, its game and what its folder should hold
   * @param mayWriteMapFile asks whether a file of the open map may be written now, eg after saving
   *     the map's own changes; false to leave it
   * @param reloadMap reads the open map again from its files
   * @param openTool shows the tool of this title, eg the one that makes a file
   */
  FilesEditor(
      final Supplier<MapProject> project,
      final BooleanSupplier mayWriteMapFile,
      final Runnable reloadMap,
      final Consumer<String> openTool) {
    this(
        () -> project.get().getFolder().map(MapFolder::rootOf),
        () -> project.get().game(),
        () -> MapGuide.anatomy(project.get()),
        mayWriteMapFile,
        reloadMap,
        openTool);
  }

  private FilesEditor(
      final Supplier<Optional<Path>> mapFolder,
      final Supplier<Game> game,
      final Supplier<List<Part>> anatomy,
      final BooleanSupplier mayWriteMapFile,
      final Runnable reloadMap,
      final Consumer<String> openTool) {
    this.mapFolder = mapFolder;
    this.game = game;
    this.anatomy = anatomy;
    this.mayWriteMapFile = mayWriteMapFile;
    this.reloadMap = reloadMap;
    this.openTool = openTool;
    tree = new JTree(folders);
    lintLater.setRepeats(false);
    searchLater.setRepeats(false);
    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.setCellRenderer(new FileRenderer());
    tree.addTreeSelectionListener(
        e ->
            Optional.ofNullable(e.getNewLeadSelectionPath())
                .map(TreePath::getLastPathComponent)
                .filter(Path.class::isInstance)
                .map(Path.class::cast)
                .filter(p -> Files.isRegularFile(p) || hasFolderView(p))
                .ifPresent(this::openFile));
    search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search files");
    search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    OnTyping.attach(search, searchLater::restart);
    final JPopupMenu more = new JPopupMenu();
    more.add("Refresh").addActionListener(e -> folders.refresh());
    more.add(
            System.getProperty("os.name").startsWith("Mac")
                ? "Show in Finder"
                : "Show in Explorer")
        .addActionListener(e -> showInExplorer());
    more.add("Add a map to copy from…").addActionListener(e -> addFolder());
    final JButton moreButton = Theme.newIconButton("⋯", "More", () -> {});
    moreButton.addActionListener(e -> more.show(moreButton, 0, moreButton.getHeight()));
    final JPanel treeHeader = new JPanel(new BorderLayout(Theme.GAP_XS, 0));
    treeHeader.add(search, BorderLayout.CENTER);
    treeHeader.add(moreButton, BorderLayout.EAST);
    final JPanel treeColumn = new JPanel(new BorderLayout(0, Theme.GAP_S));
    treeColumn.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_S, Theme.GAP_S, 0, Theme.GAP_S));
    treeColumn.add(treeHeader, BorderLayout.NORTH);
    treeColumn.add(SwingComponents.newJScrollPane(tree), BorderLayout.CENTER);
    treeColumn.setMinimumSize(new Dimension(200, 0));
    treeColumn.setPreferredSize(new Dimension(TREE_WIDTH, 0));

    OnTyping.attach(text, this::typed);
    save.addActionListener(e -> saveFile());
    revert.addActionListener(e -> Optional.ofNullable(open).ifPresent(this::showFile));
    Theme.setPrimaryButtonStyle(save);
    Theme.setTertiaryButtonStyle(revert);
    changed.setForeground(Theme.getWarningColor());
    wrap.setToolTipText("Long lines wrap at the edge");
    wrap.addActionListener(e -> text.setLineWrap(wrap.isSelected()));
    textScroll.setRowHeaderView(new LineNumbers(text));
    Theme.setBorderlessButtonStyle(status);
    status.addActionListener(e -> toggleProblems());
    problemList.setCellRenderer(new LintRenderer());
    // a long message is cut, its whole text in the tool tip: a scroll bar would hide the one row
    problemScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    problemList.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting() && problemList.getSelectedValue() != null) {
            goToLine(problemList.getSelectedValue().line());
          }
        });
    page = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeColumn, fileSlot);
    page.setBorder(null);
    page.setContinuousLayout(true);
    showNothing();
  }

  @Override
  public String title() {
    return "Files";
  }

  @Override
  public Glyph glyph() {
    return Glyph.FILES;
  }

  @Override
  public String hint() {
    return "The map's files as on disk. Open one to change it; the game's files are checked as you"
        + " type. Save file keeps the first version as .bak and reads the map again.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  /**
   * Shows the open map's folder; a file not changed here is read again, eg after a save, and one no
   * longer in the tree, eg of the map left, is closed.
   */
  @Override
  public void showing() {
    folders.refresh();
    if (open == null) {
      // the folder's files may have changed meanwhile
      showNothing();
    } else if (!dirty) {
      final Path shown = open;
      if (Files.exists(shown) && tops().stream().anyMatch(shown::startsWith)) {
        showFile(shown);
      } else {
        open = null;
        showNothing();
      }
    }
  }

  /**
   * Whether the text of the open file may be dropped: unchanged, or the player agrees, eg before
   * the window closes or another map opens. It stays changed until {@link #forgetText}, as the
   * player may still cancel the leaving.
   */
  boolean mayDropText() {
    return !dirty
        || Dialogs.confirmRemove(
            page,
            "Drop changes",
            "Drop your changes to " + shortName(open) + "?\nWhat you typed there is not saved.",
            List.of(),
            "Drop");
  }

  /** Shows the file or folder in the tree, eg from the map's anatomy; a file opens beside it. */
  void reveal(final Path file) {
    final Optional<Path> top = tops().stream().filter(file::startsWith).findFirst();
    if (top.isEmpty()) {
      return;
    }
    if (isBackup(file) && revealedBackups.add(file)) {
      folders.refresh();
    }
    final List<Object> nodes = new ArrayList<>(List.of(folders.root));
    if (otherFolders.contains(top.get())) {
      nodes.add(OTHER_MAPS);
    }
    nodes.add(top.get());
    Path at = top.get();
    if (!file.equals(at)) {
      for (final Path name : top.get().relativize(file)) {
        at = at.resolve(name);
        nodes.add(at);
      }
    }
    final TreePath path = new TreePath(nodes.toArray());
    if (path.equals(tree.getSelectionPath())
        && (Files.isRegularFile(file) || hasFolderView(file))) {
      // no selection change, so the listener stays quiet: bring the file back
      openFile(file);
    } else {
      // the selection listener opens a file
      tree.setSelectionPath(path);
    }
    tree.scrollPathToVisible(path);
  }

  /** Forgets typed text once the map it belongs to is left; the file is read again if shown. */
  void forgetText() {
    dirty = false;
    updateButtons();
  }

  /** Asks for another map's folder and shows it below this map's, read only, to copy from. */
  void addFolder() {
    MapMakerWindow.chooseFolder(
            page,
            "Choose a map to copy from",
            mapFolder
                .get()
                .map(Path::getParent)
                .orElseGet(ClientFileSystemHelper::getUserMapsFolder))
        .filter(folder -> !otherFolders.contains(folder))
        .ifPresent(
            folder -> {
              otherFolders.add(folder);
              folders.refresh();
            });
  }

  private void showInExplorer() {
    mapFolder
        .get()
        .filter(folder -> Desktop.isDesktopSupported())
        .ifPresent(
            folder -> {
              try {
                Desktop.getDesktop().open(folder.toFile());
              } catch (final IOException | UnsupportedOperationException e) {
                log.info("Could not show {}", folder, e);
              }
            });
  }

  /** The map's folder, then the maps added to copy from. */
  private List<Path> tops() {
    final List<Path> tops = new ArrayList<>();
    mapFolder.get().ifPresent(tops::add);
    tops.addAll(otherFolders);
    return tops;
  }

  private void searchChanged() {
    filter = search.getText().strip().toLowerCase(Locale.ROOT);
    folders.refresh();
    if (!filter.isEmpty()) {
      // the matches are deep down, eg a unit picture: show them all
      for (int row = 0; row < tree.getRowCount(); row++) {
        tree.expandRow(row);
      }
    }
  }

  /**
   * Opens the file, after asking whether to drop the changes to the one open now; the map's folder
   * itself shows what a map folder holds.
   */
  private void openFile(final Path file) {
    final boolean mapRoot = mapFolder.get().filter(file::equals).isPresent();
    if (file.equals(open) && !mapRoot) {
      return;
    }
    if (mayDropText()) {
      if (mapRoot) {
        open = null;
        dirty = false;
        showNothing();
      } else {
        showFile(file);
      }
    } else {
      // the open file stays: the tree no longer points at the other one
      tree.clearSelection();
    }
  }

  private void showFile(final Path file) {
    open = file;
    dirty = false;
    // a check of the text shown before is dropped
    edits++;
    problems.clear();
    fileSlot.removeAll();
    // a backup shows as the file it was
    final String name = typeName(file);
    if (Files.isDirectory(file)) {
      showFolder(file);
    } else if (TEXT_FILES.stream().anyMatch(name::endsWith)) {
      showText(file);
    } else if (PICTURES.stream().anyMatch(name::endsWith)) {
      showPicture(file);
    } else {
      fileSlot.add(note("This is no text file the map maker shows."), BorderLayout.CENTER);
    }
    fileSlot.revalidate();
    fileSlot.repaint();
  }

  private void showText(final Path file) {
    try {
      final boolean ofThisMap = mapFolder.get().filter(file::startsWith).isPresent();
      final boolean small = Files.size(file) <= MAX_EDITED_SIZE;
      final boolean backup = isBackup(file);
      editable = ofThisMap && small && !backup;
      filling = true;
      final String name = typeName(file);
      html = name.matches(".*\\.html?");
      if (html || name.endsWith(".xml")) {
        text.colorAsXml();
      } else {
        text.colorAsPlain();
      }
      text.setText(MapFolder.readText(file));
      text.setCaretPosition(0);
      text.setEditable(editable);
      readOnly.setText(
          !ofThisMap
              ? "Read only: another map"
              : backup ? "Read only: a first version" : small ? "" : "Too large to change here");
      final JPanel editor = new JPanel(new BorderLayout(0, Theme.GAP_S));
      editor.setBorder(
          BorderFactory.createEmptyBorder(Theme.GAP_S, Theme.GAP_M, Theme.GAP_XS, Theme.GAP_M));
      editor.add(fileBar(file), BorderLayout.NORTH);
      if (html) {
        htmlPreview.setText(text.getText());
        editor.add(
            Panels.textAndPreview(textScroll, SwingComponents.newJScrollPane(htmlPreview)),
            BorderLayout.CENTER);
      } else {
        editor.add(textScroll, BorderLayout.CENTER);
      }
      final JPanel statusLine = new JPanel(new BorderLayout(Theme.GAP_S, 0));
      statusLine.add(status, BorderLayout.WEST);
      statusLine.add(wrap, BorderLayout.EAST);
      status.setText(" ");
      problemScroll.setVisible(false);
      final JPanel below = new JPanel(new BorderLayout(0, Theme.GAP_XS));
      below.add(problemScroll, BorderLayout.CENTER);
      below.add(statusLine, BorderLayout.SOUTH);
      editor.add(below, BorderLayout.SOUTH);
      fileSlot.add(editor, BorderLayout.CENTER);
      updateButtons();
      lint();
    } catch (final IOException e) {
      log.info("Could not read {}", file, e);
      fileSlot.add(note("Could not read " + file.getFileName() + ": " + e.getMessage()));
    } finally {
      filling = false;
    }
  }

  /** Where the file is, and what may be done with it: "• Changed", Revert and Save file. */
  private JPanel fileBar(final Path file) {
    breadcrumb.setText(shortName(file));
    breadcrumb.setToolTipText(file.toString());
    final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
    actions.add(readOnly);
    actions.add(changed);
    actions.add(revert);
    actions.add(save);
    final JPanel bar = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    bar.add(breadcrumb, BorderLayout.CENTER);
    bar.add(actions, BorderLayout.EAST);
    return bar;
  }

  private static boolean isBackup(final Path file) {
    return file.getFileName().toString().endsWith(BACKUP);
  }

  /** The file's name in lower case, of a backup without ".bak", which tells its type. */
  private static String typeName(final Path file) {
    final String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return isBackup(file) ? name.substring(0, name.length() - BACKUP.length()) : name;
  }

  /** The problems' list opens and closes below the text. */
  private void toggleProblems() {
    problemScroll.setVisible(!problemScroll.isVisible() && !problems.isEmpty());
    problemList.setVisibleRowCount(Math.min(MAX_PROBLEM_ROWS, problems.size()));
    showStatus();
    fileSlot.revalidate();
  }

  /** The file as the path in its map's folder, eg map › games › theatre.xml. */
  private String shortName(@Nullable final Path file) {
    if (file == null) {
      return "";
    }
    return tops().stream()
        .filter(file::startsWith)
        .findFirst()
        .map(
            top ->
                Stream.concat(
                        otherFolders.contains(top)
                            ? Stream.of(top.getFileName().toString())
                            : Stream.empty(),
                        StreamSupport.stream(top.relativize(file).spliterator(), false)
                            .map(Path::toString))
                    .collect(Collectors.joining(" › ")))
        .orElse(file.toString());
  }

  private void showPicture(final Path file) {
    try {
      final BufferedImage picture = ImageIO.read(file.toFile());
      if (picture == null) {
        fileSlot.add(note(file.getFileName() + " is no picture Java reads."));
        return;
      }
      final double scale =
          Math.min(1, PREVIEW_SIZE / (double) Math.max(picture.getWidth(), picture.getHeight()));
      final Image shown =
          scale < 1
              ? picture.getScaledInstance(
                  (int) (picture.getWidth() * scale),
                  (int) (picture.getHeight() * scale),
                  Image.SCALE_FAST)
              : picture;
      final JLabel label = new JLabel(new ImageIcon(shown));
      label.setVerticalAlignment(SwingConstants.TOP);
      label.setHorizontalAlignment(SwingConstants.LEFT);
      final JLabel title =
          new JLabel(
              shortName(file) + "  (" + picture.getWidth() + "×" + picture.getHeight() + ")");
      title.setToolTipText(file.toString());
      title.setBorder(
          BorderFactory.createEmptyBorder(
              Theme.GAP_S, Theme.GAP_M, Theme.GAP_S, Theme.GAP_M));
      fileSlot.add(title, BorderLayout.NORTH);
      fileSlot.add(SwingComponents.newJScrollPane(label), BorderLayout.CENTER);
    } catch (final IOException e) {
      fileSlot.add(note("Could not read " + file.getFileName() + ": " + e.getMessage()));
    }
  }

  /**
   * Whether there is a view for the folder of the open map: map.yml's games, the picture folders
   * the map lacks, or one of the picture folders.
   */
  private boolean hasFolderView(final Path folder) {
    return Files.isDirectory(folder)
        && mapFolder.get().filter(folder::startsWith).isPresent()
        && (Files.isRegularFile(folder.resolve(MapDescriptionYaml.MAP_YAML_FILE_NAME))
            || Files.isRegularFile(folder.resolve(POLYGONS))
            || MapFolder.PICTURE_FOLDERS.containsKey(folder.getFileName().toString()));
  }

  private void showFolder(final Path folder) {
    final Form form = new Form(PREVIEW_SIZE);
    form.title(folder.getFileName().toString());
    final Path yml = folder.resolve(MapDescriptionYaml.MAP_YAML_FILE_NAME);
    if (Files.isRegularFile(yml)) {
      showGames(form, folder, yml);
    }
    if (Files.isRegularFile(folder.resolve(POLYGONS))) {
      showMissingPictureFolders(form, folder);
    }
    Optional.ofNullable(MapFolder.PICTURE_FOLDERS.get(folder.getFileName().toString()))
        .ifPresent(what -> showPictures(form, folder, what));
    form.end();
    fileSlot.add(SwingComponents.newJScrollPane(form), BorderLayout.CENTER);
  }

  /** The map's name and its games as map.yml lists them, each game to rename. */
  private void showGames(final Form form, final Path folder, final Path yml) {
    form.section(
        "Games",
        "How the game lists the map and its games, in map.yml. The games find the map by its name,"
            + " so the name stays; a game's name is the one players choose it by.");
    final Optional<MapDescriptionYaml> yaml = MapDescriptionYaml.fromFile(yml);
    if (yaml.isEmpty()) {
      form.note("map.yml does not read: open it to fix it.");
      return;
    }
    form.field("Map name", new JLabel(yaml.get().getMapName()));
    final List<String> names =
        yaml.get().getMapGameList().stream().map(MapGame::getGameName).toList();
    for (final MapGame mapGame : yaml.get().getMapGameList()) {
      final JTextField name = new JTextField(mapGame.getGameName());
      final JButton rename = new JButton("Rename");
      rename.addActionListener(
          e -> renameGame(folder, yml, mapGame, name.getText().strip(), names));
      name.addActionListener(e -> rename.doClick());
      final JPanel line = new JPanel(new BorderLayout(Theme.GAP_S, 0));
      line.add(name, BorderLayout.CENTER);
      line.add(rename, BorderLayout.EAST);
      final JPanel row = new JPanel(new BorderLayout(0, Theme.GAP_XS));
      row.add(line, BorderLayout.CENTER);
      row.add(Theme.newSecondaryLabel("in " + mapGame.getXmlFileName()), BorderLayout.SOUTH);
      form.field("Game name", row);
    }
  }

  private void renameGame(
      final Path folder,
      final Path yml,
      final MapGame mapGame,
      final String name,
      final List<String> names) {
    if (name.equals(mapGame.getGameName())) {
      return;
    }
    if (name.isEmpty() || names.contains(name)) {
      Dialogs.warn(
          page,
          "Rename game",
          name.isEmpty() ? "A game needs a name." : "Another game has the name " + name + ".");
      return;
    }
    try {
      MapFolder.renameGame(yml, mapGame.getXmlFileName(), name);
    } catch (final IOException e) {
      log.warn("Could not rename the game in {}", yml, e);
      Dialogs.warn(page, "Rename game", "Could not write map.yml\n" + e.getMessage());
    }
    showFile(folder);
  }

  /** The picture folders the map has not, each to add. */
  private void showMissingPictureFolders(final Form form, final Path folder) {
    final List<String> missing =
        MapFolder.PICTURE_FOLDERS.keySet().stream()
            .sorted()
            .filter(name -> !Files.isDirectory(folder.resolve(name)))
            .toList();
    if (missing.isEmpty()) {
      return;
    }
    form.section(
        "Picture folders", "Folders of pictures the game takes in place of its own drawing.");
    for (final String name : missing) {
      final JButton add = new JButton("Add");
      add.addActionListener(e -> addPictureFolder(folder.resolve(name)));
      form.row(
          slotRow(name + ": " + MapFolder.PICTURE_FOLDERS.get(name), true, add, Optional.empty()));
    }
  }

  /** Makes the picture folder if needed and shows it, with the pictures to import. */
  private void addPictureFolder(final Path pictures) {
    try {
      Files.createDirectories(pictures);
    } catch (final IOException e) {
      log.warn("Could not make {}", pictures, e);
      Dialogs.warn(page, "Add folder", "Could not make " + pictures.getFileName() + "\n" + e);
      return;
    }
    folders.refresh();
    reveal(pictures);
  }

  /** The pictures the game looks for in the folder, those missing marked, each to import. */
  private void showPictures(final Form form, final Path folder, final String what) {
    final List<String> pictures =
        MapFolder.picturesFor(folder.getFileName().toString(), game.get());
    final long there = pictures.stream().filter(p -> Files.exists(folder.resolve(p))).count();
    form.section("Pictures", what);
    form.note(
        there
            + " of "
            + pictures.size()
            + " there; the game draws its own where one is missing.");
    final JButton importHere = new JButton("Import picture here…");
    importHere.setToolTipText("Copies a picture into this folder under its own name");
    importHere.addActionListener(
        e ->
            choosePicture(folder)
                .ifPresent(
                    chosen ->
                        importInto(
                            folder,
                            chosen,
                            () ->
                                MapFolder.copyInto(
                                    chosen, folder.resolve(chosen.getFileName().toString())))));
    final JPanel importRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    importRow.add(importHere);
    form.row(importRow);
    for (final String picture : pictures) {
      final JButton take = new JButton("Import…");
      take.addActionListener(
          e ->
              choosePicture(folder)
                  .ifPresent(
                      chosen ->
                          importInto(
                              folder,
                              chosen,
                              () -> MapFolder.importPicture(chosen, folder.resolve(picture)))));
      final boolean present = Files.exists(folder.resolve(picture));
      form.row(
          slotRow(
              present ? picture : Status.TODO.mark() + " " + picture,
              !present,
              take,
              present ? thumbnail(folder.resolve(picture)) : Optional.empty()));
    }
  }

  /** The picture shrunk to fit a row, empty when it does not read. */
  private static Optional<ImageIcon> thumbnail(final Path file) {
    try {
      final BufferedImage picture = ImageIO.read(file.toFile());
      if (picture == null) {
        return Optional.empty();
      }
      final double scale =
          Math.min(1, THUMBNAIL / (double) Math.max(picture.getWidth(), picture.getHeight()));
      return Optional.of(
          new ImageIcon(
              picture.getScaledInstance(
                  Math.max(1, (int) (picture.getWidth() * scale)),
                  Math.max(1, (int) (picture.getHeight() * scale)),
                  Image.SCALE_SMOOTH)));
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  private static JPanel slotRow(
      final String text,
      final boolean quiet,
      final JButton action,
      final Optional<ImageIcon> thumbnail) {
    final JLabel label = new JLabel(text);
    thumbnail.ifPresent(label::setIcon);
    if (quiet) {
      label.setForeground(Theme.getSecondaryTextColor());
    }
    final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    row.add(label, BorderLayout.CENTER);
    row.add(action, BorderLayout.EAST);
    return row;
  }

  private Optional<Path> choosePicture(final Path folder) {
    return MapMakerWindow.chooseFileToOpen(
        page, "Choose a picture", folder, "png", "gif", "jpg", "jpeg");
  }

  /** Writes the picture, then shows the folder again with it. */
  private void importInto(final Path folder, final Path chosen, final Import write) {
    try {
      write.run();
    } catch (final IOException e) {
      log.warn("Could not import {}", chosen, e);
      Dialogs.warn(
          page,
          "Import picture",
          "Could not import " + chosen.getFileName() + "\n" + e.getMessage());
    }
    folders.refresh();
    reveal(folder);
    showFile(folder);
  }

  @FunctionalInterface
  private interface Import {
    void run() throws IOException;
  }

  /** No file open: what a map folder holds, each part to open or to make in its tool. */
  private void showNothing() {
    fileSlot.removeAll();
    final List<Part> parts = anatomy.get();
    if (parts.isEmpty()) {
      fileSlot.add(
          mapFolder.get().isPresent()
              ? Panels.empty(Glyph.FILES, "Choose a file", "Open a file in the tree to change it.")
              : Panels.empty(
                  Glyph.FILES, "No files yet", "The map's files show here once it is saved."),
          BorderLayout.CENTER);
    } else {
      final Form form = new Form(PREVIEW_SIZE);
      form.title("What's in a map folder");
      final JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_L, 0));
      legend.add(Theme.newStatusLabel(Status.DONE, "there"));
      legend.add(Theme.newStatusLabel(Status.PROBLEM, "missing, the game needs it"));
      legend.add(Theme.newStatusLabel(Status.TODO, "missing, optional"));
      form.row(legend);
      form.row(anatomyRows(parts));
      form.end();
      fileSlot.add(SwingComponents.newJScrollPane(form), BorderLayout.CENTER);
    }
    fileSlot.revalidate();
    fileSlot.repaint();
  }

  /**
   * One row per part: its mark, its name (a click opens it), what it is for, and where it is made;
   * then the picture folders, with how many of the game's pictures they have.
   */
  private JPanel anatomyRows(final List<Part> parts) {
    final JPanel rows = new JPanel(new GridBagLayout());
    rows.setOpaque(false);
    int row = 0;
    for (final Part part : parts) {
      final Optional<JButton> link =
          part.name().equals(MapDescriptionYaml.MAP_YAML_FILE_NAME) && part.present()
              ? Optional.of(link("Games…", () -> showFile(part.file().getParent())))
              : Optional.ofNullable(part.tool())
                  .map(tool -> link(verb(part) + " in " + tool, () -> openTool.accept(tool)));
      addAnatomyRow(
          rows,
          row++,
          part.depth(),
          part.present() ? Status.DONE : part.required() ? Status.PROBLEM : Status.TODO,
          part.name(),
          part.present() ? Optional.of(part.file()) : Optional.empty(),
          part.purpose(),
          link);
    }
    // the picture folders sit beside polygons.txt
    final Optional<Part> polygons =
        parts.stream().filter(part -> part.name().equals(POLYGONS)).findFirst();
    if (polygons.isPresent()) {
      final Path content = polygons.get().file().getParent();
      for (final String name : MapFolder.PICTURE_FOLDERS.keySet().stream().sorted().toList()) {
        final Path folder = content.resolve(name);
        final boolean present = Files.isDirectory(folder);
        final String purpose = MapFolder.PICTURE_FOLDERS.get(name).replaceFirst("\\.$", "");
        addAnatomyRow(
            rows,
            row++,
            polygons.get().depth(),
            present ? Status.DONE : Status.TODO,
            name + "/",
            present ? Optional.of(folder) : Optional.empty(),
            present ? purpose + " · " + picturesThere(folder) : purpose,
            Optional.of(link("Import…", () -> addPictureFolder(folder))));
      }
    }
    return rows;
  }

  /** "Make" for a part not there yet and for the relief, which is made again, else "Edit". */
  private static String verb(final Part part) {
    return part.present() && !part.name().startsWith("reliefTiles") ? "Edit" : "Make";
  }

  /** "3 of 12 pictures": of those the game looks for in the folder. */
  private String picturesThere(final Path folder) {
    final List<String> pictures =
        MapFolder.picturesFor(folder.getFileName().toString(), game.get());
    return pictures.stream().filter(p -> Files.exists(folder.resolve(p))).count()
        + " of "
        + pictures.size()
        + " pictures";
  }

  private void addAnatomyRow(
      final JPanel rows,
      final int row,
      final int depth,
      final Status mark,
      final String name,
      final Optional<Path> file,
      final String purpose,
      final Optional<JButton> link) {
    final JLabel markLabel = new JLabel(mark.mark());
    markLabel.setForeground(mark.color());
    markLabel.getAccessibleContext().setAccessibleName(
        switch (mark) {
          case DONE -> "there";
          case PROBLEM, WARNING -> "missing";
          case TODO -> "optional";
        });
    // one width for every mark, so the names line up whatever the mark
    final FontMetrics metrics = markLabel.getFontMetrics(markLabel.getFont());
    final int markWidth =
        Stream.of(Status.values()).mapToInt(s -> metrics.stringWidth(s.mark())).max().orElse(0);
    markLabel.setHorizontalAlignment(SwingConstants.CENTER);
    markLabel.setPreferredSize(new Dimension(markWidth, markLabel.getPreferredSize().height));
    final JButton nameLink = link(name, () -> file.ifPresent(this::reveal));
    final JLabel plainName = new JLabel(name);
    // where the link's text starts, so that all names line up
    plainName.setBorder(new EmptyBorder(nameLink.getInsets()));
    final JComponent nameLabel = file.isPresent() ? nameLink : plainName;
    final JPanel nameCell = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    nameCell.setOpaque(false);
    nameCell.setBorder(BorderFactory.createEmptyBorder(0, depth * INDENT, 0, 0));
    nameCell.add(markLabel, BorderLayout.WEST);
    nameCell.add(nameLabel, BorderLayout.CENTER);
    final GridBagConstraints at = new GridBagConstraints();
    at.gridy = row;
    at.anchor = GridBagConstraints.WEST;
    at.insets = new Insets(1, 0, 1, Theme.GAP_L);
    at.ipady = 4;
    rows.add(nameCell, at);
    at.weightx = 1;
    at.fill = GridBagConstraints.HORIZONTAL;
    rows.add(Theme.newSecondaryLabel(purpose), at);
    at.weightx = 0;
    at.fill = GridBagConstraints.NONE;
    at.anchor = GridBagConstraints.EAST;
    at.insets = new Insets(1, 0, 1, 0);
    rows.add(link.<JComponent>map(b -> b).orElseGet(JLabel::new), at);
  }

  private static JButton link(final String text, final Runnable action) {
    final JButton link = new JButton(text);
    Theme.setTertiaryButtonStyle(link);
    link.addActionListener(e -> action.run());
    return link;
  }

  private static JComponent note(final String message) {
    final JLabel note = new JLabel(message, SwingConstants.CENTER);
    note.setForeground(Theme.getSecondaryTextColor());
    return note;
  }

  /** "• Changed", Revert and Save file show only while there is something to save. */
  private void updateButtons() {
    final boolean toSave = editable && dirty;
    changed.setVisible(toSave);
    revert.setVisible(toSave);
    save.setVisible(toSave);
    readOnly.setVisible(!editable);
    fileSlot.revalidate();
  }

  /** Shows the typed HTML beside it, where the reader was in it; called when typing pauses. */
  void previewHtml() {
    if (!html) {
      return;
    }
    Panels.showKeepingPlace(htmlPreview, text.getText());
  }

  /** Checks the text in the background; a check of a text changed since is dropped. */
  private void lint() {
    final Path file = open;
    if (file == null || !FileLint.isChecked(file.getFileName().toString())) {
      return;
    }
    final String checked = text.getText();
    final int edit = edits;
    setStatus(Status.TODO, "Checking…");
    new SwingWorker<List<Lint>, Void>() {
      @Override
      protected List<Lint> doInBackground() {
        return FileLint.lint(file.getFileName().toString(), checked);
      }

      @Override
      protected void done() {
        if (edit != edits || !file.equals(open)) {
          return;
        }
        try {
          final List<Lint> found = get();
          problems.clear();
          found.forEach(problems::addElement);
          if (found.isEmpty()) {
            problemScroll.setVisible(false);
          }
          problemList.setVisibleRowCount(Math.min(MAX_PROBLEM_ROWS, found.size()));
          showStatus();
          fileSlot.revalidate();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
          log.info("Could not check {}", file, e);
          setStatus(Status.WARNING, "Could not check");
        }
      }
    }.execute();
  }

  /** The checked file's problems: none, or how many, with the list open or closed. */
  private void showStatus() {
    if (problems.isEmpty()) {
      setStatus(Status.DONE, "The game reads this file");
    } else {
      setStatus(
          Status.PROBLEM,
          problems.size()
              + (problems.size() == 1 ? " problem " : " problems ")
              + (problemScroll.isVisible() ? "▴" : "▾"));
    }
  }

  private void setStatus(final Status shown, final String words) {
    status.setText(shown.mark() + " " + words);
    status.setForeground(shown.color());
  }

  private void goToLine(final int line) {
    if (line < 1) {
      return;
    }
    try {
      final int start = text.getLineStartOffset(Math.min(line, text.getLineCount()) - 1);
      text.setCaretPosition(start);
      text.requestFocusInWindow();
      final Rectangle shown = text.modelToView2D(start).getBounds();
      text.scrollRectToVisible(shown);
    } catch (final BadLocationException e) {
      log.debug("No line {} in {}", line, open, e);
    }
  }

  /** Writes the file, keeping its first version as .bak, and reloads the map from its files. */
  private void saveFile() {
    final Path file = open;
    if (file == null || !editable || !mayWriteMapFile.getAsBoolean()) {
      return;
    }
    try {
      final String written = text.getText();
      MapFolder.replaceText(file, written);
      dirty = false;
      updateButtons();
      reloadMap.run();
    } catch (final IOException e) {
      log.warn("Could not save {}", file, e);
      Dialogs.warn(page, "Save file", "Could not save " + file.getFileName() + "\n" + e);
    }
  }

  /** Each change of the text marks it changed and checks it again after a pause. */
  private void typed() {
    if (filling) {
      return;
    }
    edits++;
    dirty = true;
    updateButtons();
    lintLater.restart();
  }

  /**
   * The folders of the tree: the open map's first, then the maps added to copy from under their
   * own heading; directories before files, by name, read when first opened. A search shows only
   * what matches and the folders with matches in them.
   */
  private final class Folders implements TreeModel {
    private final Object root = "Folders";
    private final Map<Path, List<Path>> listed = new HashMap<>();
    private final List<TreeModelListener> listeners = new ArrayList<>();

    void refresh() {
      listed.clear();
      matching = filter.isEmpty() ? Set.of() : findMatching();
      final TreeModelEvent changed = new TreeModelEvent(this, new Object[] {root});
      listeners.forEach(l -> l.treeStructureChanged(changed));
      if (getChildCount(root) > 0) {
        tree.expandRow(0);
      }
      // the new structure dropped the selection: the open file stays marked in the tree
      if (open != null && filter.isEmpty() && Files.exists(open)) {
        reveal(open);
      }
    }

    @Override
    public Object getRoot() {
      return root;
    }

    @Override
    public Object getChild(final Object parent, final int index) {
      return childrenOf(parent).get(index);
    }

    @Override
    public int getChildCount(final Object parent) {
      return childrenOf(parent).size();
    }

    @Override
    public boolean isLeaf(final Object node) {
      return node instanceof Path path && !Files.isDirectory(path);
    }

    @Override
    public void valueForPathChanged(final TreePath path, final Object newValue) {
      // the names are not changed in the tree
    }

    @Override
    public int getIndexOfChild(final Object parent, final Object child) {
      return childrenOf(parent).indexOf(child);
    }

    /** The children, the heading of the other maps among them. */
    private List<?> childrenOf(final Object parent) {
      if (parent == root) {
        final List<Object> tops = new ArrayList<>();
        mapFolder.get().ifPresent(tops::add);
        if (!otherFolders.isEmpty()) {
          tops.add(OTHER_MAPS);
        }
        return tops;
      }
      if (parent == OTHER_MAPS) {
        return otherFolders;
      }
      return listed.computeIfAbsent(
          (Path) parent,
          folder -> list(folder).stream().filter(FilesEditor.this::matchesSearch).toList());
    }

    @Override
    public void addTreeModelListener(final TreeModelListener listener) {
      listeners.add(listener);
    }

    @Override
    public void removeTreeModelListener(final TreeModelListener listener) {
      listeners.remove(listener);
    }
  }

  /** Whether the file's name has the searched text, or the folder has such a file in it. */
  private boolean matchesSearch(final Path file) {
    return filter.isEmpty() || matching.contains(file);
  }

  /**
   * The files below the tree's folders whose name has the searched text, and the folders they are
   * in; what the tree leaves out is left out here too.
   */
  private Set<Path> findMatching() {
    final Set<Path> found = new HashSet<>();
    for (final Path top : tops()) {
      try (Stream<Path> inside = Files.walk(top)) {
        inside
            .filter(p -> !p.equals(top))
            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains(filter))
            .filter(p -> isShownBelow(top, p))
            .forEach(
                p -> {
                  for (Path at = p; !at.equals(top); at = at.getParent()) {
                    found.add(at);
                  }
                });
      } catch (final IOException | UncheckedIOException e) {
        log.info("Could not search {}", top, e);
      }
    }
    return Set.copyOf(found);
  }

  /** Whether the tree shows the file and every folder between it and the top. */
  private boolean isShownBelow(final Path top, final Path file) {
    for (Path at = file; !at.equals(top); at = at.getParent()) {
      if (!isShown(at)) {
        return false;
      }
    }
    return true;
  }

  /** Hidden files are left out, and backups but for those revealed. */
  private boolean isShown(final Path file) {
    return !file.getFileName().toString().startsWith(".")
        && (!isBackup(file) || revealedBackups.contains(file));
  }

  /** A folder's entries: folders first, then files, by name; hidden ones and backups left out. */
  private List<Path> list(final Path folder) {
    try (Stream<Path> entries = Files.list(folder)) {
      return entries
          .filter(this::isShown)
          .sorted(
              Comparator.comparing((Path p) -> !Files.isDirectory(p))
                  .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
          .toList();
    } catch (final IOException e) {
      log.info("Could not list {}", folder, e);
      return List.of();
    }
  }

  /** Shows a file or folder by its name, the heading of the other maps as it is. */
  private static final class FileRenderer extends DefaultTreeCellRenderer {
    @Serial private static final long serialVersionUID = 1L;

    @Override
    public Component getTreeCellRendererComponent(
        final JTree shownIn,
        final Object value,
        final boolean selected,
        final boolean expanded,
        final boolean leaf,
        final int row,
        final boolean hasFocus) {
      final String name =
          value instanceof Path path ? path.getFileName().toString() : String.valueOf(value);
      return super.getTreeCellRendererComponent(
          shownIn, name, selected, expanded, leaf, row, hasFocus);
    }
  }

  /** A problem as "Line 12: what is wrong", or the message alone without a line. */
  private static final class LintRenderer extends DefaultListCellRenderer {
    @Serial private static final long serialVersionUID = 1L;

    @Override
    public Component getListCellRendererComponent(
        final JList<?> list,
        final Object value,
        final int index,
        final boolean selected,
        final boolean focused) {
      final Lint lint = (Lint) value;
      final String shown = (lint.line() > 0 ? "Line " + lint.line() + ": " : "") + lint.message();
      final Component row =
          super.getListCellRendererComponent(list, shown, index, selected, focused);
      setToolTipText(shown);
      return row;
    }
  }
}
