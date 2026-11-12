package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class StartPageTest {

  @DisplayName("Without recent maps the cards sit in the middle; with them 120 px down")
  @Test
  void centredWithoutRecentMaps() {
    final Component firstRun = givenColumnLaidOut(List.of());
    final Component withRecent = givenColumnLaidOut(List.of(Path.of("gone", "game.xml")));

    final int centredTop = (861 - firstRun.getHeight()) / 2;

    assertThat(firstRun.getY())
        .as("no empty lower half on the first run")
        .isBetween(centredTop - 2, centredTop + 2);
    assertThat(withRecent.getY()).as("the recent maps grow downwards").isEqualTo(120);
  }

  /** The page's one column, laid out in a 1440 x 900 window. */
  private static Component givenColumnLaidOut(final List<Path> recentGames) {
    final StartPage page = new StartPage(() -> {}, () -> {}, game -> {}, recentGames);
    page.setSize(1424, 861);
    page.doLayout();
    return page.getComponent(0);
  }
}
