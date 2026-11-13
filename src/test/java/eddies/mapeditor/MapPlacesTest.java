package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.triplea.attachments.TerritoryAttachment;
import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
  private static final String NAME_BY_HAND = "Territory 2 (1,2)\r\n";
  private static final String CAPITALS_BY_HAND = "Territory 1 (10, 20)\nTerritory 2   (30,40)\n";

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

  @DisplayName("A moved center is one step and saved over centers.txt, keeping its .bak")
  @Test
  void savesMovedCenter() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();
    final Path centers = placeTxt(project).resolveSibling("centers.txt");
    final String before = Files.readString(centers);
    final Point moved = new Point(11, 12);

    project.setCenter(TERRITORY, moved);
    project.save();

    assertThat(PointFileReaderWriter.readOneToOne(centers).get(TERRITORY))
        .as("the territory's center, moved")
        .isEqualTo(moved);
    assertThat(centers.resolveSibling("centers.txt.bak"))
        .as("the file as it was before the save")
        .hasContent(before);
    project.undo();
    assertThat(project.getFiles().centers().get(TERRITORY))
        .as("undone: the center as opened")
        .isNotEqualTo(moved);
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

  @DisplayName("Laying out several territories' unit places is one step, with their overflow")
  @Test
  void layOutIsOneStep() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();
    final List<String> territories = List.of(TERRITORY, SEA);
    final Map<String, List<Point>> before =
        Map.of(
            TERRITORY, project.getFiles().unitPlaces(TERRITORY),
            SEA, project.getFiles().unitPlaces(SEA));

    project.layOutUnitPlaces(MapFiles.UnitLayout.GRID, 16, territories);
    final MapFiles laidOut = project.getFiles();
    project.undo();

    assertThat(territories)
        .as("both laid out anew")
        .allMatch(t -> !laidOut.unitPlaces(t).equals(before.get(t)));
    assertThat(territories)
        .as("each with the overflow its layout chose")
        .allMatch(
            t ->
                laidOut.places().units().get(t).equals(
                    laidOut.unitPlacesLaidOut(MapFiles.UnitLayout.GRID, 16, t)));
    assertThat(territories)
        .as("one undo takes back both")
        .allMatch(t -> project.getFiles().unitPlaces(t).equals(before.get(t)));
    assertThat(project.canUndo()).as("it was a single step").isFalse();
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

  /**
   * A map as published ones are, opened: marker files written by hand, with places the game does
   * not draw (Territory 2 is no capital and gives no PUs, the game has no victory city), an empty
   * convoy.txt and no name_place.txt. Territory 1 gives 3 PUs.
   */
  private MapProject givenPublishedMap() throws Exception {
    final MapProject made = givenSavedMapWithUnknownPlace();
    final Path mapFolder = made.getFolder().orElseThrow();
    made.edit(game -> MapXml.withProduction(game, TERRITORY, 3));
    made.save();
    Files.delete(mapFolder.resolve("name_place.txt"));
    Files.writeString(mapFolder.resolve("capitols.txt"), CAPITALS_BY_HAND);
    Files.writeString(mapFolder.resolve("pu_place.txt"), "Territory 2 (1,2)\r\nAtlantis (3,4)");
    Files.writeString(mapFolder.resolve("vc.txt"), "Territory 1 (5,6)\n");
    Files.writeString(mapFolder.resolve("convoy.txt"), "");
    return MapProject.open(made.getXmlFile().orElseThrow());
  }

  /** The files right in the map's folder with their bytes, eg every file of places. */
  private static Map<String, List<Byte>> filesOf(final MapProject project) throws Exception {
    final Map<String, List<Byte>> files = new LinkedHashMap<>();
    try (Stream<Path> found = Files.list(project.getFolder().orElseThrow())) {
      for (final Path file : found.filter(Files::isRegularFile).sorted().toList()) {
        final List<Byte> bytes = new ArrayList<>();
        for (final byte b : Files.readAllBytes(file)) {
          bytes.add(b);
        }
        files.put(file.getFileName().toString(), bytes);
      }
    }
    return files;
  }

  @DisplayName("A save that changes no place leaves every file of places as it is, byte for byte")
  @Test
  void savesOnlyTheGameWhenNoPlaceChanged() throws Exception {
    final MapProject project = givenPublishedMap();
    final Map<String, List<Byte>> before = filesOf(project);

    project.edit(game -> MapXml.withProduction(game, TERRITORY, 7));
    project.save();

    assertThat(filesOf(project))
        .as(
            "no place file written, made or removed, and no .bak: places the game does not"
                + " draw and the empty file stay, the changed value has no line and gets none")
        .isEqualTo(before);
  }

  @DisplayName("A marker drawn only since a change made here gets one new line, the rest stays")
  @Test
  void markerThatBecomesDrawnGetsALine() throws Exception {
    final MapProject project = givenPublishedMap();
    final Path mapFolder = project.getFolder().orElseThrow();
    final String values = Files.readString(mapFolder.resolve("pu_place.txt"));

    project.edit(
        game ->
            MapXml.withVictoryCity(
                MapXml.withCapital(
                    MapXml.withProduction(game, "Territory 3", 1), "Territory 3", "Red"),
                "Territory 3"));
    project.save();

    final List<String> valueLines = Files.readAllLines(mapFolder.resolve("pu_place.txt"));
    assertThat(String.join("\r\n", valueLines.subList(0, 2)))
        .as("the lines of pu_place.txt as they were")
        .isEqualTo(values);
    assertThat(valueLines)
        .as("and exactly one new line, for the territory that gives PUs now")
        .hasSize(3)
        .last()
        .asString()
        .startsWith("Territory 3 ");
    assertThat(Files.readString(mapFolder.resolve("capitols.txt")))
        .as("the new capital's flag after the lines written by hand")
        .startsWith(CAPITALS_BY_HAND + "Territory 3 ");
    assertThat(Files.readAllLines(mapFolder.resolve("vc.txt")))
        .as("the new victory city after the line of one this game does not have")
        .hasSize(2)
        .first()
        .isEqualTo("Territory 1 (5,6)");
    assertThat(mapFolder.resolve("name_place.txt"))
        .as("no names: no territory is new")
        .doesNotExist();
  }

  @DisplayName("A moved marker changes only its line of its file; the other lines stay as written")
  @Test
  void movedMarkerChangesOnlyItsLine() throws Exception {
    final MapProject project = givenPublishedMap();
    final Path mapFolder = project.getFolder().orElseThrow();
    final Map<String, List<Byte>> before = filesOf(project);
    project.edit(game -> MapXml.withCapital(game, TERRITORY, "Red"));

    project.setMarkerPlace(Marker.CAPITAL, TERRITORY, 0, new Point(11, 12));
    project.save();

    assertThat(mapFolder.resolve("capitols.txt"))
        .as("the moved flag in its line, the line of Territory 2 as written by hand")
        .hasContent("Territory 1  (11,12) \nTerritory 2   (30,40)\n");
    assertThat(mapFolder.resolve("capitols.txt.bak"))
        .as("the file as it was before")
        .hasContent(CAPITALS_BY_HAND);
    final Map<String, List<Byte>> after = filesOf(project);
    after.keySet().removeAll(List.of("capitols.txt", "capitols.txt.bak"));
    before.remove("capitols.txt");
    assertThat(after).as("no other file touched").isEqualTo(before);
  }

  @DisplayName("A renamed territory keeps its lines under its new name and is not a new one")
  @Test
  void renamedTerritoryIsNotNew() throws Exception {
    final MapProject project = givenPublishedMap();
    final Path mapFolder = project.getFolder().orElseThrow();
    final String lineOfFirst = "Territory 1 (10, 20)";

    project.rename("Territory 2", "Avalon");
    project.save();

    assertThat(mapFolder.resolve("name_place.txt"))
        .as("no name placed: the map leaves that to the game, and the territory is not new")
        .doesNotExist();
    assertThat(Files.readAllLines(mapFolder.resolve("capitols.txt")))
        .as("the other line as written by hand")
        .contains(lineOfFirst);
    assertThat(PointFileReaderWriter.readOneToOne(mapFolder.resolve("capitols.txt")))
        .as("its flag's place under the new name only")
        .containsOnly(
            Map.entry(TERRITORY, new Point(10, 20)), Map.entry("Avalon", new Point(30, 40)));
    assertThat(PointFileReaderWriter.readOneToOne(mapFolder.resolve("pu_place.txt")))
        .as("its value's place too, beside the line of an unknown territory")
        .containsOnly(Map.entry("Avalon", new Point(1, 2)), Map.entry("Atlantis", new Point(3, 4)));
    assertThat(Files.readAllLines(mapFolder.resolve("vc.txt")))
        .as("a file without it is not touched")
        .containsExactly("Territory 1 (5,6)");
  }

  @DisplayName("A territory removed here loses its lines in the files of places; others stay")
  @Test
  void removedTerritoryLosesItsLines() throws Exception {
    final MapProject project = givenPublishedMap();
    final Path mapFolder = project.getFolder().orElseThrow();

    assertThat(project.removeTerritory("Territory 2")).as("nothing names it").isEmpty();
    project.save();

    assertThat(mapFolder.resolve("capitols.txt"))
        .as("its flag's line gone, the other as written")
        .hasContent("Territory 1 (10, 20)\n");
    assertThat(mapFolder.resolve("pu_place.txt"))
        .as("its value's line gone, the line of a territory the map does not have stays")
        .hasContent("Atlantis (3,4)");
    assertThat(mapFolder.resolve("vc.txt.bak"))
        .as("vc.txt, without it, not written")
        .doesNotExist();
  }

  @DisplayName("Saving one game keeps the places another game of the same map needs")
  @Test
  void savingOneGameKeepsTheOthersPlaces() throws Exception {
    final MapProject first = givenSavedMapWithUnknownPlace();
    final Path capitals = first.getFolder().orElseThrow().resolve("capitols.txt");
    final MapProject second =
        MapProject.open(MapFolder.newGame(first.getXmlFile().orElseThrow(), "Second"));
    second.edit(game -> MapXml.withCapital(game, "Territory 2", "Red"));
    second.setMarkerPlace(Marker.CAPITAL, "Territory 2", 0, new Point(40, 41));
    second.save();

    final MapProject opened = MapProject.open(first.getXmlFile().orElseThrow());
    opened.edit(game -> MapXml.withCapital(game, TERRITORY, "Red"));
    opened.save();

    assertThat(MapXml.isCapital(opened.game(), "Territory 2"))
        .as("the first game has no capital there")
        .isFalse();
    assertThat(PointFileReaderWriter.readOneToOne(capitals))
        .as("the second game's flag stays where it was put, beside the first game's new one")
        .containsEntry("Territory 2", new Point(40, 41))
        .containsKey(TERRITORY);
  }

  @DisplayName("A new map, opened again as the wizard does, gets a place for a later capital")
  @Test
  void newMapGetsPlacesForLaterMarkers() throws Exception {
    final MapProject project = givenSavedMapWithUnknownPlace();
    final Path capitals = project.getFolder().orElseThrow().resolve("capitols.txt");
    assertThat(capitals).as("no capital yet, no file").doesNotExist();

    project.edit(game -> MapXml.withCapital(game, TERRITORY, "Red"));
    project.save();

    assertThat(PointFileReaderWriter.readOneToOne(capitals))
        .as("the flag's place found, as the game hangs it from the center without one")
        .containsOnlyKeys(TERRITORY);
    assertThat(
            PointFileReaderWriter.readOneToOne(
                project.getFolder().orElseThrow().resolve("name_place.txt")))
        .as("the first save placed every territory's name")
        .containsOnlyKeys(MapXml.territories(project.game()));
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

  /** A saved map opened again whose place.txt lacks a territory and whose names have one place. */
  private MapProject givenMapWithPlacesMissing() throws Exception {
    final MapProject made =
        MapProject.newMap(
                "Island Wars",
                MapPictureTest.givenPicture(),
                List.of("Red"),
                folder.resolve("maps"))
            .project();
    final Path places = placeTxt(made);
    Files.write(
        places,
        Files.readAllLines(places).stream()
            .filter(line -> !line.startsWith(TERRITORY + " "))
            .toList());
    Files.writeString(namePlaces(made), NAME_BY_HAND);
    return MapProject.open(made.getXmlFile().orElseThrow());
  }

  private static Path namePlaces(final MapProject project) {
    return project.getFolder().orElseThrow().resolve("name_place.txt");
  }

  @DisplayName("Missing places are created only where a territory has none, as one step")
  @Test
  void createsOnlyMissingPlaces() throws Exception {
    // given
    final MapProject project = givenMapWithPlacesMissing();
    final MapFiles files = project.getFiles();
    final Game game = project.game();
    final List<String> withoutUnits = files.withoutUnitPlaces();
    final List<String> withoutName = files.withoutMarkerPlace(game, Marker.NAME);

    // when
    project.editFiles(f -> f.withMissingUnitPlaces().withMissingMarkerPlaces(game, Marker.NAME));
    final MapFiles created = project.getFiles();

    // then
    assertThat(withoutUnits).as("the territory left out of place.txt").containsExactly(TERRITORY);
    assertThat(withoutName)
        .as("every territory but the one name_place.txt has")
        .containsExactly(TERRITORY, "Territory 3", "Territory 4", SEA);
    assertThat(created.withoutUnitPlaces()).as("no unit places missing now").isEmpty();
    assertThat(created.missingPlaces(game, "name_place.txt")).as("nor names").isZero();
    assertThat(created.places().units().get(TERRITORY).getFirst())
        .as("the places found for it")
        .isEqualTo(files.unitPlaces(TERRITORY));
    assertThat(created.places().markers(Marker.NAME).get("Territory 2"))
        .as("a place there stays")
        .containsExactly(new Point(1, 2));
    assertThat(created.places().units().get(SEA))
        .as("as do the unit places there")
        .isSameAs(files.places().units().get(SEA));
    project.undo();
    assertThat(project.getFiles()).as("one undo takes all back").isSameAs(files);
    assertThat(project.canUndo()).as("it was one step").isFalse();
  }

  @DisplayName("Created places are written by the save, into a new file where the map has none")
  @Test
  void savesCreatedPlaces() throws Exception {
    // given: no pu_place.txt, and a land worth 3 PUs since before the map was opened
    final MapProject made = givenMapWithPlacesMissing();
    made.edit(g -> MapXml.withProduction(g, "Territory 3", 3));
    made.save();
    final Path values = made.getFolder().orElseThrow().resolve("pu_place.txt");
    Files.deleteIfExists(values);
    final MapProject project = MapProject.open(made.getXmlFile().orElseThrow());
    final Game game = project.game();

    // when
    project.editFiles(
        f ->
            f.withMissingUnitPlaces()
                .withMissingMarkerPlaces(game, Marker.NAME)
                .withMissingMarkerPlaces(game, Marker.VALUE));
    project.save();

    // then
    assertThat(PointFileReaderWriter.readOneToManyPlacements(placeTxt(project)))
        .as("place.txt has the territory again")
        .containsKey(TERRITORY);
    assertThat(PointFileReaderWriter.readOneToOne(namePlaces(project)))
        .as("name_place.txt has every territory, the one set by hand as it was")
        .containsOnlyKeys(TERRITORY, "Territory 2", "Territory 3", "Territory 4", SEA)
        .containsEntry("Territory 2", new Point(1, 2));
    assertThat(PointFileReaderWriter.readOneToOne(values))
        .as("pu_place.txt is new, with the one land that has a value")
        .containsOnlyKeys("Territory 3");
  }

  @DisplayName("A place file that does not read misses nothing: created places leave it as it is")
  @Test
  void unreadPlaceFileStaysAsItIs() throws Exception {
    // given: place.txt names a territory twice, so the game's reader refuses it
    final MapProject made = givenMapWithPlacesMissing();
    final Path places = placeTxt(made);
    Files.writeString(
        places, Files.readString(places) + "\r\n" + SEA + " (1,2)\r\n" + SEA + " (3,4)\r\n");
    final byte[] before = Files.readAllBytes(places);
    final MapProject project = MapProject.open(made.getXmlFile().orElseThrow());
    final Game game = project.game();

    // when
    final List<String> withoutUnits = project.getFiles().withoutUnitPlaces();
    project.editFiles(
        f -> {
          MapFiles filled = f.withMissingUnitPlaces();
          for (final Marker marker : Marker.values()) {
            filled = filled.withMissingMarkerPlaces(game, marker);
          }
          return filled;
        });
    project.save();

    // then
    assertThat(project.getFiles().placesUnread("place.txt")).as("it does not read").isTrue();
    assertThat(withoutUnits)
        .as("none counts as missing, though place.txt lacks a territory: its lines are not known")
        .isEmpty();
    assertThat(project.getFiles().places().units()).as("no unit place was set").isEmpty();
    assertThat(Files.readAllBytes(places)).as("the save left every byte of it").isEqualTo(before);
  }

  @DisplayName("A place file that does not read is not rewritten by a moved place or a layout")
  @Test
  void unreadPlaceFileRefusesSinglePlaces() throws Exception {
    // given: place.txt names a territory twice, so the game's reader refuses it
    final MapProject made = givenMapWithPlacesMissing();
    final Path places = placeTxt(made);
    Files.writeString(
        places, Files.readString(places) + "\r\n" + SEA + " (1,2)\r\n" + SEA + " (3,4)\r\n");
    final byte[] before = Files.readAllBytes(places);
    final MapProject project = MapProject.open(made.getXmlFile().orElseThrow());
    final List<String> territories = List.copyOf(project.getFiles().polygons().keySet());

    // when: a unit place set as a drag does, then Lay out unit places for every territory
    project.setUnitPlaces(SEA, List.of(new Point(5, 5)));
    project.layOutUnitPlaces(MapFiles.UnitLayout.GRID, 6, territories);
    final boolean changed = project.hasUnsavedChanges();
    project.save();

    // then
    assertThat(project.whyNoPlaces("place.txt"))
        .as("said with the way out, in the words of the menu's \"file does not read\"")
        .hasValueSatisfying(
            why -> assertThat(why).contains("place.txt does not read", "in Files"));
    assertThat(project.whyNoPlaces("name_place.txt")).as("a file that reads").isEmpty();
    assertThat(changed).as("neither made a step").isFalse();
    assertThat(Files.readAllBytes(places)).as("the save left every byte of it").isEqualTo(before);
  }

  @DisplayName("A save after a merge takes only the merged territory's lines from the place files")
  @Test
  void savesMergedTerritory() throws Exception {
    // given: every territory with unit places and a name place set by hand
    final MapProject made =
        MapProject.newMap(
                "Island Wars",
                MapPictureTest.givenPicture(),
                List.of("Red"),
                folder.resolve("maps"))
            .project();
    Files.writeString(
        namePlaces(made),
        "Territory 1 (10,20)\r\nTerritory 2 (30, 40)\r\nTerritory 3   (5,6)\r\n"
            + "Territory 4 (7,8)\r\nSea Zone 1 (9,9)\r\n");
    final MapProject project = MapProject.open(made.getXmlFile().orElseThrow());
    final List<String> unitLines = Files.readAllLines(placeTxt(project));
    final List<String> nameLines = Files.readAllLines(namePlaces(project));

    // when
    final List<String> named = project.mergeTerritories(TERRITORY, "Territory 4");
    project.save();

    // then
    assertThat(named).as("merged").isEmpty();
    assertThat(Files.readAllLines(placeTxt(project)))
        .as("place.txt without the merged one's line, every other line as it was")
        .isEqualTo(unitLines.stream().filter(line -> !line.startsWith("Territory 4 ")).toList())
        .hasSize(unitLines.size() - 1);
    assertThat(Files.readAllLines(namePlaces(project)))
        .as("name_place.txt likewise, the spacing typed by hand kept")
        .isEqualTo(nameLines.stream().filter(line -> !line.startsWith("Territory 4 ")).toList())
        .hasSize(nameLines.size() - 1);
  }
}
