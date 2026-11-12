package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.MapMakerWindow.Check;
import eddies.mapeditor.MapMakerWindow.Pill;
import eddies.mapeditor.Theme.Status;
import java.util.List;
import java.util.Optional;
import javax.swing.JButton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class MapMakerWindowTest {

  @DisplayName("The pill says what Check found, that it runs, or that it has not run yet")
  @Test
  void pillTellsTheProblems() {
    final List<Pill> pills =
        List.of(
            MapMakerWindow.pill(0, Check.NOT_YET),
            MapMakerWindow.pill(3, Check.RUNNING),
            MapMakerWindow.pill(1, Check.NOT_YET),
            MapMakerWindow.pill(4, Check.DONE),
            MapMakerWindow.pill(0, Check.DONE));

    assertThat(pills)
        .as("words and marks, never color alone")
        .containsExactly(
            new Pill("Not checked yet", Status.TODO),
            new Pill("Checking…", Status.TODO),
            new Pill("! 1 problem", Status.PROBLEM),
            new Pill("! 4 problems", Status.PROBLEM),
            new Pill("✓ No problems", Status.DONE));
  }

  @DisplayName("One primary in the header: Save while there are changes, else Play")
  @Test
  void savePrimaryWhenChangedElsePlay() {
    final MapProject project = MapProject.newEmpty();
    final JButton save = new JButton("Save");
    final JButton play = new JButton("Play");

    MapMakerWindow.showSaveAndPlay(project, save, play);
    final boolean saveShownClean = save.isVisible();
    final boolean playPrimaryClean = Theme.isPrimary(play);
    givenChange(project);
    MapMakerWindow.showSaveAndPlay(project, save, play);

    assertThat(saveShownClean).as("all saved: no Save button").isFalse();
    assertThat(playPrimaryClean).as("all saved: Play is the primary").isTrue();
    assertThat(save.isVisible()).as("changed: Save shows").isTrue();
    assertThat(Theme.isPrimary(save)).as("changed: Save is the primary").isTrue();
    assertThat(Theme.isPrimary(play)).as("changed: Play is secondary").isFalse();
  }

  @DisplayName("The header names the game only when it is named apart from the map")
  @Test
  void headerSaysTheNameOnce() {
    final String map = "Aggression 1941";

    final String same = MapMakerWindow.headerTitle(map, Optional.of(map));
    final String other = MapMakerWindow.headerTitle(map, Optional.of("Short game"));

    assertThat(same).as("not \"X · X\"").isEqualTo("Aggression 1941");
    assertThat(other).as("another game is told apart").isEqualTo("Aggression 1941 · Short game");
  }

  private static void givenChange(final MapProject project) {
    project.edit(game -> MapXml.withNation(game, "Red"));
  }
}
