package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import eddies.mapeditor.MapChecks.Problem;

/** A problem or note the map maker means so is marked "Fine as it is" and counts no more. */
final class FineAsItIsTest {
  private static final String DIAL = "Dial";
  private static final String NO_OUTLINE = "Dial has no outline on the map";

  @TempDir Path folder;
  private final Preferences preferences =
      Preferences.userRoot().node("eddies-map-editor-test-" + UUID.randomUUID());

  @BeforeEach
  void givenFoldsClosed() {
    Folds.keepIn(preferences);
  }

  @AfterEach
  void removePreferences() throws Exception {
    preferences.removeNode();
    Folds.keepIn(Preferences.userNodeForPackage(Folds.class));
  }

  /** A saved map with a territory that has no outline, eg a dial that only triggers use. */
  private MapProject givenMapWithDial() throws Exception {
    final MapProject project =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), folder)
            .project();
    project.edit(game -> MapXml.withTerritory(game, DIAL, List.of("Territory 1")));
    project.save();
    return project;
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

  @DisplayName("A problem marked as fine counts nowhere, is kept with the map and follows a rename")
  @Test
  void markedProblemCountsNoMore() throws Exception {
    // given
    final MapProject project = givenMapWithDial();
    final Problem problem =
        project.problems().stream().filter(p -> p.text().equals(NO_OUTLINE)).findFirst().orElseThrow();

    // when
    project.markFine(problem);
    final List<String> quick = texts(project.problems());
    final List<String> checked = texts(MapChecks.all(project.game(), project.getFiles()));
    project.rename(DIAL, "Clock");
    project.save();
    final MapProject again = MapProject.open(project.getXmlFile().orElseThrow());

    // then
    assertThat(quick).as("not among the quick problems").doesNotContain(NO_OUTLINE);
    assertThat(checked).as("nor among Check's").doesNotContain(NO_OUTLINE);
    assertThat(Files.readString(project.getFolder().orElseThrow().resolve("map.properties")))
        .as("kept in map.properties by its kind and territory, under the new name")
        .contains("guide.fine.noOutline.Clock=true")
        .doesNotContain("noOutline.Dial");
    assertThat(texts(again.problems())).as("opened again: still fine").noneMatch(t -> t.contains("outline"));
    assertThat(texts(again.fineProblems()))
        .as("and listed as marked")
        .containsExactly("Clock has no outline on the map");
    assertThat(MapChecks.findError(again.getXmlFile().orElseThrow()))
        .as("TripleA loads the map with the editor's key")
        .isEmpty();

    // when
    again.countAgain(again.fineProblems().getFirst());

    // then
    assertThat(texts(again.problems())).as("it counts again").contains("Clock has no outline on the map");
    assertThat(again.fineProblems()).as("nothing marked").isEmpty();
  }

  @DisplayName("What keeps TripleA from loading or starting the game cannot be marked")
  @Test
  void fatalProblemsCannotBeMarked() throws Exception {
    // given a territory of a nation the game does not have
    final MapProject project = givenMapWithDial();
    project.edit(game -> MapXml.withOwner(game, "Territory 1", "Nobody"));
    final Problem fatal =
        project.problems().stream().filter(p -> p.text().contains("no nation")).findFirst().orElseThrow();
    final HomeEditor home =
        new HomeEditor(project, t -> {}, t -> {}, s -> {}, () -> {}, () -> {}, () -> {});

    // when
    project.markFine(fatal);

    // then
    assertThat(fatal.key()).as("no key to mark it by").isNull();
    assertThat(project.problems()).as("it stays a problem").contains(fatal);
    assertThat(buttons(home.page()).map(b -> b.getAccessibleContext().getAccessibleName()))
        .as("Home offers the mark for the outline only")
        .contains("Fine as it is: " + NO_OUTLINE)
        .doesNotContain("Fine as it is: " + fatal.text());
  }

  @DisplayName("A mark keeps Check's result: the guide does not ask for a Check again")
  @Test
  void markKeepsTheCheck() throws Exception {
    // given a map checked, with the outline as its one problem
    final MapProject project = givenMapWithDial();
    final HomeEditor home =
        new HomeEditor(project, t -> {}, t -> {}, s -> {}, () -> {}, () -> {}, () -> {});
    home.setValidated(MapChecks.all(project.game(), project.getFiles()));
    final Problem outline = home.problems().getFirst();

    // when
    project.markFine(outline);
    final boolean afterMark = home.checked();
    final List<String> marked = texts(home.problems());
    project.countAgain(outline);

    // then
    assertThat(afterMark).as("the mark changes nothing Check looked at").isTrue();
    assertThat(marked).as("Check's problem is fine now").doesNotContain(NO_OUTLINE);
    assertThat(home.checked()).as("nor does taking it back").isTrue();
    assertThat(texts(home.problems())).as("which counts it again").contains(NO_OUTLINE);
  }

  @DisplayName("Home: Fine as it is takes a row away; Marked as fine lists it with Count again")
  @Test
  void homeMarksAndCountsAgain() throws Exception {
    // given
    final MapProject project = givenMapWithDial();
    final HomeEditor home =
        new HomeEditor(project, t -> {}, t -> {}, s -> {}, () -> {}, () -> {}, () -> {});

    // when
    button(home, "Fine as it is: " + NO_OUTLINE).doClick();
    // Home fills when it shows: here it is on no screen
    home.showing();

    // then
    assertThat(texts(home.problems())).as("no problem of Home").doesNotContain(NO_OUTLINE);
    assertThat(buttons(home.page()).map(AbstractButton::getText))
        .as("one fold row for what is marked")
        .contains("▸ Marked as fine (1)");

    // when
    buttons(home.page())
        .filter(b -> "▸ Marked as fine (1)".equals(b.getText()))
        .findFirst()
        .orElseThrow()
        .doClick();
    button(home, "Count again: " + NO_OUTLINE).doClick();
    home.showing();

    // then
    assertThat(texts(home.problems())).as("a problem again").contains(NO_OUTLINE);
    assertThat(buttons(home.page()).map(AbstractButton::getText))
        .as("nothing marked, no row")
        .noneMatch(text -> text.contains("Marked as fine"));
  }
}
