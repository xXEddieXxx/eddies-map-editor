package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.triplea.attachments.RulesAttachment;
import java.awt.Color;
import java.awt.Image;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.util.PointFileReaderWriter;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapFiles.Redrawn;
import eddies.mapeditor.MapPicture.DetectedTerritory;
import eddies.mapeditor.MapProject.RedrawChoices;
import eddies.mapeditor.MapProject.Renamed;

final class MapProjectTest {
  private static final String NORTH = "red north 1";
  // the areas of MapPictureTest.givenPicture, as Find territories names them
  private static final String TOP_LEFT = "Territory 1";
  private static final String TOP_RIGHT = "Territory 2";

  @TempDir Path folder;

  private MapProject givenMinimapProject() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        MapProjectTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  private MapProject givenSavedMapWithRed() throws Exception {
    return MapProject.newMap(
            "Island Wars", MapPictureTest.givenPicture(), List.of("Red"), folder.resolve("maps"))
        .project();
  }

  @DisplayName("A nation's imported unit picture is shown and saved; taken back, the game's again")
  @Test
  void importsNationUnitPictures() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final Path file = project.getFolder().orElseThrow().resolve("units/Red/infantry.png");
    final BufferedImage imported = new BufferedImage(5, 7, BufferedImage.TYPE_INT_ARGB);
    final Color red = project.nationColor("Red");

    project.setNationUnitPictures("Red", Map.of("infantry", imported));
    project.save();
    final BufferedImage saved = ImageIO.read(file.toFile());
    project.setNationUnitPictures("Red", Map.of());
    final Optional<Integer> shownAfterTakingBack =
        project.unitPicture("Red", red, "infantry").map(image -> image.getWidth(null));
    project.save();

    assertThat(saved.getWidth()).as("the save wrote the imported picture").isEqualTo(5);
    assertThat(shownAfterTakingBack)
        .as("taken back, the map's file written by the save is not shown")
        .isNotEqualTo(Optional.of(5));
    assertThat(Files.exists(file) ? ImageIO.read(file.toFile()).getWidth() : 0)
        .as("the next save removed it or wrote the game's over it")
        .isNotEqualTo(5);
  }

  @DisplayName("A unit type's picture is the map's of the first nation with one, before TripleA's")
  @Test
  void unitPictureOfTheFirstNationWithOne() throws Exception {
    final MapProject project = givenMinimapProject();
    final List<String> nations = MapXml.nations(project.game());
    final String first = nations.getFirst();
    final BufferedImage picture = new BufferedImage(5, 7, BufferedImage.TYPE_INT_ARGB);
    project.setNationUnitPictures(nations.get(1), Map.of("infantry", picture));

    final Optional<Integer> width =
        project.unitPicture("infantry").map(image -> image.getWidth(null));
    final Optional<Image> firstNations =
        project.unitPicture(first, project.nationColor(first), "infantry");

    assertThat(firstNations)
        .as("the first nation shows TripleA's picture")
        .isPresent()
        .get()
        .extracting(image -> image.getWidth(null))
        .isNotEqualTo(5);
    assertThat(width).as("the second nation's picture of the map's is shown").contains(5);
  }

  @DisplayName("Units in the nation's color are tinted as the game does and follow a new color")
  @Test
  void tintsUnitsInNationColor() throws Exception {
    final MapProject project = givenMinimapProject();
    final String nation = MapXml.nations(project.game()).getFirst();
    final BufferedImage white = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
    white.setRGB(0, 0, 0xFFFFFFFF);
    project.setNationUnitPictures(nation, Map.of("infantry", white));

    final Color before = project.nationColor(nation);
    project.tintUnits(List.of(nation));
    final Optional<Color> tint = project.getFiles().unitTint(nation);
    project.setNationColor(nation, Color.BLUE);
    final BufferedImage shown =
        (BufferedImage) project.unitPicture(nation, Color.BLUE, "infantry").orElseThrow();

    assertThat(tint).as("tinted in the nation's color").contains(before);
    assertThat(project.getFiles().unitTint(nation))
        .as("the tint follows the nation's new color")
        .contains(Color.BLUE);
    assertThat(new Color(shown.getRGB(0, 0)))
        .as("the white pixel shows in the tint, as the game draws it")
        .matches(c -> c.getBlue() > c.getRed() && c.getBlue() > c.getGreen());
    assertThat(white.getRGB(0, 0)).as("the picture itself stays").isEqualTo(0xFFFFFFFF);
    project.undo();
    assertThat(project.getFiles().unitTint(nation))
        .as("one undo takes back color and tint together")
        .isEqualTo(tint);
  }

  @DisplayName("A tint of another color stays; an ignored unit is not tinted; untinted, no entry")
  @Test
  void tintOfItsOwn() throws Exception {
    final MapProject project = givenMinimapProject();
    final String nation = MapXml.nations(project.game()).getFirst();
    final BufferedImage white = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
    white.setRGB(0, 0, 0xFFFFFFFF);
    project.setNationUnitPictures(nation, Map.of("infantry", white));

    project.setMapProperties(
        Map.of(MapFiles.UNIT_TINT + nation, "00FF00", "units.transform.ignore", "infantry"));
    project.setNationColor(nation, Color.BLUE);
    final BufferedImage shown =
        (BufferedImage) project.unitPicture(nation, Color.BLUE, "infantry").orElseThrow();
    final Optional<Color> tint = project.getFiles().unitTint(nation);
    project.setMapProperty(MapFiles.UNIT_TINT + nation, "");

    assertThat(tint).as("a tint of its own does not follow the color").contains(Color.GREEN);
    assertThat(shown.getRGB(0, 0)).as("an ignored unit is drawn as it is").isEqualTo(0xFFFFFFFF);
    assertThat(project.getFiles().properties())
        .as("untinted, the entry goes, as the game reads no empty color")
        .doesNotContainKey(MapFiles.UNIT_TINT + nation);
  }

  @DisplayName("Tinting units of a nation without a color writes the color too, the same")
  @Test
  void tintWritesMissingColor() throws Exception {
    final MapProject project = givenMinimapProject();
    final String nation = MapXml.nations(project.game()).getFirst();
    project.setMapProperty("color." + nation, "");

    project.tintUnits(List.of(nation));

    assertThat(project.getFiles().properties().getProperty("color." + nation))
        .as("land and units in one color in the game")
        .isNotNull()
        .isEqualTo(project.getFiles().properties().getProperty(MapFiles.UNIT_TINT + nation));
  }

  @DisplayName("Undo keeps the newest steps whose pictures fit the limit, a shared one counted once")
  @Test
  void undoForgetsOldPictures() {
    // given: 10x10 pictures of 400 bytes each, newest step first
    final BufferedImage shared = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
    final BufferedImage older = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
    final BufferedImage oldest = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
    final List<Optional<BufferedImage>> steps =
        List.of(
            Optional.of(shared),
            Optional.of(shared),
            Optional.empty(),
            Optional.of(older),
            Optional.of(oldest));

    // when
    final int kept = MapProject.keptSteps(steps, Optional.empty(), 800);
    final int keptBesideCurrent = MapProject.keptSteps(steps, Optional.of(shared), 800);

    // then
    assertThat(kept).as("shared counted once, then older: 800 bytes; oldest goes").isEqualTo(4);
    assertThat(keptBesideCurrent)
        .as("the current picture is not counted, so oldest fits too")
        .isEqualTo(5);
  }

  @DisplayName("Borders redrawn from files the map no longer has are not taken")
  @Test
  void refusesRedrawnAfterChange() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final MapFiles from = project.getFiles();
    final BufferedImage picture = MapPictureTest.givenPicture();
    final Redrawn redrawn = from.withBordersFrom(picture, MapPicture.detect(picture));
    project.setNationColor("Red", Color.BLUE);

    final Optional<List<String>> taken = project.takeRedrawn(from, redrawn, RedrawChoices.none());

    assertThat(taken).as("the files changed meanwhile").isEmpty();
    assertThat(project.getFiles().nationColor("Red"))
        .as("the change made meanwhile stays")
        .contains(Color.BLUE);
  }

  @DisplayName("A new area of a redrawn picture becomes a territory, connected; one undo")
  @Test
  void addsRedrawnArea() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final MapFiles from = project.getFiles();
    final Game game = project.game();
    final BufferedImage split = MapFilesTest.givenPictureWithNewBorder();
    final Redrawn redrawn = from.withBordersFrom(split, MapPicture.detect(split));
    final DetectedTerritory area = redrawn.added().getFirst();
    final DetectedTerritory east =
        new DetectedTerritory("East", false, area.outlines(), area.center());

    final Optional<List<String>> named =
        project.takeRedrawn(from, redrawn, new RedrawChoices(Map.of(area, east), Map.of()));

    assertThat(named).as("taken, nothing still named").contains(List.of());
    assertThat(MapXml.territories(project.game())).as("added").contains("East");
    assertThat(MapXml.isWater(project.game(), "East")).as("as land").isFalse();
    assertThat(MapXml.neighbors(project.game(), "East"))
        .as("the territories that took the areas beside it")
        .containsExactlyInAnyOrder(TOP_RIGHT, "Territory 4", "Sea Zone 1");
    assertThat(project.getFiles().polygons().get("East"))
        .as("its outline")
        .isEqualTo(area.outlines());
    project.undo();
    assertThat(project.game()).as("one undo takes it back").isSameAs(game);
    assertThat(project.canUndo()).as("it was one step").isFalse();
  }

  @DisplayName("A merged area goes to the chosen keeper; the other goes, its neighbours to it")
  @Test
  void mergesRedrawnArea() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final MapFiles from = project.getFiles();
    final BufferedImage merged = MapFilesTest.givenPictureWithBorderAt(500);
    final Redrawn redrawn = from.withBordersFrom(merged, MapPicture.detect(merged));
    final DetectedTerritory area = redrawn.merged().keySet().iterator().next();

    final Optional<List<String>> named =
        project.takeRedrawn(from, redrawn, new RedrawChoices(Map.of(), Map.of(area, TOP_RIGHT)));

    assertThat(named).as("taken, nothing still named").contains(List.of());
    assertThat(MapXml.territories(project.game())).as("the first is gone").doesNotContain(TOP_LEFT);
    assertThat(project.getFiles().polygons())
        .as("from the files too")
        .doesNotContainKey(TOP_LEFT);
    assertThat(project.getFiles().polygons().get(TOP_RIGHT))
        .as("the keeper has the merged outline")
        .isEqualTo(area.outlines());
    assertThat(MapXml.neighbors(project.game(), TOP_RIGHT))
        .as("and the removed one's neighbour below it")
        .contains("Territory 3");
  }

  @DisplayName("A merged territory the rules still name stays with its old border and is told")
  @Test
  void keepsMergedTerritoryStillNamed() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    project.edit(
        g ->
            MapXml.withOption(
                g,
                new MapXml.AttachmentKey(
                    "rulesAttachment", RulesAttachment.class.getName(), "player", "Red"),
                "directPresenceTerritories",
                TOP_RIGHT));
    final MapFiles from = project.getFiles();
    final BufferedImage merged = MapFilesTest.givenPictureWithBorderAt(500);
    final Redrawn redrawn = from.withBordersFrom(merged, MapPicture.detect(merged));

    final Optional<List<String>> named = project.takeRedrawn(from, redrawn, RedrawChoices.none());

    assertThat(named)
        .as("the territory to go and where it is named")
        .contains(List.of(TOP_RIGHT + ": rulesAttachment of Red: directPresenceTerritories"));
    assertThat(MapXml.territories(project.game())).as("it stays").contains(TOP_RIGHT);
    assertThat(project.getFiles().polygons().get(TOP_RIGHT))
        .as("with its old outline")
        .isEqualTo(from.polygons().get(TOP_RIGHT));
  }

  @DisplayName("Undo and redo step through the changes")
  @Test
  void undoAndRedo() throws Exception {
    final MapProject project = givenMinimapProject();
    final int value = MapXml.production(project.game(), NORTH);
    project.edit(game -> MapXml.withOwner(game, NORTH, "Italians"));
    project.edit(game -> MapXml.withProduction(game, NORTH, value + 4));

    project.undo();

    assertThat(MapXml.production(project.game(), NORTH)).as("the value undone").isEqualTo(value);
    assertThat(MapXml.owner(project.game(), NORTH)).as("the owner kept").contains("Italians");
    project.redo();
    assertThat(MapXml.production(project.game(), NORTH))
        .as("the value back after redo")
        .isEqualTo(value + 4);
  }

  @DisplayName("A new change ends what could be redone")
  @Test
  void newChangeEndsRedo() throws Exception {
    final MapProject project = givenMinimapProject();
    project.edit(game -> MapXml.withOwner(game, NORTH, "Italians"));
    project.undo();

    project.edit(game -> MapXml.withVictoryCity(game, NORTH));

    assertThat(project.canRedo())
        .as("the undone change is dropped once another change is made")
        .isFalse();
  }

  @DisplayName("The changed territories are counted per step, and none after a save")
  @Test
  void countsChangedTerritories() throws Exception {
    final MapProject project = givenMinimapProject();
    project.edit(game -> MapXml.withOwner(game, NORTH, "Italians"));
    final long beforeSave = project.changedCount();

    project.save();

    assertThat(beforeSave).as("one owner changed").isEqualTo(1);
    assertThat(project.changedCount()).as("the save is the new start").isZero();
  }

  @DisplayName("Saving writes the changes, keeps the first version as .bak and loads again")
  @Test
  void savesAndKeepsABackup() throws Exception {
    final MapProject project = givenMinimapProject();
    final String before = Files.readString(project.getXmlFile().orElseThrow());
    project.edit(game -> MapXml.withOwner(game, NORTH, "Italians"));
    assertThat(project.isChanged(NORTH)).as("marked until saved").isTrue();

    project.save();

    assertThat(project.hasUnsavedChanges()).as("all changes are saved").isFalse();
    assertThat(project.isChanged(NORTH)).as("the mark goes with the save").isFalse();
    assertThat(Files.readString(folder.resolve("minimap.xml.bak")))
        .as("the backup is the file before the first save")
        .isEqualTo(before);
    assertThat(MapXml.owner(MapProject.open(project.getXmlFile().orElseThrow()).game(), NORTH))
        .as("opened again, the change is there")
        .contains("Italians");
  }

  @DisplayName("The checks find a territory left alone and a game the engine does not load")
  @Test
  void findsProblems() throws Exception {
    final MapProject project = givenMinimapProject();
    assertThat(MapChecks.all(project.game(), project.getFiles()))
        .as("the minimap is fine, but the test resources have no polygons.txt")
        .extracting(Problem::text)
        .containsExactly("No picture yet: load one in Territories");

    project.edit(
        game ->
            MapXml.withUnits(
                MapXml.withoutConnection(
                    MapXml.withoutConnection(
                        MapXml.withoutConnection(
                            MapXml.withoutConnection(
                                MapXml.withoutConnection(game, NORTH, "red capitol"),
                                NORTH,
                                "red middle 1"),
                            NORTH,
                            "red north 2"),
                        NORTH,
                        "sea zone 1"),
                    NORTH,
                    "neutral territory 1"),
                NORTH,
                "Russians",
                "tank",
                2));

    assertThat(MapChecks.all(project.game(), project.getFiles()))
        .as("all neighbours removed, an unknown unit type, and the engine refuses it")
        .extracting(Problem::text)
        .contains(
            NORTH + " is not connected to any territory", NORTH + " has units of no type: tank")
        .anyMatch(text -> text.startsWith("The game does not load"));
  }

  private MapProject givenProjectWithOutlines() throws Exception {
    final Path xml = givenMinimapProject().getXmlFile().orElseThrow();
    final Polygon outline = new Polygon(new int[] {0, 10, 10}, new int[] {0, 0, 10}, 3);
    return new MapProject(
        xml,
        MapProject.open(xml).game(),
        new MapFiles(
            Optional.of(folder),
            Map.of(NORTH, List.of(outline)),
            Map.of(NORTH, new Point(5, 3)),
            new Properties(),
            Optional.empty(),
            Map.of(),
            Map.of(),
            Optional.empty(),
            MapFiles.Places.none()));
  }

  @DisplayName("Renaming a territory renames its outline and center; undo takes both back")
  @Test
  void renameAndUndo() throws Exception {
    final MapProject project = givenProjectWithOutlines();

    assertThat(project.rename(NORTH, "Archangel")).as("a new name is taken").isTrue();

    assertThat(MapXml.territories(project.game())).as("the game").contains("Archangel");
    assertThat(project.getFiles().polygons()).as("the outlines").containsOnlyKeys("Archangel");
    assertThat(project.getFiles().centers()).as("the centers").containsOnlyKeys("Archangel");
    project.undo();
    assertThat(MapXml.territories(project.game())).as("the game undone").contains(NORTH);
    assertThat(project.getFiles().polygons()).as("the outlines undone").containsOnlyKeys(NORTH);
    assertThat(project.hasUnsavedChanges()).as("back to the saved state").isFalse();
  }

  @DisplayName("A blank name, a name in use or one the files cannot hold is refused")
  @Test
  void refusesBadNames() throws Exception {
    final MapProject project = givenProjectWithOutlines();

    assertThat(List.of("  ", "red capitol", "a:b", "a<b", "a(b"))
        .as("nothing changes for these")
        .noneMatch(name -> project.rename(NORTH, name));
    assertThat(project.canUndo()).as("no step was made").isFalse();
  }

  @DisplayName("A territory never takes or gives up a nation's or a unit's name")
  @Test
  void refusesNamesOfNationsAndUnits() throws Exception {
    final MapProject project = givenProjectWithOutlines();

    final boolean renamed = project.rename(NORTH, "Russians");

    assertThat(renamed)
        .as("options name nations and territories alike, so a rename would change the nation")
        .isFalse();
    assertThat(project.rename(NORTH, "infantry")).as("the same for a unit").isFalse();
    assertThat(project.canUndo()).as("no step was made").isFalse();
  }

  @DisplayName("Saving after a rename writes the renamed outlines and centers")
  @Test
  void savesRenamedOutlines() throws Exception {
    final MapProject project = givenProjectWithOutlines();
    project.rename(NORTH, "Archangel");

    project.save();

    assertThat(PointFileReaderWriter.readOneToManyPolygons(folder.resolve("polygons.txt")))
        .as("polygons.txt under the new name")
        .containsOnlyKeys("Archangel");
    assertThat(PointFileReaderWriter.readOneToOne(folder.resolve("centers.txt")))
        .as("centers.txt under the new name")
        .containsOnlyKeys("Archangel");
    assertThat(MapProject.open(project.getXmlFile().orElseThrow()).game())
        .as("the game file has the new name")
        .satisfies(game -> assertThat(MapXml.territories(game)).contains("Archangel"));
  }

  @DisplayName("Imported names rename as one step; lines that cannot be taken are told with why")
  @Test
  void importsNames() throws Exception {
    final MapProject project = givenProjectWithOutlines();
    final List<String> lines =
        List.of(
            "# old;new",
            "",
            NORTH + ";Archangel",
            "red capitol = Moscow",
            "sea zone 1;",
            "red north 2,Moscow",
            "nowhere;Atlantis",
            "no separator");

    final Renamed renamed = project.renameAll(lines);

    assertThat(renamed.count()).as("two lines with a new name that is free").isEqualTo(2);
    assertThat(renamed.refused())
        .as("Moscow taken by then, no such territory, no separator; the rest skipped")
        .containsExactly(
            "red north 2,Moscow: the new name is taken, or holds : < or (",
            "nowhere;Atlantis: no territory is called nowhere",
            "no separator: no ; , or = between the old and the new name");
    assertThat(MapXml.territories(project.game())).as("renamed").contains("Archangel", "Moscow");
    assertThat(project.getFiles().polygons()).as("the outline too").containsOnlyKeys("Archangel");
    project.undo();
    assertThat(MapXml.territories(project.game()))
        .as("one undo takes all back")
        .contains(NORTH, "red capitol");
    assertThat(project.canUndo()).as("it was one step").isFalse();
  }

  @DisplayName("Exported names are lines to fill in that import renames nothing as they are")
  @Test
  void exportsNames() throws Exception {
    final MapProject project = givenProjectWithOutlines();

    final List<String> exported = project.nameList();

    assertThat(exported).as("one line per territory").hasSize(20).contains(NORTH + ";");
    assertThat(project.renameAll(exported))
        .as("no new names yet")
        .isEqualTo(new Renamed(0, List.of()));
    assertThat(project.canUndo()).as("no step").isFalse();
  }

  @DisplayName("A map without outlines opens as a list")
  @Test
  void opensWithoutOutlines() throws Exception {
    assertThat(givenMinimapProject().getFiles().hasOutlines())
        .as("the test resources have no polygons.txt")
        .isFalse();
  }

  @DisplayName("Removing a territory is one step; undo brings back its game and files")
  @Test
  void removesTerritoryAsOneStep() throws Exception {
    final MapProject project = givenProjectWithOutlines();
    final MapFiles files = project.getFiles();
    final Game game = project.game();

    final List<String> named = project.removeTerritory(NORTH);

    assertThat(named).as("nothing else names it").isEmpty();
    assertThat(MapXml.territories(project.game())).as("gone").doesNotContain(NORTH);
    assertThat(project.getFiles().polygons()).as("its outline too").doesNotContainKey(NORTH);
    project.undo();
    assertThat(project.game()).as("one undo brings the game back").isSameAs(game);
    assertThat(project.getFiles()).as("and the files").isSameAs(files);
    assertThat(project.canUndo()).as("it was one step").isFalse();
  }

  @DisplayName("A save after removing a territory leaves no line of it; the game still loads")
  @Test
  void savesRemovedTerritory() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    final String territory = MapXml.territories(project.game()).getFirst();
    final Path mapFolder = project.getFolder().orElseThrow();
    // its places found again and saved: the saved places no longer hold it, place.txt does
    project.findPlacesAgain(territory);
    project.save();

    project.removeTerritory(territory);
    project.save();

    assertThat(PointFileReaderWriter.readOneToManyPolygons(mapFolder.resolve("polygons.txt")))
        .as("no outline in polygons.txt")
        .doesNotContainKey(territory)
        .isNotEmpty();
    assertThat(PointFileReaderWriter.readOneToManyPlacements(mapFolder.resolve("place.txt")))
        .as("no line in place.txt, though the last save wrote one found")
        .doesNotContainKey(territory)
        .isNotEmpty();
    assertThat(MapChecks.findError(project.getXmlFile().orElseThrow()))
        .as("TripleA loads the game")
        .isEmpty();
  }

  @DisplayName("A territory the rules still name stays, and where it is named is told")
  @Test
  void refusesRemovingNamedTerritory() throws Exception {
    final MapProject project = givenMinimapProject();
    project.edit(
        g ->
            MapXml.withOption(
                g,
                new MapXml.AttachmentKey(
                    "rulesAttachment", RulesAttachment.class.getName(), "player", "Russians"),
                "directPresenceTerritories",
                NORTH));
    final Game game = project.game();

    final List<String> named = project.removeTerritory(NORTH);

    assertThat(named)
        .as("the option naming it")
        .containsExactly("rulesAttachment of Russians: directPresenceTerritories");
    assertThat(project.game()).as("nothing changed").isSameAs(game);
  }

  @DisplayName("A free name is the first of name, name 2, name 3 that nothing has")
  @Test
  void findsFreeName() throws Exception {
    final MapProject project = givenMinimapProject();

    final String free = project.freeName(NORTH, List.of());
    final String alsoTaken = project.freeName("Atlantis", List.of("Atlantis", "Atlantis 2"));

    assertThat(free).as("the territory has the name").isEqualTo(NORTH + " 2");
    assertThat(alsoTaken).as("names taken beside the game's").isEqualTo("Atlantis 3");
  }

  @Test
  @DisplayName("nameProblem says why a name is refused, nothing for a free or blank one")
  void nameProblemSaysWhy() throws Exception {
    // given
    final MapProject project = givenMinimapProject();

    // when / then
    assertThat(project.nameProblem("a:b"))
        .as("the files cannot hold ':'")
        .contains("Must not contain : < or (");
    assertThat(project.nameProblem(" Atlantis"))
        .as("spaces around the name")
        .contains("No spaces at the start or end");
    assertThat(project.nameProblem(NORTH))
        .as("a territory has the name")
        .contains("Already used by a territory, nation or unit");
    assertThat(project.nameProblem("Atlantis")).as("a free name").isEmpty();
    assertThat(project.nameProblem("  ")).as("blank: the button is off, no message").isEmpty();
  }
}
