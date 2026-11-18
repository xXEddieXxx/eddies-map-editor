package eddies.mapeditor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * A newer release of the editor on GitHub: asked for once a day when the editor starts, told once
 * per version. Nothing but the request for the latest release leaves the computer; "Tell me of new
 * versions" in the File menu turns it off. A build from the sources ("dev") never asks. "Look
 * for a new version" there asks at once. What is done with a newer release is {@link Updater}'s.
 */
@UtilityClass
@Slf4j
class UpdateCheck {
  static final String LATEST =
      "https://api.github.com/repos/xXEddieXxx/eddies-map-editor/releases/latest";
  static final Duration EVERY = Duration.ofDays(1);
  private static final Duration TIMEOUT = Duration.ofSeconds(8);
  private static final String TELL = "tellOfNewVersions";
  private static final String ASKED_AT = "newVersionAskedAt";
  private static final String TOLD = "newVersionTold";
  private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
  private static final Pattern PAGE =
      Pattern.compile("\"html_url\"\\s*:\\s*\"(https://github\\.com/[^\"]*/releases/tag/[^\"]+)\"");
  // the installer among the release's files: from its name to its link, with its size and digest
  private static final Pattern MSI =
      Pattern.compile(
          "\"name\"\\s*:\\s*\""
              + Pattern.quote(Updater.MSI_NAME)
              + "\"(.*?)\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"",
          Pattern.DOTALL);
  private static final Pattern SIZE = Pattern.compile("\"size\"\\s*:\\s*(\\d+)");
  private static final Pattern DIGEST =
      Pattern.compile("\"digest\"\\s*:\\s*\"sha256:([0-9a-fA-F]{64})\"");
  private static final Preferences PREFERENCES = Preferences.userNodeForPackage(UpdateCheck.class);

  /**
   * A release: its version without the v, its page with the downloads, and its Windows installer
   * where it has one.
   */
  record Release(String version, String page, Optional<Updater.Installer> installer) {
    Release(final String version, final String page) {
      this(version, page, Optional.empty());
    }
  }

  /** Whether the editor asks GitHub for new versions; on unless turned off in the File menu. */
  static boolean tells() {
    return PREFERENCES.getBoolean(TELL, true);
  }

  static void setTells(final boolean tells) {
    PREFERENCES.putBoolean(TELL, tells);
  }

  /**
   * Asks for the latest release on a thread of its own when it is time, and gives a release newer
   * than this version, not told of before, on the Swing thread. A failed request is logged alone;
   * the next try is a day later.
   */
  static void whenDue(final String own, final Consumer<Release> found) {
    final long now = System.currentTimeMillis();
    if (!tells() || own.equals("dev") || !isDue(now, PREFERENCES)) {
      return;
    }
    PREFERENCES.putLong(ASKED_AT, now);
    inBackground(
        () ->
            latest(own)
                .filter(release -> toTell(own, release, PREFERENCES))
                .ifPresent(
                    release -> {
                      PREFERENCES.put(TOLD, release.version());
                      SwingUtilities.invokeLater(() -> found.accept(release));
                    }));
  }

  /**
   * Asks for the latest release now, whatever was asked or told before, as "Look for a new
   * version" does, and gives it on the Swing thread: the release, also when it is this version or
   * older, or nothing when GitHub gave no answer.
   */
  static void now(final String own, final Consumer<Optional<Release>> answered) {
    inBackground(
        () -> {
          final Optional<Release> latest = latest(own);
          SwingUtilities.invokeLater(() -> answered.accept(latest));
        });
  }

  private static void inBackground(final Runnable asking) {
    final Thread thread = new Thread(asking, "update check");
    thread.setDaemon(true);
    thread.start();
  }

  // the latest release as GitHub tells it now; a failed request is logged and gives none
  private static Optional<Release> latest(final String own) {
    try {
      final HttpResponse<String> response =
          HttpClient.newBuilder()
              .connectTimeout(TIMEOUT)
              .followRedirects(HttpClient.Redirect.NORMAL)
              .build()
              .send(
                  HttpRequest.newBuilder(URI.create(LATEST))
                      .timeout(TIMEOUT)
                      .header("Accept", "application/vnd.github+json")
                      .header("User-Agent", "eddies-map-editor/" + own)
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.info("No word of the latest release: HTTP {}", response.statusCode());
        return Optional.empty();
      }
      return parse(response.body());
    } catch (final IOException | RuntimeException e) {
      log.info("No word of the latest release", e);
      return Optional.empty();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /** Whether a day has passed since the last time asked, by these preferences. */
  static boolean isDue(final long now, final Preferences preferences) {
    return now - preferences.getLong(ASKED_AT, 0) >= EVERY.toMillis();
  }

  /** A release newer than this version that was not told of before, by these preferences. */
  static boolean toTell(final String own, final Release release, final Preferences preferences) {
    return isNewer(own, release.version())
        && !release.version().equals(preferences.get(TOLD, ""));
  }

  /** The release GitHub's answer names: its tag without the v, and its page. */
  static Optional<Release> parse(final String json) {
    final Matcher tag = TAG.matcher(json);
    final Matcher page = PAGE.matcher(json);
    if (!tag.find() || !page.find()) {
      return Optional.empty();
    }
    return Optional.of(new Release(tag.group(1), page.group(1), installer(json)));
  }

  // the release's Windows installer, when it has one that is a file of the editor's releases
  private static Optional<Updater.Installer> installer(final String json) {
    final Matcher msi = MSI.matcher(json);
    if (!msi.find()) {
      return Optional.empty();
    }
    final Matcher size = SIZE.matcher(msi.group(1));
    final Matcher digest = DIGEST.matcher(msi.group(1));
    if (!size.find()) {
      return Optional.empty();
    }
    return Optional.of(
            new Updater.Installer(
                msi.group(2),
                Long.parseLong(size.group(1)),
                digest.find() ? Optional.of(digest.group(1)) : Optional.empty()))
        .filter(Updater.Installer::isOwn);
  }

  /**
   * Whether the other version is newer than this one, number by number: 0.2.10 is newer than
   * 0.2.9, 0.3 than 0.2.9; what is no number counts as 0, so 0.2.1-rc is 0.2.1.
   */
  static boolean isNewer(final String own, final String other) {
    final String[] mine = own.strip().split("[.\\-]");
    final String[] theirs = other.strip().split("[.\\-]");
    for (int i = 0; i < Math.max(mine.length, theirs.length); i++) {
      final int a = i < mine.length ? number(mine[i]) : 0;
      final int b = i < theirs.length ? number(theirs[i]) : 0;
      if (a != b) {
        return b > a;
      }
    }
    return false;
  }

  private static int number(final String part) {
    final Matcher digits = Pattern.compile("\\d+").matcher(part);
    return digits.lookingAt() ? Integer.parseInt(digits.group()) : 0;
  }
}
