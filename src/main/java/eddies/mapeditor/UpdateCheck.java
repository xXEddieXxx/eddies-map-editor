package eddies.mapeditor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
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
  // the newer release learnt of last: the start of its keys
  private static final String KNOWN = "newVersion";
  private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
  private static final Pattern PAGE =
      Pattern.compile("\"html_url\"\\s*:\\s*\"(https://github\\.com/[^\"]*/releases/tag/[^\"]+)\"");
  private static final Pattern SIZE = Pattern.compile("\"size\"\\s*:\\s*(\\d+)");
  private static final Pattern DIGEST =
      Pattern.compile("\"digest\"\\s*:\\s*\"sha256:([0-9a-fA-F]{64})\"");
  private static final Preferences PREFERENCES = Preferences.userNodeForPackage(UpdateCheck.class);

  /**
   * A release: its version without the v, its page with the downloads, its Windows installer
   * where it has one, and what is new in it: the titles of its notes' entries, its section of
   * CHANGELOG.md, eg "Random starts".
   */
  record Release(
      String version, String page, Optional<Updater.Installer> installer, List<String> news) {
    Release {
      news = List.copyOf(news);
    }

    Release(final String version, final String page) {
      this(version, page, Optional.empty());
    }

    Release(final String version, final String page, final Optional<Updater.Installer> installer) {
      this(version, page, installer, List.of());
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
   * than this version on the Swing thread, with whether it is told for the first time. The
   * release is kept for {@link #known}. A failed request is logged alone; the next try is a day
   * later.
   */
  static void whenDue(final String own, final BiConsumer<Release, Boolean> found) {
    final long now = System.currentTimeMillis();
    if (!tells() || own.equals("dev") || !isDue(now, PREFERENCES)) {
      return;
    }
    PREFERENCES.putLong(ASKED_AT, now);
    inBackground(
        () ->
            latest(own)
                .filter(release -> isNewer(own, release.version()))
                .ifPresent(
                    release -> {
                      final boolean first = toTell(own, release, PREFERENCES);
                      keep(release, PREFERENCES);
                      PREFERENCES.put(TOLD, release.version());
                      SwingUtilities.invokeLater(() -> found.accept(release, first));
                    }));
  }

  /**
   * The newer release learnt of at an earlier start, while this version is still the older one:
   * shown at once at every start, without asking GitHub again. None when telling is turned off.
   */
  static Optional<Release> known(final String own) {
    return tells() && !own.equals("dev") ? known(own, PREFERENCES) : Optional.empty();
  }

  static Optional<Release> known(final String own, final Preferences preferences) {
    final String version = preferences.get(KNOWN + "Version", "");
    final String page = preferences.get(KNOWN + "Page", "");
    if (version.isEmpty() || page.isEmpty() || !isNewer(own, version)) {
      return Optional.empty();
    }
    final String msi = preferences.get(KNOWN + "Msi", "");
    final String sha = preferences.get(KNOWN + "MsiSha", "");
    final String news = preferences.get(KNOWN + "News", "");
    return Optional.of(
        new Release(
            version,
            page,
            Optional.of(
                    new Updater.Installer(
                        msi,
                        preferences.getLong(KNOWN + "MsiSize", 0),
                        Optional.of(sha).filter(told -> !told.isEmpty())))
                .filter(Updater.Installer::isOwn),
            news.isEmpty() ? List.of() : List.of(news.split("\n"))));
  }

  /** Keeps the release for {@link #known}, in these preferences. */
  static void keep(final Release release, final Preferences preferences) {
    preferences.put(KNOWN + "Version", release.version());
    preferences.put(KNOWN + "Page", release.page());
    preferences.put(KNOWN + "Msi", release.installer().map(Updater.Installer::url).orElse(""));
    preferences.putLong(
        KNOWN + "MsiSize", release.installer().map(Updater.Installer::size).orElse(0L));
    preferences.put(
        KNOWN + "MsiSha", release.installer().flatMap(Updater.Installer::sha256).orElse(""));
    preferences.put(KNOWN + "News", String.join("\n", release.news()));
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
    return Optional.of(new Release(tag.group(1), page.group(1), installer(json), news(json)));
  }

  // an entry of the notes, "- **Its title** (#53). What it does"
  private static final Pattern ENTRY = Pattern.compile("(?m)^- \\*\\*(.+?)\\*\\*");
  // as many as a preference keeps well and a dialog shows
  private static final int MOST_NEWS = 12;

  /**
   * The titles of the entries of the release's notes, before the "---" the downloads follow, eg
   * "Random starts"; none for notes without such entries, eg a release before 0.2.7.
   */
  static List<String> news(final String json) {
    final String notes = body(json).map(UpdateCheck::unescaped).orElse("").split("\n---", 2)[0];
    final List<String> titles = new ArrayList<>();
    final Matcher entry = ENTRY.matcher(notes);
    while (entry.find() && titles.size() < MOST_NEWS) {
      titles.add(entry.group(1).replace("`", "").strip());
    }
    return List.copyOf(titles);
  }

  // the "body" string of the answer as written, up to its closing quote: scanned, not matched by
  // a pattern, as notes are thousands of characters long
  private static Optional<String> body(final String json) {
    final Matcher key = BODY_KEY.matcher(json);
    if (!key.find()) {
      return Optional.empty();
    }
    for (int i = key.end(); i < json.length(); i++) {
      final char c = json.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '"') {
        return Optional.of(json.substring(key.end(), i));
      }
    }
    return Optional.empty();
  }

  private static final Pattern BODY_KEY = Pattern.compile("\"body\"\\s*:\\s*\"");

  // a JSON string's text: its escapes as the characters they stand for
  private static String unescaped(final String text) {
    final StringBuilder plain = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c != '\\' || i + 1 == text.length()) {
        plain.append(c);
        continue;
      }
      final char next = text.charAt(++i);
      switch (next) {
        case 'n' -> plain.append('\n');
        case 'r' -> plain.append('\r');
        case 't' -> plain.append('\t');
        case 'u' -> {
          final String hex = i + 4 < text.length() ? text.substring(i + 1, i + 5) : "";
          if (hex.matches("[0-9a-fA-F]{4}")) {
            plain.append((char) Integer.parseInt(hex, 16));
            i += 4;
          } else {
            plain.append('u');
          }
        }
        default -> plain.append(next);
      }
    }
    return plain.toString();
  }

  // the release's Windows installer, when it has one that is a file of the editor's releases: the
  // setup, else for a release before there was one the msi
  private static Optional<Updater.Installer> installer(final String json) {
    return installer(json, Updater.SETUP_NAME).or(() -> installer(json, Updater.MSI_NAME));
  }

  // the file of this name among the release's: from its name to its link, with its size and digest
  private static Optional<Updater.Installer> installer(final String json, final String name) {
    final Matcher msi =
        Pattern.compile(
                "\"name\"\\s*:\\s*\""
                    + Pattern.quote(name)
                    + "\"(.*?)\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"",
                Pattern.DOTALL)
            .matcher(json);
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
