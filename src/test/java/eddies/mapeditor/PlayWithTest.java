package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlayWithTest {
  private static final Path WINDOWS = Path.of("C:/Program Files/TripleA/TripleA.exe");
  private static final Path MAC = Path.of("/Applications/TripleA.app");

  @TempDir Path folder;

  private static Path givenGame() {
    return Path.of("maps", "island", "games", "island.xml").toAbsolutePath();
  }

  @DisplayName("TripleA gets the game as its only argument, on a Mac through open")
  @Test
  void startsTripleaWithTheGame() {
    final Path game = givenGame();

    final List<String> onWindows = PlayWith.command(WINDOWS, game);
    final List<String> onMac = PlayWith.command(MAC, game);

    assertThat(onWindows)
        .as("the program, then the game")
        .containsExactly(WINDOWS.toString(), game.toString());
    assertThat(onMac)
        .as("a new instance of the app with the game")
        .containsExactly("open", "-n", "-a", MAC.toString(), "--args", game.toString());
  }

  @DisplayName("Built in stays chosen; a chosen TripleA that is gone gives way to the one found")
  @Test
  void picksTheTripleaToPlay() throws Exception {
    final Path chosen = Files.createFile(folder.resolve("TripleA.exe"));
    final Path gone = folder.resolve("old/TripleA.exe");

    final Optional<Path> builtIn = PlayWith.program(PlayWith.BUILT_IN, () -> Optional.of(WINDOWS));
    final Optional<Path> stillThere = PlayWith.program(chosen.toString(), () -> Optional.of(WINDOWS));
    final Optional<Path> replaced = PlayWith.program(gone.toString(), () -> Optional.of(WINDOWS));
    final Optional<Path> nothing = PlayWith.program("", Optional::empty);

    assertThat(builtIn).as("built in, though one is installed").isEmpty();
    assertThat(stillThere).as("the chosen one").contains(chosen);
    assertThat(replaced).as("the chosen one is gone: the installed one").contains(WINDOWS);
    assertThat(nothing).as("none chosen, none found: built in").isEmpty();
  }

  @DisplayName("Windows' list of installed programs tells where TripleA is")
  @Test
  void findsTripleaInTheUninstallList() {
    final String regOutput =
        String.join(
            "\r\n",
            "HKEY_LOCAL_MACHINE\\SOFTWARE\\Uninstall\\Other",
            "    DisplayName    REG_SZ    Other Game",
            "    InstallLocation    REG_SZ    D:\\Other",
            "",
            "HKEY_LOCAL_MACHINE\\SOFTWARE\\Uninstall\\5251-3669-9623-1649",
            "    DisplayName    REG_SZ    TripleA 2026-11.16.15914",
            "    InstallLocation    REG_SZ    D:\\Games\\Triple A");

    final Optional<Path> found = PlayWith.fromUninstallList(regOutput);
    final Optional<Path> none = PlayWith.fromUninstallList(regOutput.replace("TripleA 2026", "X"));

    assertThat(found)
        .as("TripleA.exe in TripleA's install folder, spaces kept")
        .contains(Path.of("D:\\Games\\Triple A", "TripleA.exe"));
    assertThat(none).as("no entry named TripleA").isEmpty();
  }
}
