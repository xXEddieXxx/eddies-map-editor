package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.triplea.attachments.RulesAttachment;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.map.game.notes.GameNotes;
import org.triplea.util.PointFileReaderWriter;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapFiles.Group;
import eddies.mapeditor.MapFiles.Redrawn;
import eddies.mapeditor.MapFiles.UnitPicture;
import eddies.mapeditor.MapFiles.UnitPicture.Coloring;
import eddies.mapeditor.MapPicture.DetectedTerritory;
import eddies.mapeditor.MapProject.RedrawChoices;
import eddies.mapeditor.MapProject.Renamed;

final class MapProjectTest {
  private static final String NORTH = "red north 1";
  // the areas of MapPictureTest.givenPicture, as Make territories names them
  private static final String TOP_LEFT = "Territory 1";
  private static final String TOP_RIGHT = "Territory 2";
  private static final String BOTTOM_RIGHT = "Territory 4";

  @TempDir Path folder;

  private MapProject givenMinimapProject() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        MapProjectTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  @DisplayName("Notes that are not UTF-8 open as TripleA shows them; only changed, they are saved")
  @Test
  void opensNotesInAnotherEncoding() throws Exception {
    // given notes as old maps have them: ISO-8859-1, which is no UTF-8 for the é
    givenMinimapProject();
    final Path xml = folder.resolve("minimap.xml");
    final Path notes = MapProject.notesFile(xml);
    final byte[] original =
        "<p>Café de la Paix</p>".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    Files.write(notes, original);

    // when
    final MapProject project = MapProject.open(xml);
    project.edit(game -> MapXml.withProduction(game, "red north 1", 9));
    project.save();

    // then
    assertThat(MapXml.notes(project.game()))
        .as("read as TripleA does: UTF-8, else ISO-8859-1")
        .isEqualTo("<p>Café de la Paix</p>");
    assertThat(notes).as("unchanged notes are not written again").hasBinaryContent(original);
    assertThat(MapFolder.backupOf(notes))
        .as("so there is no .bak")
        .doesNotExist();

    // when the map maker changes them
    project.edit(game -> MapXml.withNotes(game, "<p>Café Central</p>"));
    project.save();

    // then
    assertThat(Files.readString(notes))
        .as("changed notes are saved as UTF-8")
        .isEqualTo("<p>Café Central</p>");
    assertThat(MapFolder.backupOf(notes))
        .as("the original stays as .bak, byte for byte")
        .hasBinaryContent(original);
  }

  @DisplayName("The notes are kept in the notes file alone: those only the game file had move there")
  @Test
  void keepsNotesOnce() throws Exception {
    // given a map as old ones are: the notes in the game file, no notes file
    final MapProject project = givenMinimapProject();
    final Path xml = folder.resolve("minimap.xml");
    final Path notes = MapProject.notesFile(xml);
    final boolean before = Files.exists(notes);

    // when
    project.edit(game -> MapXml.withProduction(game, "red north 1", 9));
    project.save();

    // then
    assertThat(before).as("given: no notes file").isFalse();
    assertThat(GameNotes.loadGameNotes(xml))
        .as("the notes the game shows, in their file")
        .isEqualTo("A simple two player game.");
    assertThat(Files.readString(xml))
        .as("the game file holds no copy, which would go stale")
        .doesNotContain("\"notes\"")
        .doesNotContain("A simple two player game.");
    assertThat(MapXml.notes(MapProject.open(xml).game()))
        .as("opened again")
        .isEqualTo("A simple two player game.");
    assertThat(MapChecks.findError(xml)).as("the game still loads").isEmpty();
  }

  private MapProject givenSavedMapWithRed() throws Exception {
    return MapProject.newMap(
            "Island Wars", MapPictureTest.givenPicture(), List.of("Red"), folder.resolve("maps"))
        .project();
  }

  @DisplayName("A painted texture and its offset are map.properties steps, undone one by one")
  @Test
  void texturesAreSteps() throws Exception {
    // given
    final MapProject project = givenMinimapProject();

    // when
    project.editFiles(files -> files.withTexture(NORTH, Optional.of("Forest")));
    project.setTextureOffset("Forest", new Point(5, -3));
    final Properties painted = project.getFiles().properties();
    final Point offset = project.getFiles().textureOffset("Forest");
    project.undo();
    final Point undoneOffset = project.getFiles().textureOffset("Forest");
    final Map<String, String> beforeUndo = project.getFiles().textures();
    project.undo();

    // then
    assertThat(painted.getProperty(MapFiles.TEXTURE + NORTH))
        .as("the territory's texture, kept in map.properties")
        .isEqualTo("Forest");
    assertThat(painted.getProperty(MapFiles.TEXTURE_OFFSET + "Forest")).isEqualTo("5,-3");
    assertThat(offset).as("the offset as written").isEqualTo(new Point(5, -3));
    assertThat(undoneOffset).as("one undo takes the offset back").isEqualTo(new Point());
    assertThat(beforeUndo).as("and keeps the texture").containsEntry(NORTH, "Forest");
    assertThat(project.getFiles().textures()).as("the next takes the texture back").isEmpty();
  }

  @DisplayName("A renamed territory keeps its texture; a removed one's goes with it")
  @Test
  void textureFollowsTerritory() throws Exception {
    // given
    final MapProject project = givenMinimapProject();
    project.editFiles(files -> files.withTexture(NORTH, Optional.of("Forest")));

    // when
    project.rename(NORTH, "Archangel");
    final MapFiles removed = project.getFiles().withoutTerritory("Archangel");

    // then
    assertThat(project.getFiles().textures())
        .as("the texture under the new name only")
        .isEqualTo(Map.of("Archangel", "Forest"));
    assertThat(removed.textures()).as("no texture of a territory no more").isEmpty();
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

  @DisplayName("Rename gives the game the name, one step; the folder's name stays in the saved game")
  @Test
  void renameIsAStep() throws Exception {
    // given: a map made here, saved once under its name
    final MapProject project = givenSavedMapWithRed();

    // when
    project.rename(" Island Wars II ");
    final String renamed = project.name();
    project.save();
    final String xml = Files.readString(project.getXmlFile().orElseThrow());
    project.undo();

    // then
    assertThat(renamed).as("the name as typed, trimmed").isEqualTo("Island Wars II");
    assertThat(xml)
        .as("the game's name, and still the folder the game finds the map by")
        .contains("name=\"Island Wars II\"")
        .contains("value=\"Island Wars\"");
    assertThat(project.name()).as("undone, the name before").isEqualTo("Island Wars");
  }

  @DisplayName("A picture in the units folder itself is shown for every nation, as the game does")
  @Test
  void sharedUnitPictureInTheUnitsFolder() throws Exception {
    // given: a unit type the game has no picture of, drawn alike for all nations, eg a bunker
    final MapProject project = givenSavedMapWithRed();
    final Path shared = project.getFolder().orElseThrow().resolve("units/Industry-Hvy.png");
    Files.createDirectories(shared.getParent());
    ImageIO.write(new BufferedImage(9, 7, BufferedImage.TYPE_INT_ARGB), "png", shared.toFile());

    // when
    final Optional<Integer> width =
        project
            .unitPicture("Red", project.nationColor("Red"), "Industry-Hvy")
            .map(image -> image.getWidth(null));

    // then
    assertThat(width).as("the shared picture, not a badge").contains(9);
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
    final List<List<BufferedImage>> steps =
        List.of(List.of(shared), List.of(shared), List.of(), List.of(older), List.of(oldest));

    // when
    final int kept = MapProject.keptSteps(steps, List.of(), 800);
    final int keptBesideCurrent = MapProject.keptSteps(steps, List.of(shared), 800);

    // then
    assertThat(kept).as("shared counted once, then older: 800 bytes; oldest goes").isEqualTo(4);
    assertThat(keptBesideCurrent)
        .as("the current picture is not counted, so oldest fits too")
        .isEqualTo(5);
  }

  @DisplayName("A relief held with a step's picture counts for the undo limit too, once")
  @Test
  void undoCountsHeldReliefs() {
    // given: 10x10 pictures of 400 bytes each; two steps share picture and relief
    final BufferedImage picture = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
    final BufferedImage relief = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
    final BufferedImage olderPicture = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
    final BufferedImage olderRelief = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
    final List<List<BufferedImage>> steps =
        List.of(
            List.of(picture, relief),
            List.of(picture, relief),
            List.of(olderPicture, olderRelief));

    // when
    final int kept = MapProject.keptSteps(steps, List.of(), 1200);

    // then
    assertThat(kept)
        .as("picture and relief take 800 bytes; the older step's two would make 1600")
        .isEqualTo(2);
  }

  @DisplayName("A unit type kept in its own colors is not tinted; tinted again, the entry goes")
  @Test
  void unitTypeKeepsItsColors() throws Exception {
    // given: a nation's units tinted, another unit type already kept as it is
    final MapProject project = givenMinimapProject();
    final String nation = MapXml.nations(project.game()).getFirst();
    final BufferedImage white = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
    white.setRGB(0, 0, 0xFFFFFFFF);
    project.setNationUnitPictures(nation, Map.of("infantry", white));
    project.tintUnits(List.of(nation));
    project.setMapProperty(MapFiles.UNTINTED_UNITS, "factory");

    // when
    project.keepUnitTypeColors("infantry");
    final MapFiles kept = project.getFiles();
    final Image shown =
        project.unitPicture(nation, project.nationColor(nation), "infantry").orElseThrow();
    project.undo();
    final MapFiles undone = project.getFiles();
    project.keepUnitTypeColors("infantry");
    project.tintUnitType("infantry");
    project.tintUnitType("factory");

    // then
    assertThat(kept.properties().getProperty(MapFiles.UNTINTED_UNITS))
        .as("added after the others, as the game reads it")
        .isEqualTo("factory,infantry");
    assertThat(((BufferedImage) shown).getRGB(0, 0)).as("drawn as it is").isEqualTo(0xFFFFFFFF);
    assertThat(undone.properties().getProperty(MapFiles.UNTINTED_UNITS))
        .as("one undo step")
        .isEqualTo("factory");
    assertThat(project.getFiles().properties())
        .as("none kept: the entry goes")
        .doesNotContainKey(MapFiles.UNTINTED_UNITS);
  }

  @DisplayName("An uploaded picture the same for all nations is kept from the game's tint")
  @Test
  void samePictureForAllIsUntinted() throws Exception {
    // given: the map keeps a factory as it is already
    final MapProject project = givenMinimapProject();
    project.setMapProperty(MapFiles.UNTINTED_UNITS, "factory");
    final BufferedImage picture = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);

    // when
    project.setUnitPicture("factory", new UnitPicture(picture, Coloring.PER_NATION));
    final List<String> uploaded = project.getFiles().untintedUnits();
    project.setUnitPicture("infantry", new UnitPicture(picture, Coloring.AS_IS));
    final List<String> asIs = project.getFiles().untintedUnits();
    project.setUnitPicture("infantry", new UnitPicture(picture, Coloring.PER_NATION));

    // then
    assertThat(uploaded).as("an upload keeps the map's entry").containsExactly("factory");
    assertThat(asIs).as("the same for all: never tinted").containsExactly("factory", "infantry");
    assertThat(project.getFiles().untintedUnits())
        .as("switched to colored per nation: tinted again")
        .containsExactly("factory");
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
        project.takeRedrawn(
            from, redrawn, new RedrawChoices(Map.of(area, east), Map.of(), Set.of()));

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
        project.takeRedrawn(
            from,
            redrawn,
            new RedrawChoices(Map.of(), Map.of(area, TOP_RIGHT), Set.of()));

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

  @DisplayName("A merge through a gap kept as it was: both territories exactly as before; one step")
  @Test
  void keepsMergedAreaAsItWas() throws Exception {
    // given: a gap in the border between the two top territories runs them together
    final MapProject project = givenSavedMapWithRed();
    final MapFiles from = project.getFiles();
    final Redrawn redrawn = givenRedrawn(from, MapFilesTest.givenPictureWithGap());

    // when
    project.takeRedrawn(from, redrawn, keeping(redrawn.groups()));

    // then
    assertThat(redrawn.groups()).as("the merge is one change").hasSize(1);
    assertThat(MapXml.territories(project.game()))
        .as("none removed")
        .contains(TOP_LEFT, TOP_RIGHT);
    for (final String member : List.of(TOP_LEFT, TOP_RIGHT)) {
      assertThat(project.getFiles().polygons().get(member))
          .as("the old outline of " + member)
          .isEqualTo(from.polygons().get(member));
      assertThat(project.getFiles().centers().get(member))
          .as("the old center of " + member)
          .isEqualTo(from.centers().get(member));
      assertThat(project.getFiles().places().units().get(member))
          .as("the old unit places of " + member)
          .isEqualTo(from.places().units().get(member));
    }
    assertThat(project.getFiles().picture()).as("the picture as drawn").isPresent();
    project.undo();
    assertThat(project.getFiles()).as("one undo gives back the files before").isSameAs(from);
  }

  @DisplayName("A bite out of the middle of a side is a change; kept, the outline is the old one")
  @Test
  void keepsBiteAsItWas() throws Exception {
    // given: a box drawn against the bottom of the top left territory, its bounds the same
    final MapProject project = givenSavedMapWithRed();
    final MapFiles from = project.getFiles();
    final BufferedImage bitten = MapPictureTest.givenPicture();
    final Graphics2D g = bitten.createGraphics();
    g.setColor(Color.BLACK);
    g.fillRect(70, 60, 60, 3);
    g.fillRect(70, 60, 3, 50);
    g.fillRect(127, 60, 3, 50);
    g.dispose();
    final Redrawn redrawn = givenRedrawn(from, bitten);
    final List<String> before = MapXml.territories(project.game());

    // when
    project.takeRedrawn(from, redrawn, keeping(redrawn.groups()));

    // then
    assertThat(redrawn.groups())
        .as("the bitten territory and the bite are one change")
        .singleElement()
        .matches(group -> group.territories().contains(TOP_LEFT), "with the top left one")
        .matches(group -> group.added().size() == 1, "and the bite");
    assertThat(project.getFiles().polygons().get(TOP_LEFT))
        .as("kept: exactly the old outline")
        .isEqualTo(from.polygons().get(TOP_LEFT));
    assertThat(MapXml.territories(project.game()))
        .as("nothing added")
        .containsExactlyElementsOf(before);
  }

  @DisplayName("A merge and a moved border beside it are two changes; either kept: no hole")
  @Test
  void keepsMergeOrNeighbour() throws Exception {
    // given: the top border rubbed out and the bottom border between land moved to x 250
    final MapProject project = givenSavedMapWithRed();
    final MapFiles from = project.getFiles();
    final Redrawn redrawn = givenRedrawn(from, givenPictureMergedAndMoved());

    // then
    assertThat(redrawn.groups())
        .as("no land moves between the merge and the moved border: two changes")
        .hasSize(2);
    for (final Group kept : redrawn.groups()) {
      // when one is kept, the other taken
      project.takeRedrawn(from, redrawn, keeping(List.of(kept)));

      // then
      assertThat(kept.territories())
          .as("the kept one exactly as before")
          .allMatch(t -> project.getFiles().polygons().get(t).equals(from.polygons().get(t)));
      final Area uncovered = union(from);
      uncovered.subtract(union(project.getFiles()));
      assertThat(isThickerThanALine(uncovered))
          .as("the territories cover the map as before: no hole")
          .isFalse();
      assertThat(widestOverlap(project.getFiles()))
          .as("they overlap only along the lines between them")
          .isLessThanOrEqualTo(4);
      project.undo();
    }
  }

  @DisplayName("A new area reaching from beyond the old map into a territory joins it: no hole")
  @Test
  void straddlingAreaJoinsTerritory() throws Exception {
    // given: the old sea zone only from x 50, and a new line across the sea at x 100
    final MapProject project = givenSavedMapWithRed();
    project.editFiles(
        files ->
            files.withOutlines(
                "Sea Zone 1",
                List.of(
                    new Polygon(
                        new int[] {50, 400, 400, 50}, new int[] {220, 220, 300, 300}, 4))));
    final MapFiles from = project.getFiles();
    final BufferedImage picture = MapPictureTest.givenPicture();
    final Graphics2D g = picture.createGraphics();
    g.setColor(Color.BLACK);
    g.fillRect(100, 222, 3, 78);
    g.dispose();
    final Redrawn redrawn = givenRedrawn(from, picture);
    final DetectedTerritory west = redrawn.added().getFirst();

    // then
    assertThat(redrawn.groups())
        .as("the area and the sea zone it was cut from are one change")
        .singleElement()
        .matches(group -> group.territories().contains("Sea Zone 1"), "the sea zone")
        .matches(group -> group.added().contains(west), "the area");
    for (final boolean keep : List.of(true, false)) {
      // when
      project.takeRedrawn(
          from,
          redrawn,
          keep
              ? keeping(redrawn.groups())
              : new RedrawChoices(
                  Map.of(west, new DetectedTerritory("West", true, west.outlines(), west.center())),
                  Map.of(),
                  Set.of()));

      // then
      final Area uncovered = union(from);
      uncovered.subtract(union(project.getFiles()));
      assertThat(isThickerThanALine(uncovered)).as("no hole, kept " + keep).isFalse();
      assertThat(widestOverlap(project.getFiles()))
          .as("no overlap, kept " + keep)
          .isLessThanOrEqualTo(4);
      project.undo();
    }
  }

  @DisplayName("A merged area no territory took that runs into another is one change with it")
  @Test
  void mergeRunningIntoAnotherIsOneChange() throws Exception {
    // given: five strips over a bottom band; the strips' lines rubbed out, and through a gap in
    // the line below them they take the top of the band, cut off by a new line
    final MapProject project =
        MapProject.newMap("Strips", givenStrips(), List.of("Red"), folder.resolve("strips"))
            .project();
    final MapFiles from = project.getFiles();
    final BufferedImage picture = givenStripsRunningIntoBand();
    final Redrawn redrawn = givenRedrawn(from, picture);

    // then
    assertThat(redrawn.merged().values())
        .as("the five strips are one area none of them took")
        .singleElement()
        .matches(members -> members.size() == 5, "five");
    assertThat(redrawn.groups())
        .as("the band, which lost its top to the merge, is in the same change")
        .singleElement()
        .matches(group -> group.territories().size() == 6, "all six")
        .matches(group -> !group.moves().isEmpty(), "the band's top moving");
    for (final boolean keep : List.of(true, false)) {
      // when
      project.takeRedrawn(
          from, redrawn, keep ? keeping(redrawn.groups()) : RedrawChoices.none());

      // then
      final Area uncovered = union(from);
      uncovered.subtract(union(project.getFiles()));
      assertThat(isThickerThanALine(uncovered)).as("no hole, kept " + keep).isFalse();
      assertThat(widestOverlap(project.getFiles()))
          .as("no overlap, kept " + keep)
          .isLessThanOrEqualTo(4);
      project.undo();
    }
  }

  /** Five strips of 100 px over a band, 500 x 200, drawn with black lines. */
  private static BufferedImage givenStrips() {
    final BufferedImage picture = new BufferedImage(500, 200, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = picture.createGraphics();
    g.setColor(new Color(230, 180, 180));
    g.fillRect(0, 0, 500, 150);
    g.setColor(new Color(240, 230, 150));
    g.fillRect(0, 150, 500, 50);
    g.setColor(Color.BLACK);
    for (int x = 99; x < 500; x += 100) {
      g.fillRect(x, 0, 3, 150);
    }
    g.fillRect(0, 149, 500, 3);
    g.dispose();
    return picture;
  }

  /** The strips' lines rubbed out, a gap below them and a new line across the band at y 175. */
  private static BufferedImage givenStripsRunningIntoBand() {
    final BufferedImage picture = givenStrips();
    final Graphics2D g = picture.createGraphics();
    g.setColor(new Color(230, 180, 180));
    g.fillRect(0, 0, 500, 149);
    g.fillRect(220, 149, 20, 3);
    g.setColor(Color.BLACK);
    g.fillRect(0, 175, 500, 3);
    g.dispose();
    return picture;
  }

  @DisplayName("A merge and a changed neighbour taken: the merge and the new border as before")
  @Test
  void takesMergeWithNeighbour() throws Exception {
    // given
    final MapProject project = givenSavedMapWithRed();
    final MapFiles from = project.getFiles();
    final Redrawn redrawn = givenRedrawn(from, givenPictureMergedAndMoved());
    final DetectedTerritory area = redrawn.merged().keySet().iterator().next();

    // when
    project.takeRedrawn(from, redrawn, RedrawChoices.none());

    // then
    assertThat(MapXml.territories(project.game()))
        .as("the second of the merge is gone")
        .doesNotContain(redrawn.merged().get(area).getLast());
    assertThat(project.getFiles().polygons().get(redrawn.merged().get(area).getFirst()))
        .as("the first has the merged area")
        .isEqualTo(area.outlines());
    assertThat(project.getFiles().polygons().get("Territory 4"))
        .as("the moved border is taken")
        .isEqualTo(redrawn.files().polygons().get("Territory 4"));
  }

  @DisplayName("Two changes apart are two groups: one kept as it was, the other taken")
  @Test
  void keepsOneGroupTakesAnother() throws Exception {
    // given: a new line in the top left territory and one in the bottom right territory
    final MapProject project = givenSavedMapWithRed();
    final MapFiles from = project.getFiles();
    final BufferedImage picture = MapFilesTest.givenPictureWithNewBorder();
    final Graphics2D g = picture.createGraphics();
    g.setColor(Color.BLACK);
    g.fillRect(60, 0, 3, 110);
    g.dispose();
    final Redrawn redrawn = givenRedrawn(from, picture);
    final Group topLeft =
        redrawn.groups().stream()
            .filter(gr -> gr.territories().contains(TOP_LEFT))
            .findFirst()
            .orElseThrow();
    final Group bottomRight =
        redrawn.groups().stream().filter(gr -> gr != topLeft).findFirst().orElseThrow();
    final DetectedTerritory east = bottomRight.added().getFirst();

    // when
    project.takeRedrawn(
        from,
        redrawn,
        new RedrawChoices(
            Map.of(east, new DetectedTerritory("East", false, east.outlines(), east.center())),
            Map.of(),
            Set.of(topLeft)));

    // then
    assertThat(redrawn.groups()).as("two changes apart").hasSize(2);
    assertThat(project.getFiles().polygons().get(TOP_LEFT))
        .as("kept as it was")
        .isEqualTo(from.polygons().get(TOP_LEFT));
    assertThat(project.getFiles().polygons().get("East")).as("taken: added").isNotNull();
    assertThat(project.getFiles().polygons().get("Territory 4"))
        .as("taken: its new border")
        .isEqualTo(redrawn.files().polygons().get("Territory 4"));
  }

  @DisplayName("A border 5 px off beside a split kept as it was joins it: no hole, no overlap")
  @Test
  void keepsShiftBesideSplit() throws Exception {
    // given: a new line in the top left territory, its border with the top right one 5 px off
    final MapProject project = givenSavedMapWithRed();
    final MapFiles from = project.getFiles();
    final BufferedImage picture = MapFilesTest.givenPictureWithBorderAt(204);
    final Graphics2D g = picture.createGraphics();
    g.setColor(Color.BLACK);
    g.fillRect(60, 0, 3, 110);
    g.dispose();
    final Redrawn redrawn = givenRedrawn(from, picture);
    final Group split =
        redrawn.groups().stream()
            .filter(group -> group.territories().contains(TOP_LEFT))
            .findFirst()
            .orElseThrow();

    // when
    project.takeRedrawn(from, redrawn, keeping(List.of(split)));

    // then
    assertThat(split.territories()).as("the shifted neighbour is in it").contains(TOP_RIGHT);
    final Area uncovered = union(from);
    uncovered.subtract(union(project.getFiles()));
    assertThat(isThickerThanALine(uncovered)).as("no hole").isFalse();
    assertThat(widestOverlap(project.getFiles()))
        .as("no overlap beyond the lines")
        .isLessThanOrEqualTo(4);
  }

  @DisplayName("A moved border kept as it was stays old on both sides: no hole, no overlap")
  @Test
  void keepsMovedBorder() throws Exception {
    // given: the border between the two top territories moved to x 299
    final MapProject project = givenSavedMapWithRed();
    final MapFiles from = project.getFiles();
    final Redrawn redrawn = givenRedrawn(from, MapFilesTest.givenPictureWithBorderAt(299));

    // when
    project.takeRedrawn(from, redrawn, keeping(redrawn.groups()));

    // then
    assertThat(redrawn.groups())
        .as("both sides one change")
        .singleElement()
        .matches(group -> group.territories().containsAll(List.of(TOP_LEFT, TOP_RIGHT)), "both");
    assertThat(project.getFiles().polygons().get(TOP_LEFT))
        .as("its old outline")
        .isEqualTo(from.polygons().get(TOP_LEFT));
    assertThat(project.getFiles().polygons().get(TOP_RIGHT))
        .as("and the other's")
        .isEqualTo(from.polygons().get(TOP_RIGHT));
    assertThat(widestOverlap(project.getFiles()))
        .as("no overlap beyond the lines")
        .isLessThanOrEqualTo(4);
    project.undo();
    assertThat(project.getFiles()).as("one step").isSameAs(from);
  }

  private static Redrawn givenRedrawn(final MapFiles from, final BufferedImage picture) {
    return from.withBordersFrom(picture, MapPicture.detect(picture));
  }

  private static RedrawChoices keeping(final List<Group> groups) {
    return new RedrawChoices(Map.of(), Map.of(), Set.copyOf(groups));
  }

  /** The top border rubbed out, and the border between the bottom land moved to x 250. */
  private static BufferedImage givenPictureMergedAndMoved() {
    final BufferedImage picture = MapFilesTest.givenPictureWithBorderAt(500);
    final Graphics2D g = picture.createGraphics();
    g.setColor(new Color(240, 230, 150));
    g.fillRect(199, 112, 3, 107);
    g.setColor(Color.BLACK);
    g.fillRect(250, 112, 3, 107);
    g.dispose();
    return picture;
  }

  private static Area union(final MapFiles files) {
    final Area all = new Area();
    files.polygons().values().forEach(outlines -> outlines.forEach(o -> all.add(new Area(o))));
    return all;
  }

  /** Whether the area is 3 px across somewhere: a pixel of it with all 8 around it in it too. */
  private static boolean isThickerThanALine(final Area area) {
    final BufferedImage mask = new BufferedImage(400, 300, BufferedImage.TYPE_BYTE_BINARY);
    final Graphics2D g = mask.createGraphics();
    g.setColor(Color.WHITE);
    g.fill(area);
    g.dispose();
    for (int y = 1; y < 299; y++) {
      for (int x = 1; x < 399; x++) {
        boolean all = true;
        for (int dy = -1; dy <= 1 && all; dy++) {
          for (int dx = -1; dx <= 1 && all; dx++) {
            all = (mask.getRGB(x + dx, y + dy) & 0xFFFFFF) != 0;
          }
        }
        if (all) {
          return true;
        }
      }
    }
    return false;
  }

  /** The widest overlap of two territories, across its narrow side. */
  private static int widestOverlap(final MapFiles files) {
    final List<String> names = List.copyOf(files.polygons().keySet());
    int widest = 0;
    for (int i = 0; i < names.size(); i++) {
      for (int j = i + 1; j < names.size(); j++) {
        final Area overlap = new Area();
        files.polygons().get(names.get(i)).forEach(o -> overlap.add(new Area(o)));
        final Area other = new Area();
        files.polygons().get(names.get(j)).forEach(o -> other.add(new Area(o)));
        overlap.intersect(other);
        final Rectangle box = overlap.getBounds();
        widest = Math.max(widest, overlap.isEmpty() ? 0 : Math.min(box.width, box.height));
      }
    }
    return widest;
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

  @DisplayName("Make territories after two lines in a corner looks only there; far ones stay")
  @Test
  void redrawsOnlyWhereBordersWereDrawn() throws Exception {
    // given
    final MapProject project = givenCornerDrawnAfterFarArea();
    final MapFiles from = project.getFiles();
    final BufferedImage picture = from.picture().orElseThrow();

    // when
    final Redrawn redrawn = from.withBordersFrom(picture, project.drawnRegion().orElseThrow());

    // then
    assertThat(redrawn.added())
        .as("the two new areas in the top right corner, not the far one bottom left")
        .hasSize(2)
        .allMatch(a -> a.center().x > 200 && a.center().y < 110);
    assertThat(redrawn.files().polygons().get("Territory 3"))
        .as("a far territory keeps its very outlines")
        .isSameAs(from.polygons().get("Territory 3"));
    assertThat(redrawn.files().polygons().get("Sea Zone 1"))
        .as("the sea below too")
        .isSameAs(from.polygons().get("Sea Zone 1"));
    assertThat(redrawn.changed()).as("only the split one changed").containsExactly(TOP_RIGHT);
  }

  @DisplayName("Borders redrawn from a whole picture still look at the whole map")
  @Test
  void redrawsWholeMapFromPicture() throws Exception {
    // given
    final MapFiles from = givenCornerDrawnAfterFarArea().getFiles();
    final BufferedImage picture = from.picture().orElseThrow();

    // when
    final Redrawn redrawn = from.withBordersFrom(picture, MapPicture.detect(picture));

    // then
    assertThat(redrawn.added())
        .as("the far area bottom left left out before, and the two in the corner")
        .hasSize(3)
        .anyMatch(a -> a.center().x < 200 && a.center().y > 110);
  }

  @DisplayName("A relief made anew after Make territories joins its step; undo takes both back")
  @Test
  void remadeReliefJoinsTheTerritoriesStep() throws Exception {
    // given
    final MapProject project = givenSavedMapWithRed();
    final BufferedImage old = givenRelief(project);
    project.setRelief(old, new ReliefMaker.Look(50, EnumSet.noneOf(ReliefMaker.Touch.class)));
    project.eraseBorders(List.of(new Point(200, 20), new Point(200, 90)), 9);
    final MapFiles drawn = project.getFiles();
    project.takeRedrawn(
        drawn,
        drawn.withBordersFrom(drawn.picture().orElseThrow(), project.drawnRegion().orElseThrow()),
        RedrawChoices.none());
    final MapFiles made = project.getFiles();
    final BufferedImage remade = givenRelief(project);

    // when
    final boolean taken = project.takeRemadeRelief(made, remade);
    final boolean late = project.takeRemadeRelief(made, givenRelief(project));
    final Optional<BufferedImage> shown = project.getFiles().relief();
    project.undo();

    // then
    assertThat(taken).as("the files it was made for").isTrue();
    assertThat(late).as("the map changed since").isFalse();
    assertThat(shown).as("the remade relief").containsSame(remade);
    assertThat(project.getFiles()).as("one undo: the territories and the relief").isSameAs(drawn);
    assertThat(project.getFiles().relief()).as("the old relief again").containsSame(old);
  }

  private static BufferedImage givenRelief(final MapProject project) {
    final Dimension size = project.getFiles().size();
    return new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
  }

  @DisplayName("Undo of a drawn line shrinks where Make territories looks; making them resets it")
  @Test
  void drawnRegionFollowsUndo() throws Exception {
    // given
    final MapProject project = givenSavedMapWithRed();
    project.drawBorder(givenBorder(300, 0, 300, 110));
    final Optional<Rectangle> first = project.drawnRegion();
    project.drawBorder(givenBorder(100, 110, 100, 220));
    final Optional<Rectangle> both = project.drawnRegion();
    final MapFiles from = project.getFiles();
    final BufferedImage picture = from.picture().orElseThrow();
    project.takeRedrawn(
        from, from.withBordersFrom(picture, both.orElseThrow()), RedrawChoices.none());
    final Optional<Rectangle> made = project.drawnRegion();

    // when
    project.undo();
    final Optional<Rectangle> unmade = project.drawnRegion();
    project.undo();

    // then
    assertThat(first.orElseThrow().contains(300, 50)).as("around the first line").isTrue();
    assertThat(both.orElseThrow().contains(100, 150)).as("grown by the second").isTrue();
    assertThat(made).as("territories made: nothing drawn since").isEmpty();
    assertThat(unmade).as("making them undone: drawn again").isEqualTo(both);
    assertThat(project.drawnRegion()).as("the second line undone").isEqualTo(first);
    project.undo();
    assertThat(project.drawnRegion()).as("no line drawn").isEmpty();
  }

  @DisplayName("A left-out area beyond the box around the territories drawn at is found whole")
  @Test
  void findsLeftOutAreaWhole() throws Exception {
    // given: bottom left territory 3 removed, its area 0..199 x 110..220 in no territory
    final MapProject project = givenSavedMapWithRed();
    project.removeTerritory("Territory 3");
    project.drawBorder(givenBorder(50, 110, 50, 140));
    final MapFiles from = project.getFiles();

    // when
    final Redrawn redrawn =
        from.withBordersFrom(from.picture().orElseThrow(), project.drawnRegion().orElseThrow());

    // then
    assertThat(redrawn.added())
        .as("the left-out area, reaching down to the sea, not cut where the search box ended")
        .singleElement()
        .satisfies(
            area ->
                assertThat(area.outlines().getFirst().getBounds().getMaxY())
                    .as("its bottom on the line above the sea")
                    .isGreaterThan(215));
  }

  @DisplayName("A new area made where borders were drawn connects to a territory beyond them")
  @Test
  void connectsNewAreaBeyondRegion() throws Exception {
    // given: top right territory 2 split at x 250, its narrow west strip new, beside territory 1
    final MapProject project = givenSavedMapWithRed();
    project.drawBorder(givenBorder(250, 0, 250, 110));
    final MapFiles from = project.getFiles();
    final Rectangle region = project.drawnRegion().orElseThrow();
    final Redrawn redrawn = from.withBordersFrom(from.picture().orElseThrow(), region);
    final DetectedTerritory area = redrawn.added().getFirst();

    // when
    project.takeRedrawn(
        from,
        redrawn,
        new RedrawChoices(
            Map.of(area, new DetectedTerritory("West", false, area.outlines(), area.center())),
            Map.of(),
            Set.of()));

    // then
    assertThat(from.polygons().get(TOP_LEFT))
        .as("territory 1 lies beyond the region")
        .noneMatch(o -> o.intersects(region));
    assertThat(MapXml.neighbors(project.game(), "West"))
        .as("its neighbour across the old border")
        .contains(TOP_LEFT, TOP_RIGHT);
  }

  @DisplayName("A split's new part keeps the territory's texture")
  @Test
  void splitKeepsTexture() throws Exception {
    // given
    final MapProject project = givenSavedMapWithRed();
    project.editFiles(files -> files.withTexture("Territory 4", Optional.of("Desert")));
    final OutlineCut.Cut cut =
        OutlineCut.cut(
                project.getFiles().polygons().get("Territory 4"),
                List.of(new Point(150, 160), new Point(450, 160)))
            .orElseThrow();

    // when
    project.splitTerritory("Territory 4", cut, "South");

    // then
    assertThat(project.getFiles().textures())
        .as("both parts in the desert")
        .containsEntry("Territory 4", "Desert")
        .containsEntry("South", "Desert");
  }

  @DisplayName("Each line drawn or erased is kept with its kind; undo and redo follow")
  @Test
  void keepsStrokes() throws Exception {
    // given
    final MapProject project = givenSavedMapWithRed();

    // when
    project.drawBorder(givenBorder(100, 110, 100, 220));
    project.eraseBorders(List.of(new Point(200, 20), new Point(200, 90)), 9);
    final List<MapProject.Stroke> both = project.drawnStrokes();
    project.undo();
    final List<MapProject.Stroke> first = project.drawnStrokes();
    project.redo();

    // then
    assertThat(both)
        .as("the pencil line, then the eraser")
        .extracting(MapProject.Stroke::kind)
        .containsExactly(MapProject.Kind.DRAWN, MapProject.Kind.ERASED);
    assertThat(both.getFirst().area().contains(100, 150)).as("where it was drawn").isTrue();
    assertThat(first).as("undo takes the eraser back").hasSize(1);
    assertThat(project.drawnStrokes()).as("redo brings it").hasSize(2);
  }

  @DisplayName("A whole border erased with a click is kept as erased, its pixels as the stroke")
  @Test
  void keepsErasedBorder() throws Exception {
    // given
    final MapProject project = givenSavedMapWithRed();

    // when
    project.eraseBorder(project.getFiles().sharedBorderAt(new Point(200, 50), 3).orElseThrow());

    // then
    assertThat(project.drawnStrokes())
        .as("one stroke, erased, on the border")
        .singleElement()
        .matches(stroke -> stroke.kind() == MapProject.Kind.ERASED, "erased")
        .matches(stroke -> stroke.area().contains(200.5, 50.5), "on the border");
  }

  @DisplayName("A resize scales the lines drawn with the map")
  @Test
  void resizeScalesStrokes() throws Exception {
    // given
    final MapProject project = givenSavedMapWithRed();
    project.drawBorder(givenBorder(100, 110, 100, 220));
    final MapFiles from = project.getFiles();

    // when
    project.takeResized(from, from.resized(2), 2);

    // then
    assertThat(project.drawnStrokes())
        .as("twice as far out")
        .singleElement()
        .matches(stroke -> stroke.area().contains(200, 300), "on the line, scaled")
        .matches(stroke -> stroke.region().contains(200, 300), "its region too");
  }

  @DisplayName("Lines go when all changes are taken or a picture is loaded; kept ones stay")
  @Test
  void forgetsStrokesOnlyWhenTaken() throws Exception {
    // given: lines splitting the top left territory and the bottom right one
    final MapProject project = givenSavedMapWithRed();
    project.drawBorder(givenBorder(60, 0, 60, 110));
    project.drawBorder(givenBorder(330, 110, 330, 220));
    final MapFiles from = project.getFiles();
    final BufferedImage picture = from.picture().orElseThrow();
    final Redrawn redrawn = from.withBordersFrom(picture, MapPicture.detect(picture));
    final Group topLeft =
        redrawn.groups().stream()
            .filter(group -> group.territories().contains(TOP_LEFT))
            .findFirst()
            .orElseThrow();

    // when one change is kept
    project.takeRedrawn(from, redrawn, keeping(List.of(topLeft)));

    // then
    assertThat(project.drawnStrokes())
        .as("the line of the change kept stays")
        .singleElement()
        .matches(stroke -> stroke.area().contains(60, 50), "the top left one");
    assertThat(project.drawnRegion())
        .as("so Make territories looks there again")
        .hasValueSatisfying(r -> assertThat(r.contains(60, 50)).as("around it").isTrue());

    // when all are taken
    final MapFiles again = project.getFiles();
    final BufferedImage now = again.picture().orElseThrow();
    project.takeRedrawn(
        again, again.withBordersFrom(now, MapPicture.detect(now)), RedrawChoices.none());

    // then
    assertThat(project.drawnStrokes()).as("all taken: none left").isEmpty();

    // when a picture is loaded after a line
    project.drawBorder(givenBorder(100, 110, 100, 220));
    project.loadPicture(MapPictureTest.givenPicture());

    // then
    assertThat(project.drawnStrokes()).as("a new picture forgets them").isEmpty();
  }

  @DisplayName("A split drawn into the held picture counts as drawn for Make territories")
  @Test
  void splitLineIsDrawnRegion() throws Exception {
    // given
    final MapProject project = givenSavedMapWithRed();
    final List<Point> line = List.of(new Point(150, 160), new Point(450, 160));
    final OutlineCut.Cut cut =
        OutlineCut.cut(project.getFiles().polygons().get("Territory 4"), line).orElseThrow();

    // when
    project.splitTerritory(
        "Territory 4", cut, "South", Optional.of(new MapProject.BorderLine(line, 3, Color.BLACK)));

    // then
    assertThat(project.drawnStrokes())
        .as("the cut line, drawn")
        .singleElement()
        .matches(stroke -> stroke.kind() == MapProject.Kind.DRAWN, "drawn")
        .matches(stroke -> stroke.area().contains(300, 160), "on the line");
    assertThat(project.drawnRegion())
        .as("around the cut line")
        .hasValueSatisfying(
            region -> assertThat(region.contains(300, 160)).as("on the line").isTrue());
  }

  /**
   * The saved map with a line drawn across bottom left territory 3, territories made without the new
   * area, then two lines splitting top right territory 2 in three.
   */
  private MapProject givenCornerDrawnAfterFarArea() throws Exception {
    final MapProject project = givenSavedMapWithRed();
    project.drawBorder(givenBorder(100, 110, 100, 220));
    final MapFiles drawn = project.getFiles();
    project.takeRedrawn(
        drawn,
        drawn.withBordersFrom(
            drawn.picture().orElseThrow(), MapPicture.detect(drawn.picture().orElseThrow())),
        RedrawChoices.none());
    project.drawBorder(givenBorder(300, 0, 300, 110));
    project.drawBorder(givenBorder(300, 55, 400, 55));
    return project;
  }

  private static MapProject.BorderLine givenBorder(
      final int x1, final int y1, final int x2, final int y2) {
    return new MapProject.BorderLine(
        List.of(new Point(x1, y1), new Point(x2, y2)), 3, Color.BLACK);
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
    assertThat(Files.readString(MapFolder.backupOf(folder.resolve("minimap.xml"))))
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
        .as("the minimap is fine; its missing polygons.txt is the guide's first step")
        .isEmpty();

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
        .as("an unknown unit type, and the engine refuses it")
        .extracting(Problem::text)
        .contains(NORTH + " has units of no type: tank")
        .doesNotContain(NORTH + " is not connected to any territory")
        .anyMatch(text -> text.startsWith("The game does not load"));
    assertThat(MapChecks.notes(project.game()))
        .as("all neighbours removed: worth a look, no problem")
        .extracting(Problem::text)
        .containsExactly(NORTH + " is not connected to any territory");
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

  @DisplayName("Merging gives the keeper the other's outlines and connections, as one step")
  @Test
  void mergesTerritories() throws Exception {
    // given: the top left land, Red's, a unit place and its name moved by hand, and the bottom
    // right one, diagonal to it
    final MapProject project = givenSavedMapWithRed();
    project.edit(g -> MapXml.withOwner(g, TOP_LEFT, "Red"));
    project.setUnitPlaces(TOP_LEFT, List.of(new Point(31, 17)));
    project.setMarkerPlace(MapFiles.Marker.NAME, TOP_LEFT, 0, new Point(13, 29));
    final Game game = project.game();
    final MapFiles files = project.getFiles();

    // when
    final List<String> named = project.mergeTerritories(TOP_LEFT, BOTTOM_RIGHT);

    // then
    assertThat(named).as("merged").isEmpty();
    assertThat(MapXml.territories(project.game()))
        .as("the other is gone")
        .doesNotContain(BOTTOM_RIGHT);
    assertThat(project.getFiles().polygons().get(TOP_LEFT))
        .as("the keeper's outline, then the other's")
        .containsExactly(
            files.polygons().get(TOP_LEFT).getFirst(),
            files.polygons().get(BOTTOM_RIGHT).getFirst());
    assertThat(project.getFiles().polygons())
        .as("no outline of its own")
        .doesNotContainKey(BOTTOM_RIGHT);
    assertThat(MapXml.neighbors(project.game(), TOP_LEFT))
        .as("its own neighbours and the other's, not itself")
        .containsExactlyInAnyOrder(TOP_RIGHT, "Territory 3", "Sea Zone 1");
    assertThat(
            project.game().getMap().getConnections().stream()
                .filter(c -> List.of(c.getT1(), c.getT2()).contains(TOP_LEFT))
                .filter(c -> List.of(c.getT1(), c.getT2()).contains(TOP_RIGHT)))
        .as("a neighbour of both is connected once")
        .hasSize(1);
    assertThat(MapXml.owner(project.game(), TOP_LEFT))
        .as("the keeper's owner stays")
        .contains("Red");
    assertThat(project.getFiles().unitPlaces(TOP_LEFT))
        .as("the keeper's unit place stays where it was moved, not found again")
        .containsExactly(new Point(31, 17));
    assertThat(project.getFiles().markerPlace(project.game(), MapFiles.Marker.NAME, TOP_LEFT))
        .as("and its name")
        .isEqualTo(new Point(13, 29));
    assertThat(project.getFiles().centers().get(TOP_LEFT))
        .as("and its center")
        .isEqualTo(files.centers().get(TOP_LEFT));
    project.undo();
    assertThat(project.game()).as("one undo brings the game back").isSameAs(game);
    assertThat(project.getFiles()).as("and the files").isSameAs(files);
  }

  @DisplayName("Land is not merged with sea, nor a territory attachments still name")
  @Test
  void refusesMerges() throws Exception {
    // given: a rule of Red names the top right land
    final MapProject project = givenSavedMapWithRed();
    project.edit(
        g ->
            MapXml.withOption(
                g,
                new MapXml.AttachmentKey(
                    "rulesAttachment", RulesAttachment.class.getName(), "player", "Red"),
                "directPresenceTerritories",
                TOP_RIGHT));
    final Game game = project.game();

    // when
    final List<String> withSea = project.mergeTerritories(TOP_LEFT, "Sea Zone 1");
    final List<String> named = project.mergeTerritories(TOP_LEFT, TOP_RIGHT);

    // then
    assertThat(project.mergeRefusal(TOP_LEFT, "Sea Zone 1"))
        .as("land with sea, said plainly")
        .contains("Territory 1 is land and Sea Zone 1 is a sea zone: land and sea stay apart.");
    assertThat(withSea).as("not merged").isNotEmpty();
    assertThat(project.mergeRefusal(TOP_LEFT, TOP_RIGHT)).as("land with land may merge").isEmpty();
    assertThat(named)
        .as("where the other is still named")
        .singleElement()
        .asString()
        .contains("Red");
    assertThat(project.game()).as("nothing changed").isSameAs(game);
  }

  @DisplayName("Many territories are removed as one step; one still named stays and is told")
  @Test
  void removesManyTerritories() throws Exception {
    // given: a rule of Red names the top right land
    final MapProject project = givenSavedMapWithRed();
    project.edit(
        g ->
            MapXml.withOption(
                g,
                new MapXml.AttachmentKey(
                    "rulesAttachment", RulesAttachment.class.getName(), "player", "Red"),
                "directPresenceTerritories",
                TOP_RIGHT));
    final Game game = project.game();

    // when
    final Map<String, List<String>> named =
        project.removeTerritories(List.of(TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT));

    // then
    assertThat(named).as("the one still named").containsOnlyKeys(TOP_RIGHT);
    assertThat(MapXml.territories(project.game()))
        .as("the others are gone")
        .contains(TOP_RIGHT)
        .doesNotContain(TOP_LEFT, BOTTOM_RIGHT);
    assertThat(project.getFiles().polygons())
        .as("with their outlines")
        .doesNotContainKeys(TOP_LEFT, BOTTOM_RIGHT);
    project.undo();
    assertThat(project.game()).as("one undo brings both back").isSameAs(game);
  }

  @DisplayName("An erased border is one step, counts as drawn; Make territories joins the areas")
  @Test
  void erasedBorderJoinsTerritories() throws Exception {
    // given: the line between the two top lands runs down x 199 to 201
    final MapProject project = givenSavedMapWithRed();
    final BufferedImage before = project.getFiles().picture().orElseThrow();

    // when
    final boolean erased =
        project.eraseBorders(List.of(new Point(200, 20), new Point(200, 90)), 9);
    final MapFiles from = project.getFiles();
    final Optional<Rectangle> region = project.drawnRegion();
    final Redrawn redrawn =
        from.withBordersFrom(from.picture().orElseThrow(), region.orElseThrow());
    final boolean offTheLines =
        project.eraseBorders(List.of(new Point(50, 20), new Point(90, 20)), 9);

    // then
    assertThat(erased).as("a line was under the stroke").isTrue();
    assertThat(MapPicture.isBorder(from.picture().orElseThrow().getRGB(200, 50)))
        .as("the line is gone from the picture")
        .isFalse();
    assertThat(region.orElseThrow().contains(200, 50)).as("the stroke counts as drawn").isTrue();
    assertThat(redrawn.merged().values())
        .as("the two lands it parted are one area now, to choose the keeper of")
        .singleElement()
        .satisfies(
            members -> assertThat(members).containsExactlyInAnyOrder(TOP_LEFT, TOP_RIGHT));
    assertThat(offTheLines).as("a stroke over no line is no step").isFalse();
    project.undo();
    assertThat(project.getFiles().picture())
        .as("one undo brings the line back")
        .containsSame(before);
    assertThat(project.drawnRegion()).as("and nothing counts as drawn").isEmpty();
  }

  @DisplayName("A renamed or removed territory's entry in the names not drawn follows it")
  @Test
  void namesNotDrawnFollowTerritories() throws Exception {
    // given
    final MapProject project = givenSavedMapWithRed();
    project.setMapProperty(
        "dont_draw_territory_names",
        TOP_LEFT + "," + TOP_RIGHT + "," + BOTTOM_RIGHT + ",Sea Zone 1");

    // when
    project.rename(TOP_LEFT, "Alpha");
    project.removeTerritory(TOP_RIGHT);
    project.mergeTerritories("Alpha", BOTTOM_RIGHT);

    // then
    assertThat(project.getFiles().properties().getProperty("dont_draw_territory_names"))
        .as("renamed in place; the removed and the merged one dropped; the rest as it was")
        .isEqualTo("Alpha,Sea Zone 1");
  }

  @DisplayName("The names not drawn: a territory is added and taken out, the key gone with the last")
  @Test
  void hidesAndShowsNames() throws Exception {
    // given: a list with an entry no territory has, as real maps hold
    final MapProject project = givenSavedMapWithRed();
    project.setMapProperty(MapFiles.HIDDEN_NAMES, "Unit List");

    // when
    project.editFiles(f -> f.withListed(MapFiles.HIDDEN_NAMES, TOP_LEFT));
    final String both = project.getFiles().properties().getProperty(MapFiles.HIDDEN_NAMES);
    project.editFiles(f -> f.withListed(MapFiles.HIDDEN_NAMES, TOP_LEFT));
    project.undo();
    final String undone = project.getFiles().properties().getProperty(MapFiles.HIDDEN_NAMES);
    project.redo();
    project.editFiles(f -> f.withoutListed(MapFiles.HIDDEN_NAMES, "Unit List"));
    final String one = project.getFiles().properties().getProperty(MapFiles.HIDDEN_NAMES);
    project.removeTerritory(TOP_LEFT);

    // then
    assertThat(both).as("added after the entry it does not know").isEqualTo("Unit List," + TOP_LEFT);
    assertThat(undone).as("hiding it twice was one step").isEqualTo("Unit List");
    assertThat(one).as("the other taken out").isEqualTo(TOP_LEFT);
    assertThat(project.getFiles().properties().containsKey(MapFiles.HIDDEN_NAMES))
        .as("its last territory removed: no empty key stays in map.properties")
        .isFalse();
  }
}
