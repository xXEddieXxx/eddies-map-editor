package eddies.mapeditor;

import games.strategy.triplea.ResourceLoader;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
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
  // the game's folders of sounds, one for each era
  private static final List<String> ERAS =
      List.of("ww2", "classical", "preindustrial", "future", "generic");
  private static final String ALL = "All nations";
  private static final String MP3 = ".mp3";

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 8));
  private final SearchList list;
  private final Form inspector = new Form(360);
  private final JTextField files = new JTextField();
  private @Nullable String selected;
  private boolean editingHere;
  // the sound playing, stopped when another starts
  private volatile @Nullable AdvancedPlayer playing;

  SoundsEditor(final MapProject project) {
    this.project = project;
    list = new SearchList("Search sound", this::keys, this::explain, this::choose);
    files.addActionListener(e -> commit());
    files.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            commit();
          }
        });
    page.add(
        Panels.sideBySide(list, SwingComponents.newJScrollPane(inspector)), BorderLayout.CENTER);
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

  private boolean isNationsVariant(final String key) {
    return MapXml.nations(project.game()).stream().anyMatch(n -> key.endsWith("_" + n));
  }

  private String explain(final String key) {
    final String name = MapTexts.explain(Kind.SOUNDS, key, project.game());
    return name + " · " + project.texts().value(Kind.SOUNDS, key).orElse("the game's own");
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
      // the first, the default folder, rather than an empty side
      selected = keys().getFirst();
      list.choose(selected);
    }
    final String key = selected;
    inspector.title(MapTexts.explain(Kind.SOUNDS, key, project.game())).note(key);
    if (key.equals(DEFAULT_FOLDER)) {
      showDefaultFolder();
      return;
    }
    final String base = baseOf(key);
    final List<String> nations = MapXml.nations(project.game());
    // the sounds of messages are played to the nations of their trigger already
    if (!nations.isEmpty() && Stream.of("notification_", "victory_", "defeat_").noneMatch(base::startsWith)) {
      final JComboBox<String> forNation = new JComboBox<>();
      forNation.addItem(ALL);
      nations.forEach(forNation::addItem);
      forNation.setSelectedItem(base.equals(key) ? ALL : key.substring(base.length() + 1));
      forNation.addActionListener(
          e -> {
            final String nation = (String) forNation.getSelectedItem();
            choose(ALL.equals(nation) ? base : base + "_" + nation);
          });
      inspector.field("For", "A nation's own sound, played for its turns and its battles.", fitting(forNation));
    }
    files.setText(project.texts().value(Kind.SOUNDS, key).orElse(""));
    inspector.field(
        "Files",
        "Paths below a sounds folder, separated by ;. The game picks one of them each time.",
        files);
    final JButton addFile = new JButton("Add file...");
    addFile.addActionListener(e -> addFile(key));
    final JButton addFolder = new JButton("Add folder...");
    addFolder.setToolTipText("All mp3 files of a folder");
    addFolder.addActionListener(e -> addFolder(key));
    final JButton play = new JButton("Play");
    play.addActionListener(e -> play(key));
    final JButton gamesOwn = new JButton("Game's own");
    gamesOwn.setToolTipText("Takes the entry out of sounds.properties");
    gamesOwn.setEnabled(project.texts().value(Kind.SOUNDS, key).isPresent());
    gamesOwn.addActionListener(e -> project.removeText(Kind.SOUNDS, key));
    final boolean saved = project.getFolder().isPresent();
    addFile.setEnabled(saved);
    addFolder.setEnabled(saved);
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(addFile);
    buttons.add(addFolder);
    buttons.add(play);
    buttons.add(gamesOwn);
    inspector.row(buttons);
    if (!saved) {
      inspector.note("Save the map first: chosen files are copied into its folder.");
    }
    inspector.end();
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
    final JComboBox<String> folder = new JComboBox<>();
    ERAS.forEach(folder::addItem);
    mapSoundFolders().stream().filter(f -> !ERAS.contains(f)).forEach(folder::addItem);
    folder.setEditable(true);
    folder.setSelectedItem(project.texts().value(Kind.SOUNDS, DEFAULT_FOLDER).orElse("ww2"));
    folder.addActionListener(
        e -> {
          final String chosen = String.valueOf(folder.getSelectedItem()).strip();
          if (!chosen.isEmpty()) {
            setSound(DEFAULT_FOLDER, chosen);
          }
        });
    inspector
        .field(
            "Folder",
            "A sound the map gives no files for comes from this folder of the game's, or of the"
                + " map's sounds folder: the sound's own folder in it, eg ww2/battle_land.",
            fitting(folder))
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
    final JPanel fitting = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
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
    final List<String> all = new ArrayList<>(paths(key));
    if (!all.contains(path)) {
      all.add(path);
    }
    return String.join(";", all);
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
    log.warn("Could not take {}", file, e);
    JOptionPane.showMessageDialog(
        page,
        "Could not take " + file.getFileName() + ":\n" + e.getMessage(),
        "Sounds",
        JOptionPane.WARNING_MESSAGE);
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

  /**
   * Plays one of the sound's files as the game finds them: in the map's sounds folder, else the
   * game's; without files the default folder's, else the game's generic one.
   */
  private void play(final String key) {
    commit();
    final List<String> paths =
        paths(key).isEmpty()
            ? List.of(
                project.texts().value(Kind.SOUNDS, DEFAULT_FOLDER).orElse("ww2").strip()
                    + "/"
                    + key,
                "generic/" + key)
            : paths(key);
    final Optional<Path> folder = project.getFolder();
    stopPlaying();
    final Thread player =
        new Thread(
            () -> {
              try (ResourceLoader loader =
                  new ResourceLoader(folder.map(List::of).orElse(List.of()))) {
                final Optional<URL> clip =
                    paths.stream()
                        .flatMap(p -> loader.listResources("sounds/" + p).stream())
                        // as the game: it plays only files ending in exactly ".mp3"
                        .filter(u -> u.toString().endsWith(MP3))
                        .findFirst();
                if (clip.isEmpty()) {
                  log.info("No sound found for {}", key);
                  return;
                }
                try (InputStream in = clip.get().openStream()) {
                  final AdvancedPlayer advancedPlayer = new AdvancedPlayer(in);
                  playing = advancedPlayer;
                  advancedPlayer.play();
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

  private void stopPlaying() {
    final AdvancedPlayer before = playing;
    if (before != null) {
      before.close();
    }
  }
}
