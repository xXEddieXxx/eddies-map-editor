package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The check for a new version: what GitHub's answer says, what counts as newer, when to tell. */
final class UpdateCheckTest {
  private static final String ANSWER =
      """
      {
        "url": "https://api.github.com/repos/xXEddieXxx/eddies-map-editor/releases/1",
        "html_url": "https://github.com/xXEddieXxx/eddies-map-editor/releases/tag/v0.2.2",
        "tag_name": "v0.2.2",
        "name": "Eddie's Map Editor 0.2.2",
        "assets": [{"html_url": "https://github.com/x/y/releases/download/v0.2.2/a.zip"}]
      }
      """;

  @DisplayName("GitHub's answer gives the version without its v and the release's page")
  @Test
  void parsesTheAnswer() {
    assertThat(UpdateCheck.parse(ANSWER))
        .contains(
            new UpdateCheck.Release(
                "0.2.2", "https://github.com/xXEddieXxx/eddies-map-editor/releases/tag/v0.2.2"));
    assertThat(UpdateCheck.parse("{\"message\": \"Not Found\"}")).as("no release").isEmpty();
  }

  @DisplayName("What is new: the titles of the notes' entries, from CHANGELOG.md, kept and offered")
  @Test
  void readsWhatIsNew() {
    // given: notes as the release workflow writes them, the downloads after the "---"
    final String answer =
        """
        {
          "html_url": "https://github.com/xXEddieXxx/eddies-map-editor/releases/tag/v0.2.7",
          "tag_name": "v0.2.7",
          "body": "## What's new in 0.2.7\\r\\n\\r\\n### Fixed\\r\\n\\r\\n- **Loops\
         (`foreach`) were read only in Units** (#43). Territories\\r\\n  show defaults.\\r\\n\
        - **Random starts** (#53). On a map\\u2026\\r\\n\\r\\n---\\r\\n\\r\\n\
        - **Windows:** the setup"
        }
        """;
    final Preferences preferences =
        Preferences.userRoot().node("UpdateCheckTest/" + System.nanoTime());

    // when
    final UpdateCheck.Release release = UpdateCheck.parse(answer).orElseThrow();
    UpdateCheck.keep(release, preferences);

    // then
    assertThat(release.news())
        .as("the entries' titles, without the downloads after ---")
        .containsExactly("Loops (foreach) were read only in Units", "Random starts");
    assertThat(UpdateCheck.known("0.2.6", preferences).map(UpdateCheck.Release::news))
        .as("kept for the next start")
        .contains(release.news());
    assertThat(MapMakerWindow.newsLines(release.news()))
        .as("in the offer, a line each")
        .isEqualTo(
            "\nNew in it:\n• Loops (foreach) were read only in Units\n• Random starts\n");
    assertThat(MapMakerWindow.newsLines(List.of())).as("an older release: nothing").isEmpty();
  }

  @DisplayName("Notes as long as a real release's are read on the check's own thread")
  @Test
  void readsLongNotes() throws Exception {
    // given: notes of a release's length, made here so they do not follow CHANGELOG.md: a title
    // with \ and ", 20 entries of a few lines each, then the downloads, as JSON
    final StringBuilder entries = new StringBuilder();
    for (int n = 1; n <= 20; n++) {
      entries
          .append("- **Change ")
          .append(n)
          .append("** (#")
          .append(n)
          .append("). ")
          .append("What it does, in a few lines. ".repeat(12))
          .append("\n");
    }
    final String notes =
        "## What's new in 0.2.8\n\n### Fixed\n\n- **A \"quoted\" C:\\maps title** (#1).\n"
            + entries
            + "\n---\n\n"
            + "x".repeat(4000);
    final String json =
        "{\"html_url\": \"https://github.com/xXEddieXxx/eddies-map-editor/releases/tag/v0.2.7\","
            + " \"tag_name\": \"v0.2.7\", \"body\": \""
            + notes.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\r\\n")
            + "\"}";
    final List<List<String>> read = new ArrayList<>();

    // when: on a thread of its own, as the check asks
    final Thread check =
        new Thread(() -> read.add(UpdateCheck.parse(json).orElseThrow().news()));
    check.start();
    check.join();

    // then
    assertThat(json.length()).as("given: as long as a real release's answer").isGreaterThan(8000);
    assertThat(read)
        .as("read, the first titles, the downloads left out")
        .singleElement()
        .satisfies(
            news ->
                assertThat(news)
                    .startsWith("A \"quoted\" C:\\maps title", "Change 1")
                    .hasSize(12));
  }

  @DisplayName("A release with the Windows installer gives its link, size and SHA-256; one of"
      + " another repository's file gives none")
  @Test
  void parsesTheInstaller() {
    final String link =
        "https://github.com/xXEddieXxx/eddies-map-editor/releases/download/v0.2.4/"
            + "eddies-map-editor-windows.msi";
    final String answer =
        """
        {
          "html_url": "https://github.com/xXEddieXxx/eddies-map-editor/releases/tag/v0.2.4",
          "tag_name": "v0.2.4",
          "assets": [
            {"name": "eddies-map-editor-windows.zip", "size": 5, "digest": "sha256:%s",
             "browser_download_url": "https://github.com/x/y/releases/download/v0.2.4/a.zip"},
            {"name": "eddies-map-editor-windows.msi", "uploader": {"login": "bot"},
             "size": 53125594, "digest": "sha256:%s", "download_count": 3,
             "browser_download_url": "%s"}
          ]
        }
        """
            .formatted("11".repeat(32), "ab".repeat(32), link);

    assertThat(UpdateCheck.parse(answer).orElseThrow().installer())
        .contains(new Updater.Installer(link, 53125594, java.util.Optional.of("ab".repeat(32))));
    assertThat(
            UpdateCheck.parse(answer.replace("xXEddieXxx/eddies-map-editor/releases/download", "x/y"))
                .orElseThrow()
                .installer())
        .as("a link that is not to the editor's releases")
        .isEmpty();
    assertThat(UpdateCheck.parse(ANSWER).orElseThrow().installer())
        .as("a release without the installer")
        .isEmpty();
    final String setup = link.replace("eddies-map-editor-windows.msi", "eddies-map-editor-setup.exe");
    final String withSetup =
        answer.replace(
            "\"assets\": [",
            "\"assets\": [{\"name\": \"eddies-map-editor-setup.exe\", \"size\": 7,"
                + " \"browser_download_url\": \"" + setup + "\"},");
    assertThat(UpdateCheck.parse(withSetup).orElseThrow().installer())
        .as("a release with the setup: that, not the msi beside it")
        .contains(new Updater.Installer(setup, 7, java.util.Optional.empty()));
  }

  @DisplayName("Newer is told number by number: 0.2.10 after 0.2.9, 0.3 after 0.2.9, never itself")
  @Test
  void comparesVersions() {
    assertThat(UpdateCheck.isNewer("0.2.9", "0.2.10")).isTrue();
    assertThat(UpdateCheck.isNewer("0.2.9", "0.3")).isTrue();
    assertThat(UpdateCheck.isNewer("0.2.1", "0.2.1")).isFalse();
    assertThat(UpdateCheck.isNewer("0.2.2", "0.2.1")).isFalse();
    assertThat(UpdateCheck.isNewer("0.2.1-rc", "0.2.1")).as("a suffix counts as nothing").isFalse();
    assertThat(UpdateCheck.isNewer("1.0", "1.0.0")).isFalse();
  }

  @DisplayName("Asked once a day; a newer version is told once, an older or told one not")
  @Test
  void asksOnceADayAndTellsOnce() {
    // the tests' preferences are in memory, a node of this test's own
    final Preferences preferences = Preferences.userRoot().node("UpdateCheckTest/" + System.nanoTime());
    final UpdateCheck.Release newer = new UpdateCheck.Release("0.2.2", "page");
    final UpdateCheck.Release older = new UpdateCheck.Release("0.2.0", "page");
    final long now = System.currentTimeMillis();

    assertThat(UpdateCheck.isDue(now, preferences)).as("never asked: due").isTrue();
    preferences.putLong("newVersionAskedAt", now);
    assertThat(UpdateCheck.isDue(now + 1000, preferences)).as("asked just now").isFalse();
    assertThat(UpdateCheck.isDue(now + UpdateCheck.EVERY.toMillis(), preferences))
        .as("a day later")
        .isTrue();

    assertThat(UpdateCheck.toTell("0.2.1", newer, preferences)).isTrue();
    assertThat(UpdateCheck.toTell("0.2.1", older, preferences)).as("not newer").isFalse();
    preferences.put("newVersionTold", "0.2.2");
    assertThat(UpdateCheck.toTell("0.2.1", newer, preferences)).as("told already").isFalse();
    assertThat(UpdateCheck.toTell("0.2.1", new UpdateCheck.Release("0.2.3", "page"), preferences))
        .as("the next one is told")
        .isTrue();
  }

  @DisplayName("A newer release is kept and known at the next start, with its installer, until"
      + " this version is as new")
  @Test
  void keepsTheNewerRelease() {
    final Preferences preferences = Preferences.userRoot().node("UpdateCheckTest/" + System.nanoTime());
    final Updater.Installer installer =
        new Updater.Installer(
            Updater.RELEASES + "v0.2.4/" + Updater.MSI_NAME,
            53125594,
            java.util.Optional.of("ab".repeat(32)));
    final UpdateCheck.Release release =
        new UpdateCheck.Release("0.2.4", "page", java.util.Optional.of(installer));

    assertThat(UpdateCheck.known("0.2.3", preferences)).as("nothing learnt yet").isEmpty();
    UpdateCheck.keep(release, preferences);

    assertThat(UpdateCheck.known("0.2.3", preferences)).contains(release);
    assertThat(UpdateCheck.known("0.2.4", preferences)).as("installed since").isEmpty();
    UpdateCheck.keep(new UpdateCheck.Release("0.2.5", "page"), preferences);
    assertThat(UpdateCheck.known("0.2.3", preferences))
        .as("a release without the installer")
        .contains(new UpdateCheck.Release("0.2.5", "page"));
  }
}
