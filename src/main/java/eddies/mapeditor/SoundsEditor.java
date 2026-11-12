package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import games.strategy.triplea.ResourceLoader;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javazoom.jl.player.advanced.AdvancedPlayer;
import lombok.extern.slf4j.Slf4j;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapTexts.Entry;
import eddies.mapeditor.MapTexts.Kind;

/**
 * The sounds the game plays, as sounds.properties sets them: each sound with the mp3 files or
 * folders the game picks one from, the game's own where the map gives none. A chosen file is
 * copied into the map's sounds folder. Each change is a step to undo.
 */
@Slf4j
final class SoundsEditor implements Editor {
  private static final String DEFAULT_FOLDER = "Sound.Default.Folder";
  private static final String DEFAULT_ERA = "ww2";
  // the game's folders of sounds, one for each era, named in words
  private static final List<Folder> ERAS =
      List.of(
          new Folder(DEFAULT_ERA, "World War II (ww2)"),
          new Folder("classical", "Classical (classical)"),
          new Folder("preindustrial", "Pre-industrial (preindustrial)"),
          new Folder("future", "Future (future)"),
          new Folder("generic", "Generic (generic)"));
  private static final List<String> GROUPS =
      List.of("Battles", "Turns and phases", "Messages", "Other");
  private static final String ALL = "All nations";
  private static final String MP3 = ".mp3";
  private static final String GAMES_OWN = "The game's own";
  private static final int COMBO_WIDTH = 240;
  private static final int SWITCH_HEIGHT = 28;

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 8));
  private final SearchList list;
  private final Form inspector = new Form(360);
  private final JTextField files = new JTextField();
  private final JToggleButton all = newSwitchButton();
  private final JToggleButton own = newSwitchButton();
  private final JButton stop = Theme.newIconButton("■", "Stop", this::stopPlaying);
  // whether each path is found, asked once per change of the map, not on every rebuild
  private final Map<String, Boolean> found = new HashMap<>();
  private @Nullable String selected;
  private boolean editingHere;
  // the sound playing, stopped when another starts; set and cleared on the Swing thread
  private @Nullable AdvancedPlayer playing;

  /** A default folder: its value in sounds.properties, and how the list names it. */
  private record Folder(String value, String label) {
    @Override
    public String toString() {
      return label;
    }
  }

  SoundsEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList("sound", "sounds", this::shownKeys, this::title, this::detail, this::choose)
            .withEmpty(
                Panels.empty(
                    Glyph.SOUNDS,
                    "No sounds of your own",
                    "The game plays its own. Choose a sound under All to give it yours."));
    stop.setEnabled(false);
    files.addActionListener(e -> commit());
    files.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            commit();
          }
        });
    final ButtonGroup filter = new ButtonGroup();
    filter.add(all);
    filter.add(own);
    // the map's own sounds first, when it has any besides the folder
    (keys().stream().anyMatch(k -> !k.equals(DEFAULT_FOLDER) && isOwn(k)) ? own : all)
        .setSelected(true);
    all.addActionListener(e -> list.refresh());
    own.addActionListener(e -> list.refresh());
    final JPanel switches = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    switches.add(all);
    switches.add(own);
    final JPanel column = new JPanel(new BorderLayout(0, Theme.GAP_S));
    column.add(switches, BorderLayout.NORTH);
    column.add(list, BorderLayout.CENTER);
    showSwitchCounts();
    list.refresh();
    page.add(
        Panels.sideBySide(column, SwingComponents.newJScrollPane(inspector)), BorderLayout.CENTER);
    project.onChange(
        () -> {
          found.clear();
          showSwitchCounts();
          list.refresh();
          if (!editingHere) {
            showInspector();
          }
        });
    showInspector();
  }

  private static JToggleButton newSwitchButton() {
    final JToggleButton button = new JToggleButton();
    button.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    return button;
  }

  private void showSwitchCounts() {
    final List<String> keys = keys();
    all.setText("All " + keys.size());
    own.setText("Your own " + keys.stream().filter(this::isOwn).count());
    for (final AbstractButton button : List.of(all, own)) {
      button.setPreferredSize(null);
      button.setPreferredSize(new Dimension(button.getPreferredSize().width, SWITCH_HEIGHT));
    }
  }

  @Override
  public String title() {
    return "Sounds";
  }

  @Override
  public Glyph glyph() {
    return Glyph.SOUNDS;
  }

  @Override
  public String hint() {
    return "The sounds the game plays, eg for a land battle or a nation's turn. Choose mp3 files"
        + " for a sound, or leave it to the game's own. Saved in sounds.properties.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  /** The default folder, the game's sounds, and every other key the file gives. */
  private List<String> keys() {
    final Set<String> keys = new LinkedHashSet<>();
    keys.add(DEFAULT_FOLDER);
    MapTexts.soundKeys(project.game(), project.texts()).stream()
        .filter(k -> !isNationsVariant(k))
        .forEach(keys::add);
    project.texts().entries(Kind.SOUNDS).stream().map(Entry::key).forEach(keys::add);
    return List.copyOf(keys);
  }

  /** The default folder first, the rest by group, under "Your own" only the map's own. */
  List<String> shownKeys() {
    return keys().stream()
        .filter(k -> all.isSelected() || isOwn(k))
        .sorted(
            Comparator.comparing(
                (String k) -> k.equals(DEFAULT_FOLDER) ? -1 : GROUPS.indexOf(group(k))))
        .toList();
  }

  private boolean isOwn(final String key) {
    return project.texts().value(Kind.SOUNDS, key).isPresent();
  }

  /** Where a sound goes in the list: Battles, Turns and phases, Messages or Other. */
  static String group(final String key) {
    if (key.startsWith("battle_") || key.startsWith("bombing_")) {
      return "Battles";
    }
    if (key.startsWith("phase_") || key.startsWith("required_your_turn_series")) {
      return "Turns and phases";
    }
    if (Stream.of("notification_", "victory_", "defeat_").anyMatch(key::startsWith)) {
      return "Messages";
    }
    return "Other";
  }

  private boolean isNationsVariant(final String key) {
    return MapXml.nations(project.game()).stream().anyMatch(n -> key.endsWith("_" + n));
  }

  String title(final String key) {
    if (key.equals(DEFAULT_FOLDER)) {
      return "Default folder: " + defaultFolder();
    }
    return MapTexts.explain(Kind.SOUNDS, key, project.game());
  }

  private String defaultFolder() {
    return project.texts().value(Kind.SOUNDS, DEFAULT_FOLDER).map(String::strip).orElse(DEFAULT_ERA);
  }

  String detail(final String key) {
    if (key.equals(DEFAULT_FOLDER)) {
      return isOwn(key) ? "Set by this map" : GAMES_OWN;
    }
    final int count = paths(key).size();
    return group(key)
        + " · "
        + (isOwn(key) ? "Your own · " + count + (count == 1 ? " file" : " files") : GAMES_OWN);
  }

  private void choose(final String key) {
    commit();
    selected = key;
    showInspector();
  }

  private void showInspector() {
    inspector.clear();
    if (project.texts().unreadable().contains(Kind.SOUNDS)) {
      inspector.note("sounds.properties does not read: fix it in Files.").end();
      return;
    }
    if (selected == null) {
      // the first, rather than an empty side
      selected = shownKeys().stream().findFirst().orElse(DEFAULT_FOLDER);
      list.choose(selected);
    }
    final String key = selected;
    inspector
        .title(MapTexts.explain(Kind.SOUNDS, key, project.game()))
        .subtitle(key + " · sounds.properties");
    if (key.equals(DEFAULT_FOLDER)) {
      showDefaultFolder();
      return;
    }
    final String base = baseOf(key);
    final List<String> nations = MapXml.nations(project.game());
    // the sounds of messages are played to the nations of their trigger already
    if (!nations.isEmpty() && !group(base).equals("Messages")) {
      final JComboBox<String> forNation = new JComboBox<>();
      forNation.addItem(ALL);
      nations.forEach(forNation::addItem);
      forNation.setSelectedItem(base.equals(key) ? ALL : key.substring(base.length() + 1));
      forNation.addActionListener(
          e -> {
            final String nation = (String) forNation.getSelectedItem();
            choose(ALL.equals(nation) ? base : base + "_" + nation);
          });
      inspector
          .field("For", fitting(forNation))
          .note("A nation's own sound, played for its turns and its battles.");
    }
    showFiles(key);
    inspector.end();
  }

  private void showFiles(final String key) {
    inspector.section("Plays");
    final String name = MapTexts.explain(Kind.SOUNDS, key, project.game());
    final List<String> paths = paths(key);
    final JPanel rows = new JPanel();
    rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
    rows.setOpaque(false);
    if (paths.isEmpty()) {
      rows.add(
          newFileRow(
              "The game's own sound",
              "Play the game's own sound",
              () -> play(key, name, gamesOwnPaths(key)),
              Optional.empty(),
              true));
    }
    for (final String path : paths) {
      rows.add(
          newFileRow(
              path,
              "Play " + path,
              () -> play(key, name, List.of(path)),
              Optional.of(() -> removePath(key, path)),
              isFound(path)));
    }
    inspector.row(rows);
    inspector.note("The game picks one of them each time.");

    final JButton addFile = new JButton("Add file…");
    Theme.setSecondaryButtonStyle(addFile);
    addFile.addActionListener(e -> addFile(key));
    final JButton addFolder = new JButton("Add folder…");
    Theme.setSecondaryButtonStyle(addFolder);
    addFolder.setToolTipText("All mp3 files of a folder");
    addFolder.addActionListener(e -> addFolder(key));
    final boolean saved = project.getFolder().isPresent();
    addFile.setEnabled(saved);
    addFolder.setEnabled(saved);
    final JPanel adds = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    adds.setOpaque(false);
    adds.add(addFile);
    adds.add(addFolder);
    final JPanel buttons = new JPanel(new BorderLayout());
    buttons.setOpaque(false);
    buttons.add(adds, BorderLayout.WEST);
    buttons.add(stop, BorderLayout.EAST);
    inspector.row(buttons);
    if (!saved) {
      inspector.note("Save the map first: files are copied into its folder.");
    }

    files.setText(project.texts().value(Kind.SOUNDS, key).orElse(""));
    inspector.more("sound-text", 1, f -> f.field("Files as text", files));

    if (!paths.isEmpty()) {
      final JButton gamesOwn = new JButton("Use the game's own sound");
      Theme.setTertiaryButtonStyle(gamesOwn);
      gamesOwn.addActionListener(e -> useGamesOwn(key, name, paths.size()));
      final JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      bottom.setOpaque(false);
      bottom.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_XL - 6, 0, 0, 0));
      bottom.add(gamesOwn);
      inspector.row(bottom);
    }
  }

  /** ▶, the path cut in the middle to fit, "not found" when the game would not find it, ×. */
  private JPanel newFileRow(
      final String text,
      final String playTip,
      final Runnable onPlay,
      final Optional<Runnable> onRemove,
      final boolean isFound) {
    final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    row.setOpaque(false);
    row.add(Theme.newIconButton("▶", playTip, onPlay), BorderLayout.WEST);
    final JLabel path = new JLabel(isFound ? text : "! " + text);
    path.setToolTipText(text);
    if (onRemove.isPresent()) {
      path.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced");
    }
    // as wide as the row gives, not the whole path: a long one is cut, not widening the form
    path.setPreferredSize(new Dimension(0, path.getPreferredSize().height));
    path.addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(final ComponentEvent e) {
            final String whole = isFound ? text : "! " + text;
            path.setText(
                SearchList.cutMiddle(whole, path.getFontMetrics(path.getFont()), path.getWidth()));
          }
        });
    row.add(path, BorderLayout.CENTER);
    final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
    right.setOpaque(false);
    if (!isFound) {
      path.setForeground(Theme.getWarningColor());
      final JLabel notFound = new JLabel("not found");
      notFound.setForeground(Theme.getWarningColor());
      right.add(notFound);
    }
    onRemove.ifPresent(
        remove -> right.add(Theme.newIconButton("×", "Remove from this sound", remove)));
    row.add(right, BorderLayout.EAST);
    row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    return row;
  }

  private boolean isFound(final String path) {
    final Optional<Path> folder = project.getFolder();
    return found.computeIfAbsent(
        path,
        p -> {
          try (ResourceLoader loader = newLoader(folder)) {
            return findClip(loader, List.of(p)).isPresent();
          }
        });
  }

  private void removePath(final String key, final String path) {
    final String rest = without(project.texts().value(Kind.SOUNDS, key).orElse(""), path);
    if (rest.isEmpty()) {
      editingHere = true;
      try {
        project.removeText(Kind.SOUNDS, key);
      } finally {
        editingHere = false;
      }
      showInspector();
    } else {
      setSound(key, rest);
    }
  }

  /** The sound's files without that path, every other entry kept as written, in its order. */
  static String without(final String value, final String path) {
    return String.join(
        ";",
        Arrays.stream(value.split(";"))
            .map(String::strip)
            .filter(p -> !p.isEmpty() && !p.equals(path))
            .toList());
  }

  private void useGamesOwn(final String key, final String name, final int count) {
    if (count >= 2
        && !Dialogs.confirm(
            page,
            "Use the game's own sound",
            "Remove the " + count + " files of " + name + "?\nThe game then plays its own.",
            "Use the game's own")) {
      return;
    }
    project.removeText(Kind.SOUNDS, key);
    Dialogs.toast(page, "✓ " + name + " uses the game's own sound again", "Undo", project::undo);
  }

  /** The game's key without a nation's name at its end, eg battle_land for battle_land_Germans. */
  private String baseOf(final String key) {
    return MapXml.nations(project.game()).stream()
        .filter(n -> key.endsWith("_" + n))
        .map(n -> key.substring(0, key.length() - n.length() - 1))
        .findFirst()
        .orElse(key);
  }

  private void showDefaultFolder() {
    final List<Folder> folders = new ArrayList<>(ERAS);
    mapSoundFolders().stream()
        .filter(f -> ERAS.stream().noneMatch(era -> era.value().equals(f)))
        .forEach(f -> folders.add(new Folder(f, "Map folder: " + f)));
    final String value = defaultFolder();
    if (folders.stream().noneMatch(f -> f.value().equals(value))) {
      // kept as the file has it, eg a folder not there yet
      folders.add(new Folder(value, value));
    }
    final JComboBox<Folder> folder = new JComboBox<>(folders.toArray(Folder[]::new));
    folder.setSelectedItem(
        folders.stream().filter(f -> f.value().equals(value)).findFirst().orElseThrow());
    folder.setPreferredSize(new Dimension(COMBO_WIDTH, folder.getPreferredSize().height));
    folder.addActionListener(
        e -> setSound(DEFAULT_FOLDER, ((Folder) folder.getSelectedItem()).value()));
    inspector
        .field("Folder", fitting(folder))
        .note("Sounds you don't set come from this folder.")
        .end();
  }

  /** The folders in the map's sounds folder. */
  private List<String> mapSoundFolders() {
    final Optional<Path> sounds = soundsFolder().filter(Files::isDirectory);
    if (sounds.isEmpty()) {
      return List.of();
    }
    try (Stream<Path> folders = Files.list(sounds.get())) {
      return folders.filter(Files::isDirectory).map(f -> f.getFileName().toString()).sorted().toList();
    } catch (final IOException e) {
      log.info("Could not list {}", sounds.get(), e);
      return List.of();
    }
  }

  private Optional<Path> soundsFolder() {
    return project.getFolder().map(f -> f.resolve("sounds"));
  }

  private static JPanel fitting(final JComponent component) {
    if (component.getPreferredSize().width < COMBO_WIDTH) {
      component.setPreferredSize(new Dimension(COMBO_WIDTH, component.getPreferredSize().height));
    }
    final JPanel fitting = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    fitting.setOpaque(false);
    fitting.add(component);
    return fitting;
  }

  /** Copies an mp3 file into the map's sounds/<key> folder and adds it to the sound's files. */
  private void addFile(final String key) {
    final Path sounds = soundsFolder().orElseThrow();
    MapMakerWindow.chooseFileToOpen(
            page, "Choose a sound", sounds.getParent(), "mp3")
        .ifPresent(
            file -> {
              // one of the map's own as it is, unless the game would not find it
              final boolean asItIs =
                  file.startsWith(sounds) && file.getFileName().toString().endsWith(MP3);
              final String path = asItIs ? pathIn(sounds, file) : key + "/" + mp3Name(file);
              try {
                if (!asItIs) {
                  MapFolder.copyInto(file, sounds.resolve(path));
                }
                setSound(key, withPath(key, path));
              } catch (final IOException e) {
                showNotTaken(file, e);
              }
            });
  }

  /**
   * Adds a folder to the sound's files: one in the map's sounds folder as it is, another's mp3
   * files copied into the map's sounds/<key> folder, which then stands for its files.
   */
  private void addFolder(final String key) {
    final Path sounds = soundsFolder().orElseThrow();
    MapMakerWindow.chooseFolder(page, "Choose a folder of sounds", sounds.getParent())
        .ifPresent(
            folder -> {
              try {
                if (folder.startsWith(sounds)) {
                  setSound(key, withPath(key, pathIn(sounds, folder)));
                  return;
                }
                try (Stream<Path> mp3s = Files.list(folder)) {
                  for (final Path mp3 :
                      mp3s.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(MP3))
                          .toList()) {
                    MapFolder.copyInto(mp3, sounds.resolve(key).resolve(mp3Name(mp3)));
                  }
                }
                // the folder has the files chosen one by one before
                final List<String> kept =
                    paths(key).stream().filter(p -> !p.startsWith(key + "/")).toList();
                setSound(
                    key,
                    String.join(";", Stream.concat(kept.stream(), Stream.of(key)).distinct().toList()));
              } catch (final IOException e) {
                showNotTaken(folder, e);
              }
            });
  }

  /** The file's name ending in ".mp3", the one ending the game plays, eg for "Horn.MP3". */
  static String mp3Name(final Path file) {
    final String name = file.getFileName().toString();
    return name.substring(0, name.length() - MP3.length()) + MP3;
  }

  private static String pathIn(final Path sounds, final Path file) {
    return sounds.relativize(file).toString().replace('\\', '/');
  }

  private List<String> paths(final String key) {
    return project.texts().value(Kind.SOUNDS, key).stream()
        .flatMap(v -> Arrays.stream(v.split(";")))
        .map(String::strip)
        .filter(p -> !p.isEmpty())
        .toList();
  }

  private String withPath(final String key, final String path) {
    final List<String> paths = new ArrayList<>(paths(key));
    if (!paths.contains(path)) {
      paths.add(path);
    }
    return String.join(";", paths);
  }

  private void setSound(final String key, final String value) {
    editingHere = true;
    try {
      project.setText(Kind.SOUNDS, key, value);
    } finally {
      editingHere = false;
    }
    showInspector();
  }

  private void showNotTaken(final Path file, final IOException e) {
    log.warn("Could not copy {}", file, e);
    Dialogs.toast(page, "! Could not copy " + file.getFileName() + ": " + e.getMessage());
  }

  @Override
  public void commit() {
    if (selected == null || selected.equals(DEFAULT_FOLDER)) {
      return;
    }
    final String key = selected;
    final String typed = files.getText().strip();
    if (typed.equals(project.texts().value(Kind.SOUNDS, key).orElse(""))) {
      return;
    }
    editingHere = true;
    try {
      if (typed.isEmpty()) {
        project.removeText(Kind.SOUNDS, key);
      } else {
        project.setText(Kind.SOUNDS, key, typed);
      }
    } finally {
      editingHere = false;
    }
  }

  /** Where the game looks for a sound the map gives no files for. */
  private List<String> gamesOwnPaths(final String key) {
    return List.of(defaultFolder() + "/" + key, "generic/" + key);
  }

  private static ResourceLoader newLoader(final Optional<Path> folder) {
    return new ResourceLoader(folder.map(List::of).orElse(List.of()));
  }

  /**
   * The first mp3 of the paths as the game finds them: in the map's sounds folder, else the
   * game's; a folder stands for its files.
   */
  static Optional<URL> findClip(final ResourceLoader loader, final List<String> paths) {
    return paths.stream()
        .flatMap(p -> loader.listResources("sounds/" + p).stream())
        // as the game: it plays only files ending in exactly ".mp3"
        .filter(u -> u.toString().endsWith(MP3))
        .findFirst();
  }

  /** Plays the first file found of the paths; a toast when there is none. */
  private void play(final String key, final String name, final List<String> paths) {
    commit();
    final Optional<Path> folder = project.getFolder();
    final String where =
        paths(key).isEmpty() ? " in " + defaultFolder() + "/ or generic/" : " in its files";
    stopPlaying();
    final Thread player =
        new Thread(
            () -> {
              try (ResourceLoader loader = newLoader(folder)) {
                final Optional<URL> clip = findClip(loader, paths);
                if (clip.isEmpty()) {
                  SwingUtilities.invokeLater(
                      () -> Dialogs.toast(page, "! No mp3 found for " + name + where));
                  return;
                }
                try (InputStream in = clip.get().openStream()) {
                  final AdvancedPlayer advancedPlayer = new AdvancedPlayer(in);
                  SwingUtilities.invokeLater(() -> setPlaying(advancedPlayer));
                  advancedPlayer.play();
                  SwingUtilities.invokeLater(() -> stopped(advancedPlayer));
                }
              } catch (final Exception e) {
                // eg no sound device
                log.info("Could not play {}", key, e);
              }
            },
            "Sound " + key);
    player.setDaemon(true);
    player.start();
  }

  private void setPlaying(final AdvancedPlayer player) {
    stopPlaying();
    playing = player;
    stop.setEnabled(true);
  }

  private void stopped(final AdvancedPlayer player) {
    if (playing == player) {
      playing = null;
      stop.setEnabled(false);
    }
  }

  private void stopPlaying() {
    final AdvancedPlayer before = playing;
    if (before != null) {
      before.close();
      stopped(before);
    }
  }
}
