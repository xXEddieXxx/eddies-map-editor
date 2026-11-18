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
 * versions" in the File menu turns it off. A build from the sources ("dev") never asks.
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
  private static final Preferences PREFERENCES = Preferences.userNodeForPackage(UpdateCheck.class);

  /** A release: its version without the v, and its page with the downloads. */
  record Release(String version, String page) {}

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
    final Thread asking =
        new Thread(
            () -> {
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
                  return;
                }
                parse(response.body())
                    .filter(release -> toTell(own, release, PREFERENCES))
                    .ifPresent(
                        release -> {
                          PREFERENCES.put(TOLD, release.version());
                          SwingUtilities.invokeLater(() -> found.accept(release));
                        });
              } catch (final IOException | RuntimeException e) {
                log.info("No word of the latest release", e);
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "update check");
    asking.setDaemon(true);
    asking.start();
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
    return Optional.of(new Release(tag.group(1), page.group(1)));
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
