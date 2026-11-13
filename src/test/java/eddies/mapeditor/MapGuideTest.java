package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.experimental.ExtensionMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapGuide.Next;
import eddies.mapeditor.MapGuide.Next.Kind;
import eddies.mapeditor.MapGuide.Part;
import eddies.mapeditor.MapGuide.Step;
import eddies.mapeditor.MapGuide.Step.State;

@ExtensionMethod(Builders.class)
final class MapGuideTest {
  @TempDir Path maps;

  private MapProject givenSavedMapWithPicture() throws Exception {
    return MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), maps)
        .project();
  }

  @DisplayName("The anatomy lists the map's files, which are there and which tool makes them")
  @Test
  void showsTheAnatomy() throws Exception {
    final MapProject project = givenSavedMapWithPicture();

    final List<Part> parts = MapGuide.anatomy(project);

    assertThat(parts)
        .as("the files the map maker writes are there")
        .filteredOn(Part::present)
        .extracting(Part::name)
        .contains("map.yml", "island_wars.xml", "polygons.txt", "place.txt", "map.properties");
    assertThat(parts)
        .as("what a map made here does not have yet")
        .filteredOn(part -> !part.present())
        .extracting(Part::name)
        .contains("reliefTiles", "tooltips.properties");
    assertThat(parts)
        .as("each part tells which tool makes it")
        .filteredOn(part -> part.name().equals("place.txt"))
        .extracting(Part::tool)
        .containsExactly("Territories");
    assertThat(parts)
        .as("map.properties and the relief are made in Map look")
        .filteredOn(part -> List.of("map.properties", "reliefTiles").contains(part.name()))
        .extracting(Part::tool)
        .containsOnly("Map look");
  }

  @DisplayName("A map keeping its files in map/ shows that folder, its files one level below")
  @Test
  void showsTheContentFolder() throws Exception {
    final MapProject project = givenMapKeepingItsFilesIn("map");

    final List<Part> parts = MapGuide.anatomy(project);

    assertThat(parts)
        .as("map.yml at the top, the content folder and its files below it")
        .filteredOn(part -> List.of("map.yml", "map/", "polygons.txt").contains(part.name()))
        .extracting(Part::name, Part::depth, Part::present)
        .containsExactly(
            tuple("map.yml", 0, true), tuple("map/", 0, true), tuple("polygons.txt", 1, true));
  }

  /** A saved map with all but map.yml moved into the folder, as modern maps keep them. */
  private MapProject givenMapKeepingItsFilesIn(final String folder) throws Exception {
    final Path root = givenSavedMapWithPicture().getFolder().orElseThrow();
    final Path content = Files.createDirectory(root.resolve(folder));
    try (Stream<Path> files = Files.list(root)) {
      for (final Path file :
          files
              .filter(f -> !f.equals(content) && !f.getFileName().toString().equals("map.yml"))
              .toList()) {
        Files.move(file, content.resolve(file.getFileName()));
      }
    }
    return MapProject.open(content.resolve("games/island_wars.xml"));
  }

  @DisplayName("The next steps are done as the map grows: territories and nations, not yet land")
  @Test
  void followsTheSteps() throws Exception {
    final MapProject project = givenSavedMapWithPicture();

    final List<Step> steps = MapGuide.steps(project);

    assertThat(steps)
        .as("territories found and a nation added; no land, units or Check yet")
        .extracting(Step::done)
        .containsExactly(true, true, false, false, false, true, false);
  }

  @DisplayName("A step not done yet shows as such; only land and capitals fail once nations exist")
  @Test
  void stepsFailOnlyWhenDue() throws Exception {
    final MapProject project = givenSavedMapWithPicture();
    final List<State> before = MapGuide.steps(project).stream().map(Step::state).toList();

    project.edit(g -> MapXml.withOwner(g, givenLand(project), "Red"));
    final List<State> owning = MapGuide.steps(project).stream().map(Step::state).toList();

    assertThat(before)
        .as("land fails as Red has none; no capital is due while no nation owns land; no Check yet")
        .containsExactly(
            State.DONE,
            State.DONE,
            State.FAILING,
            State.TODO,
            State.TODO,
            State.DONE,
            State.TODO);
    assertThat(owning)
        .as("once Red owns land, its missing capital fails")
        .containsExactly(
            State.DONE,
            State.DONE,
            State.DONE,
            State.FAILING,
            State.TODO,
            State.DONE,
            State.TODO);
  }

  @DisplayName("The next step leads through the tools, the problems and Check to Save and Play")
  @Test
  void leadsToTheNextStep() throws Exception {
    final MapProject project = givenSavedMapWithPicture();
    final Next first = MapGuide.nextStep(project, List.of());
    givenAllStepsDone(project);

    final List<Kind> kinds =
        List.of(
            first.kind(),
            MapGuide.nextStep(project, List.of(Problem.of("Something is wrong"))).kind(),
            MapGuide.nextStep(project, List.of()).kind(),
            MapGuide.nextStepAfterCheck(project, List.of()).kind(),
            savedAndChecked(project).kind());

    assertThat(kinds)
        .as("a step not done, then problems, Check, Save and at last Play")
        .containsExactly(Kind.GO_TO_TOOL, Kind.FIX_PROBLEMS, Kind.CHECK, Kind.SAVE, Kind.PLAY);
    assertThat(first.tool()).as("Red's land is given in Territories").isEqualTo("Territories");
  }

  private static Next savedAndChecked(final MapProject project) throws Exception {
    project.save();
    return MapGuide.nextStepAfterCheck(project, List.of());
  }

  /** Red owns a territory with its capital and a unit there, not saved. */
  private static void givenAllStepsDone(final MapProject project) {
    final String land = givenLand(project);
    project.edit(g -> MapXml.withOwner(g, land, "Red"));
    project.edit(g -> MapXml.withCapital(g, land, "Red"));
    project.edit(g -> MapXml.withUnits(g, land, "Red", MapXml.unitTypes(g).getFirst(), 1));
  }

  private static String givenLand(final MapProject project) {
    return MapXml.territories(project.game()).stream()
        .filter(t -> !MapXml.isWater(project.game(), t))
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("Each step says how far it is in numbers and has an id of its own")
  @Test
  void stepsSayHowFarTheyAre() throws Exception {
    // given
    final MapProject project = givenSavedMapWithPicture();
    project.edit(g -> MapXml.withOwner(g, givenLand(project), "Red"));

    // when
    final List<Step> steps = MapGuide.steps(project);

    // then
    assertThat(steps)
        .as("one step per id, in the order of the ids")
        .extracting(Step::id)
        .containsExactly(Step.Id.values());
    assertThat(steps)
        .extracting(Step::progress)
        .as("Red owns land without a capital; no units; Check has no numbers")
        .containsExactly(
            MapXml.territories(project.game()).size() + " territories",
            "1 nation",
            "1 of 1 nation has land",
            "0 of 1 nation with land has a capital · without: Red",
            "No territory has units yet",
            MapXml.connections(project.game()).size() + " connections",
            "");
  }

  @DisplayName("The nations without land are those taking turns that own none, in their order")
  @Test
  void findsNationsWithoutLand() {
    // given
    final Game minimap = MapXmlTest.givenMinimap();
    final Game russiansWithout =
        MapXml.territories(minimap).stream()
            .filter(t -> MapXml.owner(minimap, t).equals(Optional.of("Russians")))
            .reduce(minimap, (g, t) -> MapXml.withOwner(g, t, null), (a, b) -> b);

    // when
    final List<String> without = MapGuide.withoutLand(russiansWithout);

    // then
    assertThat(MapGuide.withoutLand(minimap)).as("every playing nation owns land").isEmpty();
    assertThat(without).as("Russians gave all their land away").containsExactly("Russians");
  }

  @DisplayName("Go to carries the step, so the tool can show it as the goal")
  @Test
  void nextCarriesTheStep() throws Exception {
    // given
    final MapProject project = givenSavedMapWithPicture();

    // when
    final Next next = MapGuide.nextStep(project, List.of());

    // then
    assertThat(next.step()).as("Red has no land yet").contains(Step.Id.LAND);
    assertThat(MapGuide.nextStep(project, List.of(Problem.of("Something is wrong"))).step())
        .as("land comes before the problems")
        .contains(Step.Id.LAND);
  }

  @DisplayName("A step's progress says what is missing, never 0 of 0")
  @Test
  void progressSaysWhatIsMissing() throws Exception {
    // given
    final MapProject project = givenSavedMapWithPicture();

    // when
    final List<String> before = MapGuide.steps(project).stream().map(Step::progress).toList();
    project.addNation("Blue");
    project.edit(g -> MapXml.withOwner(g, givenLand(project), "Red"));
    final List<String> owning = MapGuide.steps(project).stream().map(Step::progress).toList();

    // then
    assertThat(before)
        .as("Red has no land, so no capital is due yet; no units anywhere")
        .contains(
            "0 of 1 nation has land · without: Red",
            "No nation has land yet",
            "No territory has units yet")
        .noneMatch(text -> text.contains("0 of 0"));
    assertThat(owning)
        .as("who is still without land, and whose capital is missing")
        .contains(
            "1 of 2 nations have land · without: Blue",
            "0 of 1 nation with land has a capital · without: Red");
  }

  @DisplayName("Check is not ticked ahead of the steps before it")
  @Test
  void checkIsNotDoneAheadOfOpenSteps() throws Exception {
    final MapProject project = givenSavedMapWithPicture();

    final List<Step> steps = MapGuide.stepsAfterCleanCheck(project);

    assertThat(steps.getLast().done())
        .as("the game loads, but Red has no land yet: the list reads top to bottom")
        .isFalse();
    assertThat(MapGuide.firstOpen(steps).map(Step::id))
        .as("the next step is the first open one in the list's order")
        .contains(Step.Id.LAND);
  }

  @DisplayName("After a clean Check a nation left without land or capital is meant so: both steps"
      + " count as done, so a published map's guide does not stay open")
  @Test
  void cleanCheckTakesLandlessNationAsMeant() throws Exception {
    // given: Red has land, a capital and units; Blue, eg a neutral, has nothing
    final MapProject project = givenSavedMapWithPicture();
    givenAllStepsDone(project);
    project.addNation("Blue");

    // when
    final List<Step> quick = MapGuide.steps(project);
    final List<Step> checked = MapGuide.stepsAfterCleanCheck(project);

    // then
    assertThat(quick.stream().filter(s -> s.id() == Step.Id.LAND).map(Step::state))
        .as("before a Check, Blue without land keeps the step open")
        .containsExactly(State.FAILING);
    assertThat(checked.stream().filter(s -> s.id() != Step.Id.CHECK))
        .as("TripleA loads the game and Red has its land and capital: the map is built")
        .allMatch(Step::done);
    assertThat(checked.stream().filter(s -> s.id() == Step.Id.LAND).map(Step::progress))
        .as("the step still says who is without")
        .containsExactly("1 of 2 nations have land · without: Blue");
    assertThat(MapGuide.nextStepAfterCheck(project, List.of()).kind())
        .as("the next step is no longer Blue's land")
        .isEqualTo(Kind.SAVE);
    assertThat(MapGuide.nextStepAfterCheck(project, List.of(Problem.of("Wrong"))).kind())
        .as("a Check that found problems proves nothing")
        .isEqualTo(Kind.GO_TO_TOOL);
  }

  @DisplayName("Nations that take no turns are not called missing by the land step")
  @Test
  void landStepWithoutTurnsSaysSo() {
    // given: a game with a nation, but no turn order steps
    final Game minimap = MapXmlTest.givenMinimap();
    final Game withoutTurns =
        minimap.toBuilder()
            .gamePlay(minimap.getGamePlay().toBuilder().sequence(null).build())
            .build();
    final MapProject project =
        new MapProject(Path.of("minimap.xml"), withoutTurns, MapFiles.none());

    // when
    final Step land =
        MapGuide.steps(project).stream()
            .filter(s -> s.id() == Step.Id.LAND)
            .findFirst()
            .orElseThrow();

    // then
    assertThat(land.progress())
        .as("the game has nations: not \"No nations yet\"")
        .isEqualTo("No nation takes turns yet");
  }
}
