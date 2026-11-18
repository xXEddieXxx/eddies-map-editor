package eddies.mapeditor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * A new version installed from within the editor (#28), for an editor that was installed by its
 * Windows installer: the release's msi is downloaded and checked, then the editor closes, Windows
 * Installer replaces it and the new one starts. An editor run from the zip or the sources has no
 * installation to replace and keeps the link to the release's page.
 */
@UtilityClass
@Slf4j
class Updater {
  /** The installer's name in every release, as the Release workflow publishes it. */
  static final String MSI_NAME = "eddies-map-editor-windows.msi";

  /** The program's name: its folder, its exe, its entry in Windows' list of programs. */
  static final String PROGRAM = "Eddies Map Editor";

  /** Only a file of the editor's own releases is ever downloaded and run. */
  static final String RELEASES =
      "https://github.com/xXEddieXxx/eddies-map-editor/releases/download/";

  /** For trying the installation step without a release: the path of an msi to install. */
  static final String TEST_MSI = "EDDIES_MAP_EDITOR_UPDATE_MSI";

  private static final String LINE_END = "\r\n";
  private static final String PRODUCTS = "HKCU\\Software\\Microsoft\\Installer\\Products";
  private static final String UNINSTALL =
      "\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall";
  // the name the temporary folder of a download starts with
  private static final String STAGE = "eddies-map-editor-update";
  private static final Duration CONNECT = Duration.ofSeconds(15);
  // how long the installer waits for the editor to be gone before it gives up
  private static final int WAIT_SECONDS = 60;

  /**
   * A release's installer: where it is, how big, and its SHA-256 as GitHub tells it (releases
   * made before GitHub told digests have none).
   */
  record Installer(String url, long size, Optional<String> sha256) {
    /** Whether it is a file of the editor's own releases. */
    boolean isOwn() {
      return url.startsWith(RELEASES) && url.endsWith("/" + MSI_NAME);
    }
  }

  /**
   * The folder this editor is installed in, when it is the copy Windows Installer put there: the
   * program is among the user's installed products, and Windows' list of programs names this
   * folder as where it was installed, wherever the map maker chose that to be. Empty on other
   * systems, for the zip and for the sources.
   */
  static Optional<Path> installedAt() {
    if (!System.getProperty("os.name", "").startsWith("Windows")) {
      return Optional.empty();
    }
    return programFolder()
        .filter(folder -> isListed(reg(PRODUCTS, "/s", "/f", PROGRAM, "/d", "/e")))
        .filter(
            folder ->
                installLocations().stream().anyMatch(told -> isSamePlace(told, folder.toString())));
  }

  // the folder with the exe: the jar is in its app folder
  private static Optional<Path> programFolder() {
    try {
      final Path jar =
          Path.of(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      return Optional.ofNullable(jar.getParent())
          .map(Path::getParent)
          .filter(folder -> Files.isRegularFile(folder.resolve(PROGRAM + ".exe")));
    } catch (final URISyntaxException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /** Whether reg's answer lists the program among the installed products, by its exact name. */
  static boolean isListed(final String products) {
    return products
        .lines()
        .map(String::strip)
        .anyMatch(line -> line.matches("ProductName\\s+REG_SZ\\s+" + PROGRAM));
  }

  // where Windows' list of programs says the program is installed: for the user or for all
  private static List<String> installLocations() {
    return Stream.of("HKCU", "HKLM")
        .map(hive -> reg(hive + UNINSTALL, "/s", "/f", PROGRAM, "/d", "/e"))
        .flatMap(found -> keys(found).stream())
        .map(key -> reg(key, "/v", "InstallLocation"))
        .flatMap(entry -> installLocation(entry).stream())
        .toList();
  }

  /** The registry keys reg's answer names, each on a line of its own. */
  static List<String> keys(final String answer) {
    return answer.lines().map(String::strip).filter(line -> line.startsWith("HKEY_")).toList();
  }

  /** The folder reg's answer gives as InstallLocation, if it has one. */
  static Optional<String> installLocation(final String answer) {
    return answer
        .lines()
        .map(String::strip)
        .filter(line -> line.matches("InstallLocation\\s+REG_SZ\\s+.+"))
        .map(line -> line.replaceFirst("InstallLocation\\s+REG_SZ\\s+", ""))
        .findFirst();
  }

  /**
   * Whether the folder reg told of is this folder. reg writes letters beyond ASCII in the
   * console's code page, which Java cannot ask for: they are left out of both, as are a last
   * backslash and the difference of capitals.
   */
  static boolean isSamePlace(final String told, final String folder) {
    return plain(told).equals(plain(folder));
  }

  private static String plain(final String path) {
    return path.strip()
        .replaceAll("[^\\x20-\\x7E]", "")
        .replaceAll("\\\\+$", "")
        .toLowerCase(Locale.ROOT);
  }

  // reg query's answer; "" when it cannot be asked. Names and keys are plain ASCII in any code page
  private static String reg(final String key, final String... options) {
    try {
      final List<String> command = new ArrayList<>(List.of("reg", "query", key));
      command.addAll(List.of(options));
      final Process reg = new ProcessBuilder(command).redirectErrorStream(true).start();
      reg.getOutputStream().close();
      final String answer =
          new String(reg.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
      reg.waitFor();
      return answer;
    } catch (final IOException e) {
      return "";
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  /**
   * Downloads the installer into a folder of its own in the temporary files and checks it: its
   * size, and its SHA-256 where GitHub told one. A file that differs is deleted and refused.
   *
   * @param percent told how far the download is, 0 to 100
   * @param cancelled asked now and then; a cancelled download is deleted and gives nothing
   */
  static Optional<Path> download(
      final Installer installer, final IntConsumer percent, final BooleanSupplier cancelled)
      throws IOException, InterruptedException {
    if (!installer.isOwn()) {
      throw new IOException("Not a file of the editor's releases: " + installer.url());
    }
    final Path msi = Files.createTempDirectory(STAGE).resolve(MSI_NAME);
    final HttpResponse<InputStream> response =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
            .send(
                HttpRequest.newBuilder(URI.create(installer.url()))
                    .header("User-Agent", "eddies-map-editor")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream in = response.body()) {
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode());
      }
      final MessageDigest digest = sha256();
      long read = 0;
      try (OutputStream out = Files.newOutputStream(msi)) {
        final byte[] buffer = new byte[1 << 16];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
          if (cancelled.getAsBoolean()) {
            out.close();
            delete(msi);
            return Optional.empty();
          }
          out.write(buffer, 0, n);
          digest.update(buffer, 0, n);
          read += n;
          percent.accept((int) Math.min(100, read * 100 / Math.max(1, installer.size())));
        }
      }
      final Optional<String> problem =
          problem(installer, read, HexFormat.of().formatHex(digest.digest()));
      if (problem.isPresent()) {
        delete(msi);
        throw new IOException(problem.get());
      }
      return Optional.of(msi);
    } catch (final IOException | RuntimeException e) {
      delete(msi);
      throw e;
    }
  }

  /** A copy of the msi in a folder made for an update, as a download is, for {@link #TEST_MSI}. */
  static Path staged(final Path msi) throws IOException {
    return Files.copy(msi, Files.createTempDirectory(STAGE).resolve(MSI_NAME));
  }

  /** What is wrong with a downloaded file of this size and SHA-256, if anything. */
  static Optional<String> problem(final Installer installer, final long size, final String sha256) {
    if (size != installer.size()) {
      return Optional.of(
          "The download has " + size + " bytes, the release's file " + installer.size());
    }
    return installer
        .sha256()
        .filter(told -> !told.equalsIgnoreCase(sha256))
        .map(told -> "The download is not the release's file: its SHA-256 differs");
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The file and the folder made for it. */
  static void delete(final Path msi) {
    try {
      Files.deleteIfExists(msi);
      Files.deleteIfExists(msi.getParent());
    } catch (final IOException e) {
      log.info("The downloaded installer stays: {}", msi, e);
    }
  }

  /**
   * Starts the installation; the caller then closes the editor at once. A script without a window
   * waits until this editor and the program that started it are gone, as Windows cannot replace a
   * running program, lets Windows Installer replace it showing only its progress, deletes the
   * download and starts the editor again.
   */
  static void install(final Path msi, final Path folder) throws IOException {
    // the script removes the msi's folder in the end: only ever one made for an update
    if (!msi.getParent().getFileName().toString().startsWith(STAGE)) {
      throw new IOException("Not in a folder made for an update: " + msi);
    }
    final ProcessHandle editor = ProcessHandle.current();
    final Path script = msi.resolveSibling("install.cmd");
    Files.writeString(script, SCRIPT, StandardCharsets.US_ASCII);
    final ProcessBuilder installing =
        new ProcessBuilder("cmd.exe", "/c", script.toString())
            .redirectInput(ProcessBuilder.Redirect.from(new File("NUL")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    // the paths go as variables, not as text of the script: no code page or quote in their way
    installing.environment().put("EDITOR_PID", String.valueOf(editor.pid()));
    // the exe's own Java started this one and waits for it
    installing
        .environment()
        .put("STARTER_PID", String.valueOf(editor.parent().orElse(editor).pid()));
    installing.environment().put("UPDATE_MSI", msi.toString());
    installing.environment().put("EDITOR_EXE", folder.resolve(PROGRAM + ".exe").toString());
    // into the folder it is in: Windows Installer does not remember one the map maker chose
    installing.environment().put("EDITOR_DIR", folder.toString());
    installing.start();
  }

  /**
   * What happens once the editor is closed. With the editor still there after a minute nothing is
   * installed, as the installer would ask to restart Windows for the files in use. The last line
   * removes the download's folder, this script with it.
   */
  static final String SCRIPT =
      String.join(
          LINE_END,
          "@echo off",
          "set TRIES=0",
          // the system's own tools by their place, whatever else of their names is on the PATH
          "set SYS=%SystemRoot%\\System32\\",
          ":wait",
          "%SYS%tasklist.exe /FI \"PID eq %EDITOR_PID%\" /NH 2>nul | %SYS%find.exe \"%EDITOR_PID%\" >nul && goto busy",
          "%SYS%tasklist.exe /FI \"PID eq %STARTER_PID%\" /NH 2>nul | %SYS%find.exe \"%STARTER_PID%\" >nul && goto busy",
          "goto install",
          ":busy",
          "set /a TRIES+=1",
          "if %TRIES% GEQ " + WAIT_SECONDS + " goto end",
          "%SYS%ping.exe -n 2 127.0.0.1 >nul",
          "goto wait",
          ":install",
          "start \"\" /wait %SYS%msiexec.exe /i \"%UPDATE_MSI%\" /passive /norestart"
              + " INSTALLDIR=\"%EDITOR_DIR%\"",
          "start \"\" \"%EDITOR_EXE%\"",
          ":end",
          "cd /d \"%TEMP%\"",
          "rmdir /s /q \"%~dp0\"",
          "");
}
