package eddies.mapeditor;

import java.awt.Component;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * Which TripleA Play starts: the player's own installation, so a map is tried in the version its
 * players use, found in the usual places or chosen once; else the TripleA the editor runs with.
 */
@UtilityClass
class PlayWith {
  private static final String KEY = "tripleaProgram";
  // kept as the choice, so "built in" stays even when an installation is found later
  static final String BUILT_IN = "built-in";

  /** Starts TripleA with the game on its setup screen; says which TripleA it is. */
  static String play(final Path gameXml) throws IOException {
    // TripleA reads an argument with '=' as name=value: such a game goes to the built-in one,
    // which gets it as a system property
    final Optional<Path> program =
        program().filter(p -> !gameXml.toAbsolutePath().toString().contains("="));
    if (program.isEmpty()) {
      MapFolder.playtest(gameXml);
      return "the TripleA the editor runs with";
    }
    new ProcessBuilder(command(program.get(), gameXml)).inheritIO().start();
    return program.get().toString();
  }

  /** The installed TripleA Play starts, empty for the built-in one. */
  static Optional<Path> program() {
    return program(preferences().get(KEY, ""), PlayWith::installed);
  }

  /**
   * The installed TripleA for the choice kept: built in, a program that is still there, or else
   * the one found.
   */
  static Optional<Path> program(final String chosen, final Supplier<Optional<Path>> installed) {
    if (BUILT_IN.equals(chosen)) {
      return Optional.empty();
    }
    return Optional.of(chosen)
        .filter(c -> !c.isEmpty())
        .map(Path::of)
        .filter(Files::exists)
        .or(installed);
  }

  /**
   * TripleA where its installer puts it, on Windows or macOS, or wherever Windows' list of
   * installed programs says it is.
   */
  static Optional<Path> installed() {
    return Stream.of(
            Optional.ofNullable(System.getenv("ProgramFiles")).map(p -> Path.of(p, "TripleA")),
            Optional.ofNullable(System.getenv("LOCALAPPDATA"))
                .map(p -> Path.of(p, "Programs", "TripleA")),
            Optional.of(Path.of(System.getProperty("user.home"), "TripleA")))
        .flatMap(Optional::stream)
        .map(folder -> folder.resolve("TripleA.exe"))
        .filter(Files::isRegularFile)
        .findFirst()
        .or(() -> Optional.of(Path.of("/Applications/TripleA.app")).filter(Files::isDirectory))
        .or(PlayWith::registered);
  }

  // the installer's entry in the uninstall list, as for the Start menu shortcut
  private static Optional<Path> registered() {
    if (!System.getProperty("os.name").startsWith("Windows")) {
      return Optional.empty();
    }
    return Stream.of("HKLM", "HKCU")
        .map(root -> root + "\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall")
        .map(PlayWith::regQuery)
        .map(PlayWith::fromUninstallList)
        .flatMap(Optional::stream)
        .filter(Files::isRegularFile)
        .findFirst();
  }

  private static String regQuery(final String key) {
    try {
      final Process reg = new ProcessBuilder("reg", "query", key, "/s").start();
      // reg writes in the system's code page, which inputReader reads
      try (BufferedReader output = reg.inputReader()) {
        return output.lines().collect(Collectors.joining("\n"));
      }
    } catch (final IOException e) {
      return "";
    }
  }

  /** TripleA.exe in the InstallLocation of the entry named TripleA, in the output of reg query. */
  static Optional<Path> fromUninstallList(final String regOutput) {
    // one block per program: its key, then its values as "name  REG_SZ  data"
    return Stream.of(regOutput.split("\\R(?=HKEY_)"))
        .filter(block -> block.matches("(?s).*\\s+DisplayName\\s+REG_SZ\\s+TripleA\\b.*"))
        .flatMap(block -> block.lines())
        .map(String::strip)
        .filter(line -> line.startsWith("InstallLocation"))
        .map(line -> line.split("\\s+REG_SZ\\s+", 2))
        .filter(parts -> parts.length == 2)
        .map(parts -> Path.of(parts[1].strip(), "TripleA.exe"))
        .findFirst();
  }

  /** TripleA reads a single argument as the game to open on its setup screen. */
  static List<String> command(final Path program, final Path gameXml) {
    final String game = gameXml.toAbsolutePath().toString();
    return program.toString().endsWith(".app")
        ? List.of("open", "-n", "-a", program.toString(), "--args", game)
        : List.of(program.toString(), game);
  }

  /** The TripleA Play starts, for a menu: its folder's or app's name; empty if built in. */
  static String describe() {
    return program().map(PlayWith::describe).orElse("");
  }

  static String describe(final Path program) {
    final String name = program.getFileName().toString();
    return name.endsWith(".app")
        ? name.substring(0, name.length() - ".app".length())
        : Optional.ofNullable(program.getParent())
            .map(Path::getFileName)
            .map(Path::toString)
            .orElse(name);
  }

  /** Asks which TripleA Play starts and keeps the answer. */
  static void choose(final Component parent) {
    final Optional<Path> now = program();
    final String own = "Your TripleA…";
    final Optional<String> answer =
        Dialogs.pick(
            parent,
            "Play with",
            "Play starts "
                + now.map(p -> "your TripleA: " + p).orElse("the TripleA the editor runs with.")
                + "\nYour own TripleA tries the map in the version its players use.",
            List.of(own, "The TripleA the editor runs with"),
            choice -> choice,
            "Choose");
    if (answer.filter(own::equals).isPresent()) {
      final Path start =
          now.map(Path::getParent).orElseGet(() -> Path.of(System.getProperty("user.home")));
      MapMakerWindow.chooseFileToOpen(parent, "Choose TripleA", start, programExtensions())
          .ifPresent(PlayWith::keep);
    } else if (answer.isPresent()) {
      preferences().put(KEY, BUILT_IN);
    }
  }

  // TripleA.exe on Windows, TripleA.app on a Mac, a launcher without extension elsewhere
  private static String[] programExtensions() {
    final String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    return os.startsWith("windows")
        ? new String[] {"exe"}
        : os.startsWith("mac") ? new String[] {"app"} : new String[0];
  }

  /** Keeps the TripleA Play starts. */
  static void keep(final Path program) {
    preferences().put(KEY, program.toString());
  }

  private static Preferences preferences() {
    return Preferences.userNodeForPackage(PlayWith.class);
  }
}
