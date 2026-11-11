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
import eddies.mapeditor.MapGuide.Part;
import eddies.mapeditor.MapGuide.Step;

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
}
