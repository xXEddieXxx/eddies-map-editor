package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The update from within the editor: which file it takes, how it checks it, what it runs. */
final class UpdaterTest {
  private static final String SHA = "ab".repeat(32);
  private static final String URL =
      "https://github.com/xXEddieXxx/eddies-map-editor/releases/download/v0.2.4/"
          + "eddies-map-editor-windows.msi";

  @TempDir Path folder;

  @DisplayName("Only the installer of the editor's own releases is taken")
  @Test
  void takesOnlyItsOwnReleases() {
    assertThat(new Updater.Installer(URL, 10, Optional.empty()).isOwn()).isTrue();
    assertThat(
            new Updater.Installer(
                    "https://github.com/someone/else/releases/download/v9/"
                        + "eddies-map-editor-windows.msi",
                    10,
                    Optional.empty())
                .isOwn())
        .as("another repository")
        .isFalse();
    assertThat(
            new Updater.Installer(
                    URL.replace("eddies-map-editor-windows.msi", "other.msi"), 10, Optional.empty())
                .isOwn())
        .as("another file")
        .isFalse();
  }

  @DisplayName("A download is refused when its size or its SHA-256 is not the release's")
  @Test
  void checksTheDownload() {
    final Updater.Installer told = new Updater.Installer(URL, 10, Optional.of(SHA));
    final Updater.Installer untold = new Updater.Installer(URL, 10, Optional.empty());

    assertThat(Updater.problem(told, 10, SHA.toUpperCase())).as("the release's file").isEmpty();
    assertThat(Updater.problem(told, 9, SHA)).as("cut short").isPresent();
    assertThat(Updater.problem(told, 10, "cd".repeat(32))).as("another file").isPresent();
    assertThat(Updater.problem(untold, 10, "cd".repeat(32)))
        .as("a release GitHub told no digest of: the size alone")
        .isEmpty();
    assertThat(Updater.problem(untold, 11, SHA)).isPresent();
  }

  @DisplayName("Installed is told by the program's exact name among Windows' installed products")
  @Test
  void tellsInstalledByItsName() {
    final String listed =
        """

        HKEY_CURRENT_USER\\Software\\Microsoft\\Installer\\Products\\A460AE4F45129533AB526D21524205E7
            ProductName    REG_SZ    Eddies Map Editor

        Suchvorgang abgeschlossen: 1 übereinstimmende Zeichenfolge(n) gefunden.
        """;

    assertThat(Updater.isListed(listed)).isTrue();
    assertThat(Updater.isListed(listed.replace("Eddies Map Editor", "Eddies Map Editor 2")))
        .as("another program")
        .isFalse();
    assertThat(Updater.isListed("Suchvorgang abgeschlossen: 0")).as("not installed").isFalse();
  }

  @DisplayName("The folder Windows' list of programs names is this one, whatever code page reg"
      + " writes its letters in")
  @Test
  void tellsItsFolderAmongThePrograms() {
    final String found =
        """

        HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{97D9}
            DisplayName    REG_SZ    Eddies Map Editor

        """;
    final String entry =
        """

        HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{97D9}
            InstallLocation    REG_SZ    D:\\Spiele\\J\u0094rg\\Map Editor\\

        """;

    assertThat(Updater.keys(found))
        .containsExactly(
            "HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{97D9}");
    assertThat(Updater.installLocation(entry)).contains("D:\\Spiele\\J\u0094rg\\Map Editor\\");
    assertThat(Updater.installLocation(found)).as("an entry without it").isEmpty();
    assertThat(Updater.isSamePlace("D:\\Spiele\\J\u0094rg\\Map Editor\\", "d:\\Spiele\\J\u00f6rg\\Map Editor"))
        .as("the same folder, its \u00f6 in the console's code page, with a last backslash")
        .isTrue();
    assertThat(Updater.isSamePlace("D:\\Spiele\\Map Editor\\", "C:\\Downloads\\Map Editor"))
        .as("an unzipped copy elsewhere")
        .isFalse();
  }

  @DisplayName("The script that installs holds no path: they reach it as variables, whatever"
      + " letters and spaces a user's folder has")
  @Test
  void scriptTakesPathsAsVariables() {
    assertThat(Updater.SCRIPT)
        .contains("%SYS%msiexec.exe /i \"%UPDATE_MSI%\" /passive /norestart")
        .as("into the folder the editor is in, also one the map maker chose")
        .contains("INSTALLDIR=\"%EDITOR_DIR%\"")
        .contains("start \"\" \"%EDITOR_EXE%\"")
        .as("it waits for the editor and for the program that started it")
        .contains("%EDITOR_PID%", "%STARTER_PID%")
        .as("plain ASCII, read the same in every code page")
        .matches("\\p{ASCII}*");
  }

  @DisplayName("Nothing is installed from a folder that was not made for an update, as that"
      + " folder is removed in the end")
  @Test
  void installsOnlyFromItsOwnFolder() throws Exception {
    final Path msi = Files.writeString(folder.resolve("some.msi"), "msi");

    assertThatThrownBy(() -> Updater.install(msi, folder)).isInstanceOf(IOException.class);

    final Path staged = Updater.staged(msi);
    assertThat(staged).as("a copy in a folder of its own").hasContent("msi");
    assertThat(staged.getParent()).isNotEqualTo(folder);
    Updater.delete(staged);
    assertThat(staged.getParent()).as("deleted with its folder").doesNotExist();
    assertThat(msi).as("the file it was copied from stays").exists();
  }
}
