package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapChecks.Problem;

/** A problem or note the map maker means so is marked "Fine as it is" and counts no more. */
final class FineAsItIsTest {
  private static final String APART = "Territory 4";
  private static final String NOT_CONNECTED = "Territory 4 is not connected to any territory";

  @TempDir Path folder;

  /** A saved map with a territory that has no neighbour, eg one only triggers use. */
  private MapProject givenMapWithTerritoryApart() throws Exception {
    final MapProject project =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), folder)
            .project();
    project.edit(
        game -> {
          Game apart = game;
          for (final String neighbour : MapXml.neighbors(game, APART)) {
            apart = MapXml.withoutConnection(apart, APART, neighbour);
          }
          return apart;
        });
    project.save();
    return project;
  }

  private static Problem givenNote(final MapProject project) {
    return MapChecks.notes(project.game()).stream()
        .filter(note -> note.text().endsWith("is not connected to any territory"))
        .findFirst()
        .orElseThrow();
  }

  private static HomeEditor givenHome(final MapProject project) {
    return new HomeEditor(project, t -> {}, t -> {}, s -> {}, () -> {}, () -> {}, () -> {});
  }

  private static List<String> texts(final List<Problem> problems) {
    return problems.stream().map(Problem::text).toList();
  }

  private static Stream<AbstractButton> buttons(final Component in) {
    return in instanceof Container container
        ? Stream.concat(
            Stream.of(in).filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast),
            Stream.of(container.getComponents()).flatMap(FineAsItIsTest::buttons))
        : Stream.empty();
  }

  private static AbstractButton button(final HomeEditor home, final String name) {
    return buttons(home.page())
        .filter(b -> name.equals(b.getAccessibleContext().getAccessibleName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No button " + name));
  }

  @DisplayName("A note marked as fine is kept with the map and follows a rename")
  @Test
  void markIsKeptAndFollowsARename() throws Exception {
    // given
    final MapProject project = givenMapWithTerritoryApart();
    final Problem note = givenNote(project);

    // when
    project.markFine(note);
    final boolean fine = project.getFiles().isFine(note);
    project.rename(APART, "Clock");
    project.save();
    final MapProject again = MapProject.open(project.getXmlFile().orElseThrow());

    // then
    assertThat(note.text()).as("the note of the territory apart").isEqualTo(NOT_CONNECTED);
    assertThat(fine).as("marked").isTrue();
    assertThat(Files.readString(project.getFolder().orElseThrow().resolve("map.properties")))
        .as("kept in map.properties by its kind and territory, under the new name")
        .contains("guide.fine.notConnected.Clock=true")
        .doesNotContain("notConnected.Territory");
    assertThat(again.getFiles().isFine(givenNote(again))).as("opened again: still fine").isTrue();
    assertThat(MapChecks.findError(again.getXmlFile().orElseThrow()))
        .as("TripleA loads the map with the editor's key")
        .isEmpty();

    // when
    again.countAgain(givenNote(again));

    // then
    assertThat(again.getFiles().isFine(givenNote(again))).as("it counts again").isFalse();
    assertThat(again.canUndo()).as("a mark is a step").isTrue();
  }

  @DisplayName("A mark names its kind and all of its name: a nation and a territory of one name,"
      + " or a name that ends with another, keep theirs")
  @Test
  void marksOfOtherNamesStay() {
    // given marks of a nation and a territory both named China, and of N.Africa
    final MapFiles files =
        MapFiles.none()
            .withProperty(MapFiles.FINE + "noFlag.China", "true")
            .withProperty(MapFiles.FINE + "notConnected.China", "true")
            .withProperty(MapFiles.FINE + "notConnected.N.Africa", "true");

    // when
    final List<String> nationRenamed =
        files.withRenamedNation("China", "PRC").properties().stringPropertyNames().stream()
            .filter(key -> key.startsWith(MapFiles.FINE))
            .sorted()
            .toList();
    final List<String> territoryRenamed =
        files.withRenamed("Africa", "Afrika").properties().stringPropertyNames().stream()
            .filter(key -> key.startsWith(MapFiles.FINE))
            .sorted()
            .toList();
    final List<String> territoryChinaRenamed =
        files.withRenamed("China", "Zhongguo").properties().stringPropertyNames().stream()
            .filter(key -> key.startsWith(MapFiles.FINE))
            .sorted()
            .toList();

    // then
    assertThat(nationRenamed)
        .as("the nation's mark moves, the territory's of the same name stays")
        .containsExactly(
            "guide.fine.noFlag.PRC",
            "guide.fine.notConnected.China",
            "guide.fine.notConnected.N.Africa");
    assertThat(territoryRenamed)
        .as("Africa is not N.Africa: nothing moves")
        .containsExactly(
            "guide.fine.noFlag.China",
            "guide.fine.notConnected.China",
            "guide.fine.notConnected.N.Africa");
    assertThat(territoryChinaRenamed)
        .as("the territory's mark moves, the nation's stays")
        .containsExactly(
            "guide.fine.noFlag.China",
            "guide.fine.notConnected.N.Africa",
            "guide.fine.notConnected.Zhongguo");
  }

  @DisplayName("What keeps TripleA from loading or starting the game cannot be marked:"
      + " a territory without outline, an owner that is no nation")
  @Test
  void fatalProblemsCannotBeMarked() throws Exception {
    // given a territory the map's files have no outline of, and land of no nation
    final MapProject project = givenMapWithTerritoryApart();
    project.edit(game -> MapXml.withTerritory(game, "Dial", List.of("Territory 1")));
    project.edit(game -> MapXml.withOwner(game, "Territory 1", "Nobody"));
    final List<Problem> fatal = project.problems();
    final HomeEditor home = givenHome(project);

    // when
    fatal.forEach(project::markFine);

    // then
    assertThat(texts(fatal))
        .as("TripleA starts neither")
        .contains("Dial has no outline on the map")
        .anyMatch(text -> text.contains("no nation"));
    assertThat(fatal).as("no key to mark them by").allMatch(problem -> problem.key() == null);
    assertThat(project.problems()).as("they stay problems").isEqualTo(fatal);
    assertThat(buttons(home.page()).map(b -> b.getAccessibleContext().getAccessibleName()))
        .as("Home offers the mark for the note only")
        .contains("Fine as it is: " + NOT_CONNECTED)
        .noneMatch(name -> name != null && name.startsWith("Fine as it is: Dial"));
  }

  @DisplayName("A mark keeps Check's result: the guide does not ask for a Check again")
  @Test
  void markKeepsTheCheck() throws Exception {
    // given a map checked
    final MapProject project = givenMapWithTerritoryApart();
    final HomeEditor home = givenHome(project);
    home.setValidated(MapChecks.all(project.game(), project.getFiles()));
    final Problem note = givenNote(project);

    // when
    project.markFine(note);
    final boolean afterMark = home.checked();
    project.countAgain(note);

    // then
    assertThat(afterMark).as("the mark changes nothing Check looked at").isTrue();
    assertThat(home.checked()).as("nor does taking it back").isTrue();
  }

  @DisplayName("Home: Fine as it is takes a row away; Marked as fine lists it with Count again")
  @Test
  void homeMarksAndCountsAgain() throws Exception {
    // given
    final MapProject project = givenMapWithTerritoryApart();
    final HomeEditor home = givenHome(project);

    // when
    button(home, "Fine as it is: " + NOT_CONNECTED).doClick();
    // Home fills when it shows: here it is on no screen
    home.showing();

    // then
    assertThat(texts(home.notes())).as("no note of Home").doesNotContain(NOT_CONNECTED);
    assertThat(buttons(home.page()).map(AbstractButton::getText))
        .as("one fold row for what is marked")
        .contains("▸ Marked as fine (1)");

    // when
    buttons(home.page())
        .filter(b -> "▸ Marked as fine (1)".equals(b.getText()))
        .findFirst()
        .orElseThrow()
        .doClick();
    button(home, "Count again: " + NOT_CONNECTED).doClick();
    home.showing();

    // then
    assertThat(texts(home.notes())).as("a note again").contains(NOT_CONNECTED);
    assertThat(buttons(home.page()).map(AbstractButton::getText))
        .as("nothing marked, no row")
        .noneMatch(text -> text.contains("Marked as fine"));
  }
}
