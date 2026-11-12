package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.MapMakerWindow.Check;
import eddies.mapeditor.MapMakerWindow.Pill;
import eddies.mapeditor.Theme.Status;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.map.data.elements.Game;

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

  @DisplayName("Following a step opens its tool with the step as the goal and the brush it needs")
  @Test
  void followsAStepIntoItsTool() throws Exception {
    // given: the minimap with the Italians, its second nation, owning no land
    final MapProject project = givenMinimapWithoutItalianLand();
    final List<Object> seen = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapMakerWindow window = new MapMakerWindow();
          try {
            window.showProject(project);
            final MapView map = givenMap(window);
            window.followStep(MapGuide.Step.Id.LAND);
            seen.add(map.bannerText().orElseThrow());
            seen.add(map.goalStep());
            window.followStep(MapGuide.Step.Id.CAPITALS);
            seen.add(map.bannerText().orElseThrow());
            seen.add(map.goalStep());
            window.followStep(MapGuide.Step.Id.TERRITORIES);
            seen.add(map.goalText());
          } finally {
            window.dispose();
          }
        });

    // then
    assertThat(seen)
        .as("the Italians' owner brush, then the Capital brush, each with its step; no strip for"
            + " the territories")
        .containsExactly(
            "Painting owner Italians: click or drag across land to give it to Italians. Sea zones"
                + " stay as they are.",
            Optional.of(MapGuide.Step.Id.LAND),
            "Painting capitals: click land to make it its owner's capital.",
            Optional.of(MapGuide.Step.Id.CAPITALS),
            Optional.empty());
  }

  private static MapProject givenMinimapWithoutItalianLand() {
    final Game minimap = MapXmlTest.givenMinimap();
    final Game without =
        MapXml.territories(minimap).stream()
            .filter(t -> MapXml.owner(minimap, t).equals(Optional.of("Italians")))
            .reduce(minimap, (g, t) -> MapXml.withOwner(g, t, null), (a, b) -> b);
    return new MapProject(Path.of("minimap.xml"), without, MapFiles.none());
  }

  /** The window's map, which it keeps to itself. */
  private static MapView givenMap(final MapMakerWindow window) {
    try {
      final Field map = MapMakerWindow.class.getDeclaredField("map");
      map.setAccessible(true);
      return (MapView) map.get(window);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
