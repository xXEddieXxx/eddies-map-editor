package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EddiesMapEditorTest {
  @TempDir Path folder;

  private Path givenJar(final String inside) throws Exception {
    final Path jar = folder.resolve(inside).resolve("game-headed-2026-11.16.15914.jar");
    Files.createDirectories(jar.getParent());
    return Files.createFile(jar);
  }

  @DisplayName("TripleA's jar is found beside TripleA.exe, inside TripleA.app and in a folder")
  @Test
  void findsTripleasJar() throws Exception {
    final Path windowsJar = givenJar("TripleA/bin");
    final Path macJar = givenJar("TripleA.app/Contents/app/bin");
    final Path unzippedJar = givenJar("game-headed/bin");

    final Optional<Path> windows = EddiesMapEditor.jarOf(folder.resolve("TripleA/TripleA.exe"));
    final Optional<Path> mac = EddiesMapEditor.jarOf(folder.resolve("TripleA.app"));
    final Optional<Path> unzipped = EddiesMapEditor.jarOf(folder.resolve("game-headed"));
    final Optional<Path> none = EddiesMapEditor.jarOf(folder.resolve("gone/TripleA.exe"));

    assertThat(windows).as("in bin beside the program").contains(windowsJar);
    assertThat(mac).as("somewhere in the app").contains(macJar);
    assertThat(unzipped).as("in a chosen folder, eg an unzipped game-headed.zip")
        .contains(unzippedJar);
    assertThat(none).as("no TripleA there").isEmpty();
  }

  @DisplayName("Only TripleA's program is kept for Play, not its folder, jar or uninstaller")
  @Test
  void keepsOnlyTheProgramForPlay() throws Exception {
    final Path jar = givenJar("TripleA/bin");
    final Path exe = Files.createFile(folder.resolve("TripleA/TripleA.exe"));
    final Path uninstall = Files.createFile(folder.resolve("TripleA/uninstall.exe"));

    assertThat(EddiesMapEditor.isProgram(exe)).as("the program").isTrue();
    assertThat(EddiesMapEditor.isProgram(folder.resolve("TripleA.app"))).as("the app").isTrue();
    assertThat(EddiesMapEditor.isProgram(folder.resolve("TripleA"))).as("its folder").isFalse();
    assertThat(EddiesMapEditor.isProgram(jar)).as("its jar").isFalse();
    assertThat(EddiesMapEditor.isProgram(uninstall)).as("the uninstaller").isFalse();
  }

  @DisplayName("The editor starts again with TripleA's jar after its own class path")
  @Test
  void startsAgainWithTriplea() {
    final Path jar = Path.of("C:/Program Files/TripleA/bin/game-headed.jar");

    final List<String> command = EddiesMapEditor.command(jar, new String[] {"a"});

    assertThat(command)
        .as("TripleA's jar last on the class path, then the editor with its arguments")
        .containsSubsequence(
            "-classpath",
            System.getProperty("java.class.path") + File.pathSeparator + jar,
            EddiesMapEditor.class.getName(),
            "a");
  }

  @DisplayName("Only a TripleA with FlatLaf (from 2026-06) is new enough; an older one is not")
  @Test
  void tellsTooOldTriplea() throws Exception {
    // given
    final Path newer = givenJar("new.jar", "com/formdev/flatlaf/FlatLaf.class");
    final Path older = givenJar("old.jar", "org/pushingpixels/substance/api/SubstanceSkin.class");

    // when, then
    assertThat(EddiesMapEditor.isNewEnough(newer)).as("has FlatLaf").isTrue();
    assertThat(EddiesMapEditor.isNewEnough(older)).as("Substance, before 2026-06").isFalse();
    assertThat(EddiesMapEditor.isNewEnough(folder.resolve("missing.jar")))
        .as("no jar")
        .isFalse();
  }

  private Path givenJar(final String name, final String entry) throws Exception {
    final Path jar = folder.resolve(name);
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
      zip.putNextEntry(new ZipEntry(entry));
      zip.closeEntry();
    }
    return jar;
  }
}
