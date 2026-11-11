package eddies.mapeditor;

import games.strategy.engine.ClientFileSystemHelper;
import games.strategy.engine.framework.ui.GameNotesView;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
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

/**
 * The map's folder as in an editor for code: its files in a tree, the file browser beside the tools
 * ({@link #page()}), the chosen one in the middle ({@link #preview()}) to read and change, checked
 * as the game reads it while it is typed ({@link FileLint}), the problems below it. Folders of
 * other maps can be added to the tree to copy from; they are read only. Saving a file of the open
 * map reloads the map from its files.
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

  private final Supplier<Optional<Path>> mapFolder;
  private final Supplier<Game> game;
  private final BooleanSupplier mayWriteMapFile;
  private final Runnable reloadMap;
  private final Runnable fileShown;
  private final List<Path> otherFolders = new ArrayList<>();
  private final Folders folders = new Folders();
  // made once the map's folder is known, as the tree asks for the folders at once
  private final JTree tree;
  private final JPanel page = new JPanel(new BorderLayout());
  private final JPanel fileSlot = new JPanel(new BorderLayout());
  private final JLabel fileLabel = new JLabel(" ");
  private final JButton save = new JButton("Save file");
  private final JButton revert = new JButton("Revert");
  private final XmlTextArea text = new XmlTextArea();
  // an HTML file as the game shows it, right of its text
  private final GameNotesView htmlPreview = new GameNotesView();
  // one for the text area, which every file shares
  private final LineNumbers lineNumbers = new LineNumbers(text);
  private final DefaultListModel<Lint> problems = new DefaultListModel<>();
  private final JList<Lint> problemList = new JList<>(problems);
  private final JLabel problemsTitle = Theme.newSectionLabel("Problems");
  // a pause in typing: check the text and show an HTML file again
  private final Timer lintLater =
      new Timer(
          600,
          e -> {
            previewHtml();
            lint();
          });
  private @Nullable Path open;
  private boolean editable;
  private boolean dirty;
  private boolean filling;
  private boolean html;
  // counts the edits, so a check of an older text is not shown
  private int edits;

  /**
   * @param mapFolder the open map's folder, with its map.yml
   * @param mayWriteMapFile asks whether a file of the open map may be written now, eg after saving
   *     the map's own changes; false to leave it
   * @param reloadMap reads the open map again from its files
   * @param fileShown brings the open file to the middle, eg when it is chosen in the tree
   * @param game the open map's game, for the pictures its folders should have
   */
  FilesEditor(
      final Supplier<Optional<Path>> mapFolder,
      final Supplier<Game> game,
      final BooleanSupplier mayWriteMapFile,
      final Runnable reloadMap,
      final Runnable fileShown) {
    this.mapFolder = mapFolder;
    this.fileShown = fileShown;
    this.mayWriteMapFile = mayWriteMapFile;
    this.reloadMap = reloadMap;
    this.game = game;
    tree = new JTree(folders);
    lintLater.setRepeats(false);
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
    // a click on the file already chosen brings it back to the middle, eg after a tool's view
    tree.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(final MouseEvent e) {
            Optional.ofNullable(tree.getPathForLocation(e.getX(), e.getY()))
                .map(TreePath::getLastPathComponent)
                .filter(clicked -> clicked.equals(open))
                .ifPresent(same -> fileShown.run());
          }
        });
    // and Enter on it, for the keyboard
    tree.getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "showFile");
    tree.getActionMap()
        .put(
            "showFile",
            new AbstractAction() {
              @Serial private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final ActionEvent e) {
                Optional.ofNullable(tree.getSelectionPath())
                    .map(TreePath::getLastPathComponent)
                    .filter(Path.class::isInstance)
                    .map(Path.class::cast)
                    .filter(Files::isRegularFile)
                    .ifPresent(FilesEditor.this::openFile);
              }
            });
    final JButton addFolder = new JButton("Add map...", MakerIcon.small(Glyph.FILES));
    addFolder.addActionListener(e -> addFolder());
    final JButton refresh = new JButton("Refresh");
    refresh.setToolTipText("Reads the folders again, eg after files were added outside");
    refresh.addActionListener(e -> folders.refresh());
    final JPanel treeButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    treeButtons.add(
        Help.with(
            addFolder,
            "Shows another map's folder below this one, read only: open its files and copy what"
                + " you need into yours."));
    treeButtons.add(refresh);
    final JPanel treeColumn = new JPanel(new BorderLayout(0, 8));
    treeColumn.add(SwingComponents.newJScrollPane(tree), BorderLayout.CENTER);
    treeColumn.add(treeButtons, BorderLayout.SOUTH);

    text.getDocument().addDocumentListener(new Typed());
    save.addActionListener(e -> saveFile());
    revert.addActionListener(e -> Optional.ofNullable(open).ifPresent(this::showFile));
    Theme.setPrimaryButtonStyle(save);
    problemList.setCellRenderer(new LintRenderer());
    problemList.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting() && problemList.getSelectedValue() != null) {
            goToLine(problemList.getSelectedValue().line());
          }
        });
    page.add(treeColumn, BorderLayout.CENTER);
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
    return "The map's files as on disk. Choose one in the tree to change it in the middle; the"
        + " game's files are checked as you type and their problems listed below them. Add other"
        + " maps to copy from. Save file keeps the first version as .bak and reads the map again.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  /** The chosen file in the middle, with its problems below it. */
  @Override
  public Optional<JComponent> preview() {
    return Optional.of(fileSlot);
  }

  /**
   * Shows the open map's folder; a file not changed here is read again, eg after a save, and one no
   * longer in the tree, eg of the map left, is closed.
   */
  @Override
  public void showing() {
    folders.refresh();
    if (open != null && !dirty) {
      final Path shown = open;
      if (Files.isRegularFile(shown)
          && folders.children(folders.root).stream().anyMatch(shown::startsWith)) {
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
        || JOptionPane.showConfirmDialog(
                page,
                "Drop your changes to " + shortName(open) + " in Files?",
                "Files",
                JOptionPane.OK_CANCEL_OPTION)
            == JOptionPane.OK_OPTION;
  }

  /**
   * Shows the file or folder in the tree, eg from the map's anatomy; a file opens in the middle.
   */
  void reveal(final Path file) {
    final Optional<Path> top =
        folders.children(folders.root).stream().filter(file::startsWith).findFirst();
    if (top.isEmpty()) {
      return;
    }
    final List<Object> nodes = new ArrayList<>(List.of(folders.root, top.get()));
    Path at = top.get();
    if (!file.equals(at)) {
      for (final Path name : top.get().relativize(file)) {
        at = at.resolve(name);
        nodes.add(at);
      }
    }
    final TreePath path = new TreePath(nodes.toArray());
    if (path.equals(tree.getSelectionPath()) && Files.isRegularFile(file)) {
      // no selection change, so the listener stays quiet: bring the file back to the middle
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

  /** Opens the file, after asking whether to drop the changes to the one open now. */
  private void openFile(final Path file) {
    if (file.equals(open)) {
      fileShown.run();
      return;
    }
    if (mayDropText()) {
      showFile(file);
      fileShown.run();
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
    final String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
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
      editable = ofThisMap && small;
      filling = true;
      final String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
      html = name.matches(".*\\.html?");
      if (html || name.endsWith(".xml")) {
        text.colorAsXml();
      } else {
        text.colorAsPlain();
      }
      text.setText(MapFolder.readText(file));
      text.setCaretPosition(0);
      text.setEditable(editable);
      fileLabel.setText(
          shortName(file)
              + (ofThisMap ? "" : "  (another map: read only)")
              + (small ? "" : "  (too large to change here)"));
      final JScrollPane scroll = SwingComponents.newJScrollPane(text);
      scroll.setRowHeaderView(lineNumbers);
      final JPanel bar = new JPanel(new BorderLayout(8, 0));
      bar.add(fileLabel, BorderLayout.CENTER);
      final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
      buttons.add(revert);
      buttons.add(save);
      buttons.setVisible(editable);
      bar.add(buttons, BorderLayout.EAST);
      final JPanel below = new JPanel(new BorderLayout(0, 4));
      below.add(problemsTitle, BorderLayout.NORTH);
      final JScrollPane problemScroll = SwingComponents.newJScrollPane(problemList);
      problemScroll.setPreferredSize(new Dimension(0, 110));
      below.add(problemScroll, BorderLayout.CENTER);
      below.setVisible(FileLint.isChecked(file.getFileName().toString()));
      final JPanel editor = new JPanel(new BorderLayout(0, 8));
      editor.add(bar, BorderLayout.NORTH);
      if (html) {
        htmlPreview.setText(text.getText());
        editor.add(
            Panels.textAndPreview(scroll, SwingComponents.newJScrollPane(htmlPreview)),
            BorderLayout.CENTER);
      } else {
        editor.add(scroll, BorderLayout.CENTER);
      }
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

  /** The file as "map folder / path in it", eg theatre / map/games/theatre.xml. */
  private String shortName(final Path file) {
    return folders.children(folders.root).stream()
        .filter(file::startsWith)
        .findFirst()
        .map(top -> top.getFileName() + " / " + top.relativize(file).toString().replace('\\', '/'))
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
      fileSlot.add(
          new JLabel(
              shortName(file) + "  (" + picture.getWidth() + "×" + picture.getHeight() + ")"),
          BorderLayout.NORTH);
      fileSlot.add(SwingComponents.newJScrollPane(label), BorderLayout.CENTER);
    } catch (final IOException e) {
      fileSlot.add(note("Could not read " + file.getFileName() + ": " + e.getMessage()));
    }
  }

  /**
   * Whether the middle has something for the folder of the open map: map.yml's games, the picture
   * folders the map lacks, or one of the picture folders.
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
        "map.yml",
        "How the game lists the map and its games. The games find the map by its name, so the"
            + " name stays; a game's name is the one players choose it by.");
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
      final JPanel row = new JPanel(new BorderLayout(6, 0));
      row.add(name, BorderLayout.CENTER);
      row.add(rename, BorderLayout.EAST);
      form.field("Game in " + mapGame.getXmlFileName(), row);
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
      JOptionPane.showMessageDialog(
          page,
          name.isEmpty() ? "A game needs a name." : "Another game has the name " + name + ".",
          "map.yml",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    try {
      MapFolder.renameGame(yml, mapGame.getXmlFileName(), name);
    } catch (final IOException e) {
      log.warn("Could not rename the game in {}", yml, e);
      JOptionPane.showMessageDialog(
          page, "Could not write map.yml:\n" + e.getMessage(), "map.yml", JOptionPane.WARNING_MESSAGE);
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
      add.addActionListener(
          e -> {
            try {
              Files.createDirectories(folder.resolve(name));
            } catch (final IOException ex) {
              log.warn("Could not make {}", folder.resolve(name), ex);
              return;
            }
            folders.refresh();
            reveal(folder.resolve(name));
          });
      form.row(
          slotRow(name + ": " + MapFolder.PICTURE_FOLDERS.get(name), true, add, Optional.empty()));
    }
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
    final JButton importHere = new JButton("Import picture here...");
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
      final JButton take = new JButton("Import...");
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
              picture + (present ? "" : "  · missing"),
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
    final JPanel row = new JPanel(new BorderLayout(8, 0));
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
      JOptionPane.showMessageDialog(
          page,
          "Could not import " + chosen.getFileName() + ":\n" + e.getMessage(),
          "Import picture",
          JOptionPane.WARNING_MESSAGE);
    }
    folders.refresh();
    reveal(folder);
    showFile(folder);
  }

  @FunctionalInterface
  private interface Import {
    void run() throws IOException;
  }

  private void showNothing() {
    fileSlot.removeAll();
    fileSlot.add(
        note(
            "Choose a file in the tree. The map's game, map.yml, point files and map.properties"
                + " are checked as you type."),
        BorderLayout.CENTER);
  }

  private static JLabel note(final String message) {
    final JLabel note = new JLabel(message, SwingConstants.CENTER);
    note.setForeground(Theme.getSecondaryTextColor());
    return note;
  }

  private void updateButtons() {
    save.setEnabled(editable && dirty);
    revert.setEnabled(editable && dirty);
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
    problemsTitle.setText("PROBLEMS · checking...");
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
          problemsTitle.setText(
              found.isEmpty()
                  ? "PROBLEMS · none, the game reads it"
                  : "PROBLEMS · " + found.size());
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
          log.info("Could not check {}", file, e);
          problemsTitle.setText("PROBLEMS · could not check");
        }
      }
    }.execute();
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
    }
  }

  /** Each change of the text marks it changed and checks it again after a pause. */
  private final class Typed implements DocumentListener {
    @Override
    public void insertUpdate(final DocumentEvent e) {
      changed();
    }

    @Override
    public void removeUpdate(final DocumentEvent e) {
      changed();
    }

    @Override
    public void changedUpdate(final DocumentEvent e) {
      changed();
    }

    private void changed() {
      if (filling) {
        return;
      }
      edits++;
      dirty = true;
      updateButtons();
      lintLater.restart();
    }
  }

  /**
   * The folders of the tree: the open map's first, then the maps added to copy from; directories
   * before files, by name, read when first opened.
   */
  private final class Folders implements TreeModel {
    private final Object root = "Folders";
    private final Map<Path, List<Path>> listed = new HashMap<>();
    private final List<TreeModelListener> listeners = new ArrayList<>();

    void refresh() {
      listed.clear();
      final TreeModelEvent changed = new TreeModelEvent(this, new Object[] {root});
      listeners.forEach(l -> l.treeStructureChanged(changed));
      if (getChildCount(root) > 0) {
        tree.expandRow(0);
      }
    }

    private List<Path> children(final Object node) {
      if (node == root) {
        final List<Path> roots = new ArrayList<>();
        mapFolder.get().ifPresent(roots::add);
        roots.addAll(otherFolders);
        return roots;
      }
      return listed.computeIfAbsent((Path) node, FilesEditor::list);
    }

    @Override
    public Object getRoot() {
      return root;
    }

    @Override
    public Object getChild(final Object parent, final int index) {
      return children(parent).get(index);
    }

    @Override
    public int getChildCount(final Object parent) {
      return children(parent).size();
    }

    @Override
    public boolean isLeaf(final Object node) {
      return node != root && !Files.isDirectory((Path) node);
    }

    @Override
    public void valueForPathChanged(final TreePath path, final Object newValue) {
      // the names are not changed in the tree
    }

    @Override
    public int getIndexOfChild(final Object parent, final Object child) {
      return children(parent).indexOf(child);
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

  /** A folder's entries: folders first, then files, by name; hidden ones and backups left out. */
  private static List<Path> list(final Path folder) {
    try (Stream<Path> entries = Files.list(folder)) {
      return entries
          .filter(p -> !p.getFileName().toString().startsWith("."))
          .filter(p -> !p.getFileName().toString().endsWith(".bak"))
          .sorted(
              Comparator.comparing((Path p) -> !Files.isDirectory(p))
                  .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
          .toList();
    } catch (final IOException e) {
      log.info("Could not list {}", folder, e);
      return List.of();
    }
  }

  /** Shows a file or folder by its name, a top folder by its whole path. */
  private final class FileRenderer extends DefaultTreeCellRenderer {
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
          value instanceof Path path
              ? (folders.children(folders.root).contains(path)
                  ? path.getFileName() + (otherFolders.contains(path) ? "  (to copy from)" : "")
                  : path.getFileName().toString())
              : String.valueOf(value);
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
      return super.getListCellRendererComponent(
          list,
          (lint.line() > 0 ? "Line " + lint.line() + ": " : "") + lint.message(),
          index,
          selected,
          focused);
    }
  }

  /** The line numbers left of the text, as editors for code show them. */
  static final class LineNumbers extends JComponent {
    @Serial private static final long serialVersionUID = 1L;

    private final JTextArea text;

    LineNumbers(final JTextArea text) {
      this.text = text;
      setForeground(Theme.getSecondaryTextColor());
      text.getDocument()
          .addDocumentListener(
              new DocumentListener() {
                @Override
                public void insertUpdate(final DocumentEvent e) {
                  changed();
                }

                @Override
                public void removeUpdate(final DocumentEvent e) {
                  changed();
                }

                @Override
                public void changedUpdate(final DocumentEvent e) {
                  changed();
                }
              });
    }

    private void changed() {
      revalidate();
      repaint();
    }

    @Override
    public Dimension getPreferredSize() {
      final FontMetrics metrics = getFontMetrics(text.getFont());
      final int digits = String.valueOf(Math.max(1, text.getLineCount())).length();
      return new Dimension(
          metrics.charWidth('0') * (digits + 2), metrics.getHeight() * text.getLineCount());
    }

    @Override
    protected void paintComponent(final Graphics g) {
      // the text's font and metrics, as the text places its lines with them
      final FontMetrics metrics = text.getFontMetrics(text.getFont());
      g.setFont(text.getFont());
      final int lineHeight = metrics.getHeight();
      final Rectangle shown = g.getClipBounds();
      final int first = Math.max(0, shown.y / lineHeight);
      final int last = Math.min(text.getLineCount(), (shown.y + shown.height) / lineHeight + 1);
      g.setColor(getForeground());
      for (int line = first; line < last; line++) {
        final String number = String.valueOf(line + 1);
        g.drawString(
            number,
            getWidth() - metrics.stringWidth(number) - metrics.charWidth('0'),
            (line + 1) * lineHeight - metrics.getDescent() + text.getInsets().top);
      }
    }
  }
}
