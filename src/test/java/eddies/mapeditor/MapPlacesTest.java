package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.triplea.attachments.TerritoryAttachment;
import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.util.PointFileReaderWriter;
import eddies.mapeditor.MapFiles.Marker;

final class MapPlacesTest {
  // a territory of MapPictureTest.givenPicture, as Make territories names it
  private static final String TERRITORY = "Territory 1";
  private static final String SEA = "Sea Zone 1";
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
    project.setMarkerPlace(Marker.NAME, TERRITORY, 0, new Point(3, 4));
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

    project.setMarkerPlace(Marker.VALUE, TERRITORY, 0, place);
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

  @DisplayName("A sea zone's kamikaze marker and each of its effects are placed and saved")
  @Test
  void savesKamikazeAndEffectPlaces() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();
    project.edit(MapPlacesTest::givenKamikazeZoneWithEffects);
    final Path convoys = project.getFolder().orElseThrow().resolve("convoy.txt");
    Files.writeString(convoys, TERRITORY + " (9,9)");

    project.setMarkerPlace(Marker.KAMIKAZE, SEA, 0, new Point(30, 31));
    project.setMarkerPlace(Marker.EFFECT, SEA, 1, new Point(40, 41));
    project.save();

    final Path folder = project.getFolder().orElseThrow();
    assertThat(Marker.KAMIKAZE.isOn(project.game(), SEA)).as("the game draws it").isTrue();
    assertThat(Marker.EFFECT.count(project.game(), SEA)).as("one per effect").isEqualTo(2);
    assertThat(PointFileReaderWriter.readOneToOne(folder.resolve("kamikaze_place.txt")))
        .as("the moved kamikaze marker, in a file of its own")
        .containsExactly(Map.entry(SEA, new Point(30, 31)));
    assertThat(PointFileReaderWriter.readOneToMany(folder.resolve("territory_effects.txt")))
        .as("the first effect at the center, the second where it was moved to")
        .containsEntry(
            SEA,
            List.of(
                project.getFiles().markerPlace(project.game(), Marker.EFFECT, SEA),
                new Point(40, 41)));
    assertThat(PointFileReaderWriter.readOneToOne(convoys))
        .as("a convoy marker set by hand stays, as triggers may make a convoy route")
        .containsEntry(TERRITORY, new Point(9, 9));
    assertThat(folder.resolve("blockade.txt")).as("no file the map does not use").doesNotExist();
  }

  @DisplayName("Found places of a territory's effects lie side by side, one per effect")
  @Test
  void spreadsFoundEffects() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();

    project.edit(MapPlacesTest::givenKamikazeZoneWithEffects);

    assertThat(project.getFiles().markerPlaces(project.game(), Marker.EFFECT, SEA))
        .as("two effects, two places apart, so each can be seen and moved")
        .hasSize(2)
        .doesNotHaveDuplicates();
  }

  /** The sea zone as a kamikaze zone with two territory effects. */
  private static Game givenKamikazeZoneWithEffects(final Game game) {
    final MapXml.AttachmentKey sea = seaAttachment();
    return MapXml.withOption(
        MapXml.withOption(game, sea, "kamikazeZone", "true"),
        sea,
        "territoryEffect",
        "Forest:Mountain");
  }

  private static MapXml.AttachmentKey seaAttachment() {
    return new MapXml.AttachmentKey(
        "territoryAttachment", TerritoryAttachment.class.getName(), "territory", SEA);
  }

  @DisplayName("Placing and taking off decorations are steps; decorations.txt keeps other pictures")
  @Test
  void savesDecorations() throws Exception {
    final MapProject opened = givenMapWithRocks();
    final Path decorations = decorationsTxt(opened);

    opened.setDecorations("tree.png", List.of(new Point(5, 5), new Point(6, 6)));
    opened.setDecorations("rock.png", List.of());
    opened.undo();
    opened.save();

    assertThat(opened.getFiles().places().decorations().get("rock.png"))
        .as("taking the rocks off undone")
        .containsExactly(new Point(1, 1), new Point(2, 2));
    assertThat(PointFileReaderWriter.readOneToMany(decorations))
        .as("the trees placed, the rocks as they were")
        .containsEntry("tree.png", List.of(new Point(5, 5), new Point(6, 6)))
        .containsEntry("rock.png", List.of(new Point(1, 1), new Point(2, 2)));
    assertThat(decorations.resolveSibling("decorations.txt.bak"))
        .as("the first version kept")
        .hasContent("rock.png (1,1) (2,2)");
  }

  /** A saved map, opened again, whose decorations.txt places rocks. */
  private MapProject givenMapWithRocks() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();
    Files.writeString(decorationsTxt(project), "rock.png (1,1) (2,2)");
    return MapProject.open(project.getXmlFile().orElseThrow());
  }

  private static Path decorationsTxt(final MapProject project) {
    return project.getFolder().orElseThrow().resolve("decorations.txt");
  }

  @DisplayName("Decorations taken off the map are gone from decorations.txt")
  @Test
  void removesDecorations() throws Exception {
    final MapProject project = givenMapWithRocks();

    project.setDecorations("rock.png", List.of());
    project.save();

    assertThat(decorationsTxt(project)).as("no picture left, no file left").doesNotExist();
  }

  @DisplayName("A comment's place follows the name a map maker moved, a line above it")
  @Test
  void commentFollowsMovedName() throws Exception {
    final MapProject project = givenOpenedMapWithComments();
    project.edit(MapPlacesTest::givenConvoyRoute);
    final Point name = new Point(150, 200);

    project.setMarkerPlace(Marker.NAME, SEA, 0, name);
    project.save();

    assertThat(PointFileReaderWriter.readOneToOne(commentsTxt(project)).get(SEA))
        .as("above the moved name, at its left end")
        .satisfies(comment -> assertThat(comment.x).isEqualTo(name.x))
        .satisfies(comment -> assertThat(comment.y).isLessThan(name.y));
  }

  /** A saved map, opened again, whose comments.txt places a comment of another sea zone. */
  private MapProject givenOpenedMapWithComments() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();
    Files.writeString(commentsTxt(project), "Atlantis (5,6)");
    return MapProject.open(project.getXmlFile().orElseThrow());
  }

  private static Path commentsTxt(final MapProject project) {
    return project.getFolder().orElseThrow().resolve("comments.txt");
  }

  /** The sea zone as a convoy route, which the game comments on. */
  private static Game givenConvoyRoute(final Game game) {
    return MapXml.withOption(game, seaAttachment(), "convoyRoute", "true");
  }
}
