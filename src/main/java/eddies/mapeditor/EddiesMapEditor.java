package eddies.mapeditor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

/**
 * Starts Eddie's Map Editor with the player's installed TripleA: the editor does not ship TripleA,
 * so it starts itself again with TripleA's jar on the class path. Only JDK classes here, as
 * TripleA is not there yet.
 */
public final class EddiesMapEditor {
  // set on the second start, so a jar without TripleA in it does not start the editor forever
  private static final String WITH_TRIPLEA = "eddies.mapeditor.withTriplea";
  // the jar of the TripleA the player chose, for a folder that has no program Play could start
  private static final String JAR = "tripleaJar";
  static final String MORE_MEMORY = "-XX:MaxRAMPercentage=50";

  private EddiesMapEditor() {}

  public static void main(final String[] args) throws IOException, InterruptedException {
    // the editor's texts are English, so its dialogs' buttons and numbers are English too
    Locale.setDefault(Locale.ENGLISH);
    JComponent.setDefaultLocale(Locale.ENGLISH);
    if (hasTriplea()) {
      MapMakerWindow.start();
      return;
    }
    if (System.getProperty(WITH_TRIPLEA) != null) {
      tell("TripleA did not start. Install it again from triplea-game.org.");
      System.exit(1);
    }
    final List<Path> found =
        Stream.concat(
                Stream.of(PlayWith.program(), PlayWith.installed())
                    .flatMap(Optional::stream)
                    .map(EddiesMapEditor::jarOf)
                    .flatMap(Optional::stream),
                keptJar().stream())
            .toList();
    final Optional<Path> jar =
        found.stream()
            .filter(EddiesMapEditor::isNewEnough)
            .findFirst()
            .or(
                () -> {
                  // a newer TripleA may be where it was not looked for
                  found.stream().findFirst().ifPresent(old -> tell(tooOld(old)));
                  return askForTriplea();
                });
    if (jar.isEmpty()) {
      tell("Eddie's Map Editor needs TripleA. Install it from triplea-game.org and start again.");
      System.exit(1);
    }
    // this JVM waits, so the program ends with the editor; the editor needs no JVM options of it
    final Process editor = new ProcessBuilder(command(jar.get(), args)).inheritIO().start();
    System.exit(editor.waitFor());
  }

  private static boolean hasTriplea() {
    try {
      Class.forName(
          "org.triplea.game.client.HeadedGameRunner", false, EddiesMapEditor.class.getClassLoader());
      return true;
    } catch (final ClassNotFoundException e) {
      return false;
    }
  }

  private static void tell(final String message) {
    JOptionPane.showMessageDialog(
        null, message, "Eddie's Map Editor", JOptionPane.ERROR_MESSAGE);
  }

  /** TripleA's one jar: in the bin folder beside TripleA.exe, or inside TripleA.app or a folder. */
  static Optional<Path> jarOf(final Path program) {
    final Path folder = Files.isDirectory(program) ? program : program.getParent();
    if (folder == null || !Files.isDirectory(folder)) {
      return Optional.empty();
    }
    try (Stream<Path> found =
        Files.find(
            folder,
            5,
            (path, attributes) -> path.getFileName().toString().matches("game-headed-.*\\.jar"))) {
      return found.findFirst();
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  /**
   * Whether the TripleA has what the editor uses: FlatLaf, its look, came with the releases of
   * 2026-06; older ones (Substance) stop the editor at its start.
   */
  static boolean isNewEnough(final Path jar) {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      return zip.getEntry("com/formdev/flatlaf/FlatLaf.class") != null;
    } catch (final IOException e) {
      return false;
    }
  }

  private static String tooOld(final Path jar) {
    return "The TripleA found ("
        + jar.getFileName()
        + ") is too old for Eddie's Map Editor.\n"
        + "Install the newest TripleA from triplea-game.org, or show where a newer one is.";
  }

  private static Optional<Path> keptJar() {
    return Optional.of(preferences().get(JAR, ""))
        .filter(jar -> !jar.isEmpty())
        .map(Path::of)
        .filter(Files::isRegularFile);
  }

  /** None found: the player shows where TripleA is; a program is kept for Play too. */
  private static Optional<Path> askForTriplea() {
    final JFileChooser chooser = new JFileChooser(System.getProperty("user.home"));
    chooser.setDialogTitle("Where is TripleA? (TripleA.exe, TripleA.app or its folder)");
    chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
    while (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
      final Path chosen = chooser.getSelectedFile().toPath();
      final Optional<Path> jar = jarOf(chosen);
      if (jar.isPresent() && !isNewEnough(jar.get())) {
        tell(tooOld(jar.get()));
      } else if (jar.isPresent()) {
        preferences().put(JAR, jar.get().toString());
        if (isProgram(chosen)) {
          PlayWith.keep(chosen);
        }
        return jar;
      } else {
        tell("No TripleA there. Choose TripleA.exe, TripleA.app or the folder TripleA is in.");
      }
    }
    return Optional.empty();
  }

  /** TripleA.exe, the Linux launcher or TripleA.app: not its folder, jar or uninstall.exe. */
  static boolean isProgram(final Path chosen) {
    // a drive root has no name
    final String name = Optional.ofNullable(chosen.getFileName()).map(Path::toString).orElse("");
    return name.endsWith(".app") || (Files.isRegularFile(chosen) && name.matches("TripleA(\\.exe)?"));
  }

  private static Preferences preferences() {
    return Preferences.userNodeForPackage(EddiesMapEditor.class);
  }

  /**
   * The editor's version, eg 1.2.0: the Implementation-Version the release build writes into the
   * jar's manifest (-PappVersion); "dev" for a run without one, eg from the source.
   */
  static String version() {
    final String version = EddiesMapEditor.class.getPackage().getImplementationVersion();
    return version == null ? "dev" : version;
  }

  /** The editor again, with TripleA's jar after its own class path. */
  static List<String> command(final Path jar, final String[] args) {
    final Path bin = Path.of(System.getProperty("java.home"), "bin");
    // javaw has no console window on Windows
    final Path java =
        Files.isRegularFile(bin.resolve("javaw.exe")) ? bin.resolve("javaw.exe") : bin.resolve("java");
    final List<String> command = new ArrayList<>();
    command.add(java.toString());
    command.add("--enable-native-access=ALL-UNNAMED");
    // up to half the computer's memory, not Java's quarter: a big map's picture is held whole
    // to draw borders into (MapFiles.tilesPictureToDrawInto)
    command.add(MORE_MEMORY);
    command.add("-D" + WITH_TRIPLEA + "=true");
    command.add("-classpath");
    command.add(System.getProperty("java.class.path") + File.pathSeparator + jar);
    command.add(EddiesMapEditor.class.getName());
    command.addAll(List.of(args));
    return List.copyOf(command);
  }
}
