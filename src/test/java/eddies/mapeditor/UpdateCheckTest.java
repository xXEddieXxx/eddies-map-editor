package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

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
}
