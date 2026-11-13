package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.ResourceLoader;
import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.ui.mapdata.MapData;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import lombok.experimental.ExtensionMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.PlayerList.Player;
import org.triplea.map.description.file.MapDescriptionYaml;
import org.triplea.map.game.notes.GameNotes;
import org.triplea.util.PointFileReaderWriter;
import org.triplea.util.Tuple;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapFiles.UnitPicture;
import eddies.mapeditor.MapFiles.UnitPicture.Coloring;
import eddies.mapeditor.MapProject.NewMap;

/** A map made from nothing: picture, nations, owners, a unit, and saved as a map of its own. */
@ExtensionMethod(Builders.class)
final class NewMapTest {
  private static final Point HAND_SET = new Point(7, 7);

  @TempDir Path maps;

  private static MapProject givenNewMapWithPicture() {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    return project;
  }

  private static MapProject givenNewMapWithNations() {
    final MapProject project = givenNewMapWithPicture();
    project.addNation("Red");
    project.addNation("Blue");
    project.edit(
        game ->
            MapXml.withCapital(
                MapXml.withCapital(
                    MapXml.withOwner(
                        MapXml.withOwner(
                            MapXml.withOwner(game, "Territory 1", "Red"), "Territory 2", "Red"),
                        "Territory 4",
                        "Blue"),
                    "Territory 1",
                    "Red"),
                "Territory 4",
                "Blue"));
    project.edit(
        game ->
            MapXml.withVictoryCity(MapXml.withProduction(game, "Territory 1", 3), "Territory 4"));
    project.addUnitType("Ranger", "infantry");
    return project;
  }

  @DisplayName("A new map starts empty and unsaved, with the template's rules")
  @Test
  void startsEmpty() {
    final MapProject project = MapProject.newEmpty();

    assertThat(project.getXmlFile()).as("no file until saved").isEmpty();
    assertThat(project.name()).as("shown as").isEqualTo("Untitled map");
    assertThat(MapXml.territories(project.game())).as("no territories").isEmpty();
    assertThat(MapXml.nations(project.game())).as("no nations").isEmpty();
    assertThat(MapXml.unitTypes(project.game()))
        .as("classic units")
        .contains("infantry", "cruiser");
    assertThat(MapChecks.quick(project.game(), project.getFiles()))
        .as("what is missing is the guide's steps, no problems beside them")
        .isEmpty();
    assertThat(MapGuide.nextStep(project, List.of()).title())
        .as("the first step says what is missing")
        .isEqualTo("Make the territories from a picture of the map");
  }

  @DisplayName("Loading a picture is one step; undo brings the empty map back")
  @Test
  void undoesThePicture() {
    final MapProject project = givenNewMapWithPicture();
    assertThat(MapXml.territories(project.game())).as("the picture's areas").hasSize(5);
    assertThat(project.getFiles().size().width).as("the picture's width").isEqualTo(400);

    project.undo();

    assertThat(MapXml.territories(project.game())).as("no territories again").isEmpty();
    assertThat(project.getFiles().hasOutlines()).as("no outlines again").isFalse();
    assertThat(project.getFiles().picture()).as("no picture again").isEmpty();
    assertThat(project.hasUnsavedChanges()).as("as it started").isFalse();
  }

  @DisplayName("A picture alone keeps the territories; they are found only when asked for")
  @Test
  void loadsPictureWithoutFindingTerritories() {
    final MapProject project = givenNewMapWithPicture();
    final BufferedImage other = new BufferedImage(500, 300, BufferedImage.TYPE_INT_RGB);

    project.loadPicture(other);

    assertThat(MapXml.territories(project.game())).as("the territories stay").hasSize(5);
    assertThat(project.getFiles().picture()).as("the new picture").contains(other);
    assertThat(project.getFiles().size().width).as("the new picture's width").isEqualTo(500);
    project.undo();
    assertThat(project.getFiles().size().width).as("undo brings the old picture").isEqualTo(400);
  }

  @DisplayName("A picture without territories asks to press Make territories")
  @Test
  void asksToFindTerritories() {
    final MapProject empty = MapProject.newEmpty();

    empty.loadPicture(new BufferedImage(500, 300, BufferedImage.TYPE_INT_RGB));

    assertThat(MapXml.territories(empty.game())).as("nothing found yet").isEmpty();
    assertThat(MapGuide.nextStep(empty, List.of()).title())
        .as("what to do next")
        .isEqualTo("Make the territories from a picture of the map");
  }

  @DisplayName("Land of the engine's Neutral owner is no problem and needs no capital")
  @Test
  void acceptsNeutralOwner() {
    final MapProject project = givenNewMapWithNations();

    project.edit(game -> MapXml.withOwner(game, "Territory 3", "Neutral"));

    assertThat(MapChecks.quick(project.game(), project.getFiles()))
        .as("Neutral is every game's owner of neutral land")
        .extracting(Problem::text)
        .noneMatch(text -> text.contains("Neutral"));
  }

  @DisplayName("The units view finds the uploaded unit pictures, the map's own and its places")
  @Test
  void findsUnitPicturesAndPlaces() throws Exception {
    final MapProject project = givenNewMapWithNations();
    project.saveAsNewMap("Island Wars", maps);
    final BufferedImage white = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
    white.setRGB(0, 0, 0xFFFFFFFF);
    project.setUnitPicture("infantry", new UnitPicture(white, Coloring.AS_IS));
    final BufferedImage mapsOwn = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
    mapsOwn.setRGB(0, 0, 0xFF123456);
    ImageIO.write(
        mapsOwn,
        "png",
        project.getFolder().orElseThrow().resolve("units/Red/artillery.png").toFile());

    final Optional<Image> uploaded = project.unitPicture("Red", Color.RED, "infantry");
    final Optional<Image> own = project.unitPicture("Red", Color.RED, "artillery");
    final Optional<Image> none = project.unitPicture("Red", Color.RED, "no_such_unit");

    assertThat(uploaded).as("the uploaded picture before the map's").contains(white);
    assertThat(own.map(image -> ((BufferedImage) image).getRGB(0, 0)))
        .as("the map's own picture of the unit, read from its units folder")
        .contains(0xFF123456);
    assertThat(none).as("a unit type neither map nor game has a picture of").isEmpty();
    assertThat(
            PointFileReaderWriter.readOneToManyPlacements(
                project.getFolder().orElseThrow().resolve("place.txt")))
        .as("place.txt as saved, for every territory")
        .containsKeys("Territory 1", "Territory 4");
  }

  @DisplayName("Skip in the wizard makes a map without a picture in the wizard's folder")
  @Test
  void skipsThePicture() throws Exception {
    final Path folder = Files.createDirectory(maps.resolve("elsewhere"));
    final NewMapWizard wizard = new NewMapWizard(folder, made -> {});
    wizard.name.setText("Empty Isle");
    wizard.next.doClick();

    wizard.skip.doClick();
    wizard.nations.setText("Red");
    final MapProject made = wizard.making().call().project();

    assertThat(made.getFolder())
        .as("in the folder the wizard has")
        .contains(folder.resolve("empty_isle"));
    assertThat(made.getFiles().picture()).as("no picture").isEmpty();
    assertThat(MapXml.territories(made.game())).as("no territories").isEmpty();
    assertThat(MapXml.nations(made.game())).as("the nations typed").containsExactly("Red");
  }

  @DisplayName("The wizard's map is saved with the picture's territories and its nations")
  @Test
  void makesTheWizardsMap() throws Exception {
    final BufferedImage picture = MapPictureTest.givenPicture();

    final NewMap newMap =
        MapProject.newMap(
            "Island Wars", picture, List.of("Red", " ", "Blue", "Red", "Territory 1"), maps);

    final MapProject made = newMap.project();
    assertThat(made.getXmlFile())
        .as("saved in a folder of its own")
        .contains(maps.resolve("island_wars/games/island_wars.xml"));
    assertThat(MapXml.territories(made.game())).as("found in the picture").hasSize(5);
    assertThat(MapXml.nations(made.game()))
        .as("blank lines left out")
        .containsExactly("Red", "Blue");
    assertThat(newMap.refusedNations())
        .as("a nation twice and a territory's name, told to the player")
        .containsExactly("Red", "Territory 1");
    assertThat(made.hasUnsavedChanges()).as("all saved").isFalse();
    assertThat(made.canUndo()).as("its history starts with the saved map").isFalse();
    assertThat(MapFolder.whyNoNewMap(maps, "island wars"))
        .as("a second map of the name is refused before any step")
        .contains("There is a map folder island_wars already");
  }

  @DisplayName("A wizard's map without a picture is saved without territories")
  @Test
  void makesTheWizardsMapWithoutPicture() throws Exception {
    final NewMap newMap = MapProject.newMap("Later", null, List.of("Red"), maps);

    assertThat(newMap.project().getFiles().hasOutlines()).as("no outlines yet").isFalse();
    assertThat(MapXml.nations(newMap.project().game())).as("its nation").containsExactly("Red");
    assertThat(maps.resolve("later/map.yml")).as("a map folder all the same").exists();
  }

  @DisplayName("Nations get distinct colors; names in use are refused")
  @Test
  void addsNations() {
    final MapProject project = givenNewMapWithPicture();

    project.addNation("Red");
    project.addNation("Blue");

    assertThat(project.getFiles().nationColor("Red"))
        .as("a color of its own")
        .isPresent()
        .isNotEqualTo(project.getFiles().nationColor("Blue"));
    assertThat(List.of("Red", "Territory 1", "infantry", " ", "a:b"))
        .as("taken, blank or not to be written")
        .noneMatch(project::addNation);
    project.setNationColor("Red", Color.BLACK);
    assertThat(project.getFiles().nationColor("Red")).as("chosen").contains(Color.BLACK);
    project.undo();
    assertThat(project.getFiles().nationColor("Red")).as("undone").isNotEqualTo(Color.BLACK);
  }

  @DisplayName("A nation that takes turns but owns no land is what the land step waits for")
  @Test
  void reportsNationsWithoutLand() {
    final MapProject project = givenNewMapWithPicture();

    project.addNation("Red");
    // a player without turns, as maps have for their neutrals
    project.edit(
        g ->
            g.toBuilder()
                .playerList(
                    g.getPlayerList().toBuilder()
                        .players(
                            Stream.concat(
                                    g.getPlayerList().getPlayers().stream(),
                                    Stream.of(Player.builder().name("Neutrals").build()))
                                .toList())
                        .build())
                .build());

    assertThat(MapGuide.withoutLand(project.game()))
        .as("a new nation has turns, so it needs land to play; the neutrals need none")
        .containsExactly("Red");
  }

  @DisplayName("Save makes a whole map folder that the engine and the game's map loading take")
  @Test
  void savesAWholeMap() throws Exception {
    final MapProject project = givenNewMapWithNations();

    project.saveAsNewMap("Island Wars", maps);

    final Path folder = maps.resolve("island_wars");
    final Path xml = folder.resolve("games/island_wars.xml");
    assertThat(project.getXmlFile()).as("a saved map now").contains(xml);
    assertThat(project.getFolder())
        .as("which knows its folder, eg for the unit pictures just written")
        .contains(folder);
    assertThat(project.name()).as("shown by its name").isEqualTo("Island Wars");
    assertThat(project.hasUnsavedChanges()).as("all saved").isFalse();
    assertThat(
            List.of(
                "map.yml",
                "polygons.txt",
                "centers.txt",
                "map.properties",
                "baseTiles/0_0.png",
                "baseTiles/1_1.png",
                "smallMap.png",
                "preview.png",
                "place.txt",
                "name_place.txt",
                "pu_place.txt",
                "capitols.txt",
                "vc.txt",
                "games/island_wars.notes.html",
                "flags/Red.png",
                "flags/Red_small.png",
                "flags/Red_large.png",
                "flags/Blue.png",
                "units/Red/infantry.png",
                "units/Red/Ranger.png",
                "units/Blue/battleship.png"))
        .as("all files a map needs")
        .allMatch(file -> Files.exists(folder.resolve(file)));
    assertThat(readProperties(folder.resolve("map.properties")))
        .as("the map's size and the nations' colors")
        .containsKeys("map.width", "map.height", "color.Red", "color.Blue");
    assertThat(MapDescriptionYaml.fromFile(folder.resolve("map.yml")).orElseThrow().getMapName())
        .as("the game finds its map by this name")
        .isEqualTo("Island Wars");
    assertThat(MapChecks.findError(xml)).as("the engine loads the game").isEmpty();

    final MapProject reopened = MapProject.open(xml);
    assertThat(MapXml.territories(reopened.game()))
        .as("the same territories")
        .isEqualTo(MapXml.territories(project.game()));
    assertThat(MapXml.owner(reopened.game(), "Territory 4")).as("the owners").contains("Blue");
    assertThat(reopened.getFiles().polygons()).as("the outlines").hasSize(5);
    try (ResourceLoader loader = new ResourceLoader(folder)) {
      final MapData mapData = new MapData(loader);
      assertThat(mapData.getTerritories())
          .as("the game's map loading finds the outlines")
          .containsExactlyInAnyOrderElementsOf(MapXml.territories(project.game()));
      assertThat(mapData.getPlayerColor("Red"))
          .as("and the nations' colors")
          .isEqualTo(project.getFiles().nationColor("Red").orElseThrow());
      final GameData data = GameParser.parse(xml, false).orElseThrow();
      assertThatCode(() -> mapData.verify(data))
          .as("a game starts only with centers, outlines and unit places for every territory")
          .doesNotThrowAnyException();
      thenMarkersAreRead(folder, mapData, data);
    }
  }

  /** The game draws names, values, capitals and victory cities where the marker files say. */
  private static void thenMarkersAreRead(
      final Path folder, final MapData mapData, final GameData data) throws IOException {
    final Territory valued = data.getMap().getTerritoryOrThrow("Territory 1");
    final Territory victoryCity = data.getMap().getTerritoryOrThrow("Territory 4");
    final Point center = mapData.getCenter(valued);
    final Point name = mapData.getNamePlacementPoint(valued).orElseThrow();
    assertThat(name.y)
        .as("the name's baseline above the units around the center")
        .isLessThan(center.y - UnitImageFactory.DEFAULT_UNIT_ICON_SIZE / 2);
    assertThat(mapData.getPolygons(valued))
        .as("the name inside its territory")
        .anyMatch(outline -> outline.contains(name));
    assertThat(mapData.getPuPlacementPoint(valued))
        .as("the value below the name, as pu_place.txt says")
        .contains(
            PointFileReaderWriter.readOneToOne(folder.resolve("pu_place.txt")).get("Territory 1"))
        .hasValueSatisfying(value -> assertThat(value.y).isGreaterThan(name.y));
    assertThat(mapData.getPuPlacementPoint(data.getMap().getTerritoryOrThrow("Territory 2")))
        .as("no value where the territory gives no PUs")
        .isEmpty();
    assertThat(mapData.getCapitolMarkerLocation(valued))
        .as("the capital's flag centered on the center, not hanging from it")
        .isEqualTo(new Point(center.x - 50, center.y - 50));
    assertThat(PointFileReaderWriter.readOneToOne(folder.resolve("capitols.txt")))
        .as("capitals only")
        .containsOnlyKeys("Territory 1", "Territory 4");
    assertThat(mapData.getVcPlacementPoint(victoryCity))
        .as("the victory city's marker beside the center")
        .isNotEqualTo(mapData.getCenter(victoryCity));
    assertThat(PointFileReaderWriter.readOneToOne(folder.resolve("vc.txt")))
        .as("victory cities only")
        .containsOnlyKeys("Territory 4");
  }

  @DisplayName("A place set by hand stays, also of a capital taken away: other games share it")
  @Test
  void keepsMarkersSetByHand() throws Exception {
    final MapProject project = givenNewMapWithNations();
    project.saveAsNewMap("Island Wars", maps);
    final Path capitals = maps.resolve("island_wars/capitols.txt");
    PointFileReaderWriter.writeOneToOne(
        capitals, Map.of("Territory 1", new Point(7, 7), "Territory 4", new Point(9, 9)));

    project.edit(game -> MapXml.withCapital(game, "Territory 4", null));
    project.save();

    assertThat(PointFileReaderWriter.readOneToOne(capitals))
        .as("the hand-set places kept, also where this game has no capital now")
        .containsOnly(
            Map.entry("Territory 1", new Point(7, 7)), Map.entry("Territory 4", new Point(9, 9)));
  }

  @DisplayName("Renamed twice and saved, a territory keeps its hand-set places and overflow")
  @Test
  void renameKeepsPlacesSetByHand() throws Exception {
    final MapProject project = givenOpenedMapWithPlacesSetByHand();

    project.rename("Territory 1", "Middle");
    project.renameAll(List.of("Middle;Home"));
    project.save();

    thenPlacesSetByHandAreUnder("Home");
  }

  @DisplayName("Saved again and again after a rename, the hand-set places stay under the new name")
  @Test
  void laterSavesKeepPlacesSetByHand() throws Exception {
    final MapProject project = givenOpenedMapWithPlacesSetByHand();
    project.rename("Territory 1", "Home");
    project.save();

    for (int value = 1; value <= 3; value++) {
      final int production = value;
      project.edit(game -> MapXml.withProduction(game, "Territory 2", production));
      project.save();
    }

    thenPlacesSetByHandAreUnder("Home");
  }

  @DisplayName("A rename saved and then undone and saved keeps the hand-set places too")
  @Test
  void undoneRenameKeepsPlacesSetByHand() throws Exception {
    final MapProject project = givenOpenedMapWithPlacesSetByHand();
    project.rename("Territory 1", "Home");
    project.save();

    project.undo();
    project.save();

    thenPlacesSetByHandAreUnder("Territory 1");
  }

  /** The map saved, its unit and flag places of Territory 1 set by hand, and opened again. */
  private MapProject givenOpenedMapWithPlacesSetByHand() throws Exception {
    givenNewMapWithNations().saveAsNewMap("Island Wars", maps);
    final Path folder = maps.resolve("island_wars");
    final Map<String, Tuple<List<Point>, Boolean>> places =
        new LinkedHashMap<>(
            PointFileReaderWriter.readOneToManyPlacements(folder.resolve("place.txt")));
    places.put("Territory 1", Tuple.of(List.of(HAND_SET), Boolean.TRUE));
    PointFileReaderWriter.writeOneToManyPlacements(folder.resolve("place.txt"), places);
    final Map<String, Point> capitals =
        new LinkedHashMap<>(PointFileReaderWriter.readOneToOne(folder.resolve("capitols.txt")));
    capitals.put("Territory 1", HAND_SET);
    PointFileReaderWriter.writeOneToOne(folder.resolve("capitols.txt"), capitals);
    return MapProject.open(folder.resolve("games/island_wars.xml"));
  }

  private void thenPlacesSetByHandAreUnder(final String territory) throws IOException {
    final Path folder = maps.resolve("island_wars");
    assertThat(PointFileReaderWriter.readOneToManyPlacements(folder.resolve("place.txt")))
        .as("the unit places and the overflow flag set by hand, under the name saved")
        .containsEntry(territory, Tuple.of(List.of(HAND_SET), Boolean.TRUE));
    assertThat(PointFileReaderWriter.readOneToOne(folder.resolve("capitols.txt")))
        .as("the flag's place set by hand, under the name saved")
        .containsEntry(territory, HAND_SET);
  }

  @DisplayName("A new map saved before its picture gets name and value places with the picture")
  @Test
  void writesNamePlacesWhenThePictureComesLater() throws Exception {
    final MapProject project = MapProject.newEmpty();
    project.saveAsNewMap("Plain", maps);
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));

    project.save();

    assertThat(maps.resolve("plain/name_place.txt"))
        .as("written with the outlines, as for a map saved with its picture")
        .exists();
  }

  @DisplayName("An uploaded unit picture is saved for each nation, colored or as it is")
  @Test
  void savesUploadedUnitPictures() throws Exception {
    final MapProject project = givenNewMapWithNations();
    final BufferedImage grey = MapFolder.toUnitSize(givenGreyPicture(), new Dimension(48, 48));
    project.setUnitPicture("Ranger", new UnitPicture(grey, Coloring.PER_NATION));
    project.setUnitPicture("infantry", new UnitPicture(grey, Coloring.AS_IS));
    project.setUnitPicture("armour", new UnitPicture(grey, Coloring.AS_IS));
    project.undo();

    project.saveAsNewMap("Island Wars", maps);

    final Path units = maps.resolve("island_wars/units");
    final Color red = project.getFiles().nationColor("Red").orElseThrow();
    assertThat(middlePixel(units.resolve("Red/Ranger.png")))
        .as("in Red's color")
        .isEqualTo(MapFolder.tint(grey, red).getRGB(24, 24));
    assertThat(middlePixel(units.resolve("Blue/Ranger.png")))
        .as("in Blue's, another")
        .isNotEqualTo(middlePixel(units.resolve("Red/Ranger.png")));
    assertThat(List.of("Red/infantry.png", "Blue/infantry.png"))
        .as("the same for all nations")
        .allMatch(file -> middlePixel(units.resolve(file)) == grey.getRGB(24, 24));
    assertThat(project.getFiles().unitPictures())
        .as("the upload undone")
        .doesNotContainKey("armour");
    assertThat(cornerPixel(units.resolve("Red/armour.png")))
        .as("the game's picture of it, clear around the unit, not the grey square")
        .isNotEqualTo(grey.getRGB(0, 0));
  }

  @DisplayName("A changed upload is written again; taking it back brings the game's picture")
  @Test
  void rewritesChangedUnitPictures() throws Exception {
    final MapProject project = givenNewMapWithNations();
    final BufferedImage grey = MapFolder.toUnitSize(givenGreyPicture(), new Dimension(48, 48));
    project.setUnitPicture("Ranger", new UnitPicture(grey, Coloring.PER_NATION));
    project.setUnitPicture("infantry", new UnitPicture(grey, Coloring.AS_IS));
    project.saveAsNewMap("Island Wars", maps);

    project.setUnitPicture("Ranger", new UnitPicture(grey, Coloring.AS_IS));
    project.removeUnitPicture("infantry");
    project.save();

    final Path units = maps.resolve("island_wars/units");
    assertThat(middlePixel(units.resolve("Red/Ranger.png")))
        .as("now as it is")
        .isEqualTo(grey.getRGB(24, 24));
    assertThat(cornerPixel(units.resolve("Red/infantry.png")))
        .as("the game's infantry again")
        .isNotEqualTo(grey.getRGB(0, 0));
  }

  /** A grey square twice a unit's size. */
  private static BufferedImage givenGreyPicture() {
    final BufferedImage picture = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = picture.createGraphics();
    g.setColor(Color.GRAY);
    g.fillRect(0, 0, 96, 96);
    g.dispose();
    return picture;
  }

  private static int middlePixel(final Path file) {
    return readPicture(file).getRGB(24, 24);
  }

  private static int cornerPixel(final Path file) {
    return readPicture(file).getRGB(0, 0);
  }

  private static BufferedImage readPicture(final Path file) {
    try {
      return ImageIO.read(file.toFile());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DisplayName("The notes are saved where the game shows them, and read from there")
  @Test
  void savesNotes() throws Exception {
    final MapProject project = givenNewMapWithNations();
    project.saveAsNewMap("Island Wars", maps);
    project.edit(game -> MapXml.withNotes(game, "<h1>Island Wars</h1><p>Take the islands.</p>"));

    project.save();

    final Path xml = maps.resolve("island_wars/games/island_wars.xml");
    assertThat(GameNotes.loadGameNotes(xml))
        .as("the game's notes file")
        .isEqualTo("<h1>Island Wars</h1><p>Take the islands.</p>");
    assertThat(MapXml.notes(MapProject.open(xml).game()))
        .as("opened again")
        .isEqualTo("<h1>Island Wars</h1><p>Take the islands.</p>");
  }

  @DisplayName("Later saves write what changed: the game, colors and pictures of new nations")
  @Test
  void savesChangesLater() throws Exception {
    final MapProject project = givenNewMapWithNations();
    project.saveAsNewMap("Island Wars", maps);
    project.addNation("Green");

    project.save();

    final Path folder = maps.resolve("island_wars");
    assertThat(folder.resolve("flags/Green.png")).as("the new nation's flag").exists();
    assertThat(folder.resolve("units/Green/Ranger.png")).as("its units").exists();
    assertThat(readProperties(folder.resolve("map.properties")))
        .as("its color")
        .containsKey("color.Green");
    final Path xml = folder.resolve("games/island_wars.xml");
    assertThat(MapXml.nations(MapProject.open(xml).game())).as("in the game").contains("Green");
    assertThat(MapChecks.findError(xml)).as("which still loads").isEmpty();
  }

  @DisplayName("A map without a picture saves too; a folder in use is refused and left alone")
  @Test
  void savesWithoutPicture() throws Exception {
    Files.createDirectories(maps.resolve("taken"));
    final MapProject project = MapProject.newEmpty();
    project.addNation("Red");

    assertThatThrownBy(() -> project.saveAsNewMap("Taken", maps))
        .as("never over a map that is there")
        .isInstanceOf(IOException.class);
    project.saveAsNewMap("Plain", maps);

    assertThat(maps.resolve("plain/games/plain.xml")).as("the game").exists();
    assertThat(maps.resolve("plain/polygons.txt")).as("no outlines to write").doesNotExist();
    assertThat(maps.resolve("taken")).as("left as it was").isEmptyDirectory();
  }

  private static Properties readProperties(final Path file) throws IOException {
    final Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      properties.load(in);
    }
    return properties;
  }
}
