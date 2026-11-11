package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.util.PointFileReaderWriter;
import eddies.mapeditor.MapFiles.Marker;

final class MapPlacesTest {
  // a territory of MapPictureTest.givenPicture, as Find territories names it
  private static final String TERRITORY = "Territory 1";
  private static final String UNKNOWN_LINE = "Atlantis (5,6) | overflowToLeft=true";

  @TempDir Path folder;

  /** A map made from the picture and saved, opened again, with a line in place.txt it lacks. */
  private MapProject givenSavedMapWithUnknownPlace() throws Exception {
    final MapProject made =
        MapProject.newMap(
                "Island Wars",
                MapPictureTest.givenPicture(),
                List.of("Red"),
                folder.resolve("maps"))
            .project();
    final Path places = placeTxt(made);
    Files.writeString(places, Files.readString(places) + "\r\n" + UNKNOWN_LINE);
    return MapProject.open(made.getXmlFile().orElseThrow());
  }

  private static Path placeTxt(final MapProject project) {
    return project.getFolder().orElseThrow().resolve("place.txt");
  }

  @DisplayName("A moved unit place is saved over place.txt, keeping its first version as .bak")
  @Test
  void savesMovedUnitPlace() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();
    final String before = Files.readString(placeTxt(project));
    final List<Point> places = project.getFiles().unitPlaces(TERRITORY);
    final Point moved = new Point(places.getFirst().x + 7, places.getFirst().y + 3);

    project.setUnitPlaces(TERRITORY, List.of(moved));
    project.save();

    assertThat(
            PointFileReaderWriter.readOneToManyPlacements(placeTxt(project))
                .get(TERRITORY)
                .getFirst())
        .as("the territory's one place, moved")
        .containsExactly(moved);
    assertThat(PointFileReaderWriter.readOneToManyPlacements(placeTxt(project)))
        .as("the line of a territory the map does not have stays")
        .containsKey("Atlantis");
    assertThat(placeTxt(project).resolveSibling("place.txt.bak"))
        .as("the file as it was before the save")
        .hasContent(before);
    assertThat(MapProject.open(project.getXmlFile().orElseThrow()).getFiles().unitPlaces(TERRITORY))
        .as("opened again, the place is there")
        .containsExactly(moved);
  }

  @DisplayName("A save without moved places leaves the files of places as they are")
  @Test
  void savesNoUnchangedPlaces() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();
    project.edit(game -> MapXml.withProduction(game, TERRITORY, 7));

    project.save();

    assertThat(placeTxt(project).resolveSibling("place.txt.bak"))
        .as("place.txt was not written again")
        .doesNotExist();
  }

  @DisplayName("Moving, adding, removing and finding places again are one step each")
  @Test
  void placesAreUndoSteps() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();
    final List<Point> found = project.getFiles().unitPlaces(TERRITORY);
    final Point added = new Point(1, 2);

    project.setUnitPlaces(TERRITORY, List.of(found.getFirst(), added));
    project.setMarkerPlace(Marker.NAME, TERRITORY, new Point(3, 4));
    project.findPlacesAgain(TERRITORY);
    project.undo();

    assertThat(project.getFiles().unitPlaces(TERRITORY))
        .as("found again and undone: the places as set")
        .containsExactly(found.getFirst(), added);
    assertThat(project.getFiles().markerPlace(project.game(), Marker.NAME, TERRITORY))
        .as("the moved name")
        .isEqualTo(new Point(3, 4));
    project.undo();
    project.undo();
    assertThat(project.getFiles().unitPlaces(TERRITORY))
        .as("two more undos: the places as opened")
        .isEqualTo(found);
    assertThat(project.hasUnsavedChanges()).as("back to the saved map").isFalse();
    project.setUnitPlaces(TERRITORY, List.of());
    assertThat(project.canUndo()).as("taking the last place away is refused").isFalse();
  }

  @DisplayName("A renamed territory keeps its places and saves them under its new name")
  @Test
  void renameCarriesPlaces() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();
    final Point place = new Point(11, 12);
    project.setUnitPlaces(TERRITORY, List.of(place));

    project.rename(TERRITORY, "Avalon");
    project.save();

    assertThat(project.getFiles().unitPlaces("Avalon")).as("in the model").containsExactly(place);
    assertThat(PointFileReaderWriter.readOneToManyPlacements(placeTxt(project)))
        .as("in place.txt under the new name only")
        .containsKey("Avalon")
        .doesNotContainKey(TERRITORY);
  }

  @DisplayName("A moved value is saved in pu_place.txt also on a map without name places")
  @Test
  void savesValuePlaceWithoutNamePlaces() throws Exception {
    final MapProject project = givenMapWithoutNamePlaces();
    final Point place = new Point(21, 22);

    project.setMarkerPlace(Marker.VALUE, TERRITORY, place);
    project.save();

    final Path folder = project.getFolder().orElseThrow();
    assertThat(PointFileReaderWriter.readOneToOne(folder.resolve("pu_place.txt")))
        .as("the value where it was moved to")
        .containsEntry(TERRITORY, place);
    assertThat(folder.resolve("name_place.txt"))
        .as("the game still places the names itself")
        .doesNotExist();
  }

  /** A saved map without name_place.txt and pu_place.txt, opened, whose territory gives PUs. */
  private MapProject givenMapWithoutNamePlaces() throws Exception {
    final MapProject made = givenSavedMapWithUnknownPlace();
    final Path folder = made.getFolder().orElseThrow();
    Files.delete(folder.resolve("name_place.txt"));
    Files.deleteIfExists(folder.resolve("pu_place.txt"));
    final MapProject opened = MapProject.open(made.getXmlFile().orElseThrow());
    opened.edit(game -> MapXml.withProduction(game, TERRITORY, 3));
    return opened;
  }

  @DisplayName("A place in a file set by hand after opening stays when the save changes no place")
  @Test
  void keepsPlaceSetByHandMeanwhile() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();
    final String byHand = "Territory 1  (1,2)  | overflowToLeft=false";
    final String file = Files.readString(placeTxt(project));
    Files.writeString(placeTxt(project), file.replaceFirst("Territory 1 [^\\r\\n]*", byHand));
    project.edit(game -> MapXml.withProduction(game, TERRITORY, 7));

    project.save();

    assertThat(
            PointFileReaderWriter.readOneToManyPlacements(placeTxt(project))
                .get(TERRITORY)
                .getFirst())
        .as("the place set by hand")
        .containsExactly(new Point(1, 2));
  }
}
