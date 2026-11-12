package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapGuide.Next;
import eddies.mapeditor.MapGuide.Next.Kind;
import eddies.mapeditor.MapGuide.Part;
import eddies.mapeditor.MapGuide.Step;
import eddies.mapeditor.MapGuide.Step.State;

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
}
