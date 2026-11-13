package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;

/** TripleA starts a game only when every land territory has a territory attachment. */
final class StartsInTripleATest {
  @TempDir Path folder;

  /** A map made from the picture and saved, as the wizard does. */
  private MapProject givenNewMap() throws Exception {
    return MapProject.newMap(
            "Island Wars", MapPictureTest.givenPicture(), List.of("Red"), folder.resolve("maps"))
        .project();
  }

  private static long landWithoutAttachment(final Game game) {
    return MapXml.territories(game).stream()
        .filter(t -> !MapXml.isWater(game, t))
        .filter(
            t ->
                AttachmentsXml.attachments(game).stream()
                    .noneMatch(
                        a ->
                            AttachmentsXml.isNamed(a, "territoryAttachment")
                                && t.equals(a.getAttachTo())))
        .count();
  }

  @DisplayName("A new map's land territories all have a territory attachment, and TripleA starts it")
  @Test
  void newMapStarts() throws Exception {
    // given, when
    final MapProject project = givenNewMap();
    final Path xml = project.getXmlFile().orElseThrow();

    // then
    assertThat(landWithoutAttachment(project.game())).as("no land without one").isZero();
    assertThat(Files.readString(xml)).as("written into the game file").contains("territoryAttachment");
    assertThat(MapChecks.findError(xml)).as("TripleA loads and starts it").isEmpty();
  }

  @DisplayName("A game file whose land lacks territory attachments is told by Check and mended by a save")
  @Test
  void opensAndMendsAGameWithoutThem() throws Exception {
    // given: the file as earlier versions of the editor wrote it
    final MapProject made = givenNewMap();
    final Path xml = made.getXmlFile().orElseThrow();
    Files.writeString(
        xml,
        Files.readString(xml)
            .replaceAll("(?s)<attachment[^>]*territoryAttachment[^>]*/>", "")
            .replaceAll("(?s)<attachment[^>]*territoryAttachment[^>]*>.*?</attachment>", ""));

    // when
    final MapProject opened = MapProject.open(xml);

    // then
    assertThat(MapChecks.findError(xml))
        .as("TripleA would stop at the start")
        .hasValueSatisfying(why -> assertThat(why).contains("No territory attachment"));
    assertThat(opened.hasUnsavedChanges()).as("the missing ones are a change to save").isTrue();
    assertThat(landWithoutAttachment(opened.game())).as("added in the editor").isZero();
    opened.save();
    assertThat(MapChecks.findError(xml)).as("saved, TripleA starts it").isEmpty();
  }

  @DisplayName("Sea turned into land gets its territory attachment; nothing is added twice")
  @Test
  void landGetsOneWithTheChange() throws Exception {
    // given
    final MapProject project = givenNewMap();
    final String sea =
        MapXml.territories(project.game()).stream()
            .filter(t -> MapXml.isWater(project.game(), t))
            .findFirst()
            .orElseThrow();
    final Game before = project.game();

    // when
    project.edit(game -> MapXml.withLand(game, sea));

    // then
    assertThat(landWithoutAttachment(project.game())).as("the new land has one").isZero();
    assertThat(MapXml.withTerritoryAttachments(before)).as("nothing missing: the same game").isSameAs(before);
  }

  @DisplayName("A sea zone with an owner gets a territory attachment too, as the game's start needs")
  @Test
  void ownedSeaGetsOne() throws Exception {
    // given
    final MapProject project = givenNewMap();
    final String sea =
        MapXml.territories(project.game()).stream()
            .filter(t -> MapXml.isWater(project.game(), t))
            .findFirst()
            .orElseThrow();

    // when
    project.edit(game -> MapXml.withOwner(game, sea, "Red"));
    project.save();

    // then
    assertThat(AttachmentsXml.attachments(project.game()))
        .as("the owned sea zone has one")
        .anyMatch(a -> sea.equals(a.getAttachTo()));
    assertThat(MapChecks.findError(project.getXmlFile().orElseThrow()))
        .as("TripleA starts it")
        .isEmpty();
  }

  @DisplayName("A game that makes territory attachments in a loop is left as it is")
  @Test
  void foreachGameIsLeftAlone() {
    // given: land without an attachment of its own, beside a foreach one and one for @x@
    final Game minimap = MapXmlTest.givenMinimap();
    final Game bare =
        AttachmentsXml.withAttachments(
            minimap,
            AttachmentsXml.attachments(minimap).stream()
                .filter(a -> !AttachmentsXml.isNamed(a, "territoryAttachment"))
                .toList());
    final Attachment.AttachmentBuilder loop =
        Attachment.builder()
            .name("territoryAttachment")
            .javaClass("TerritoryAttachment")
            .type("territory");
    final Game byForeach =
        AttachmentsXml.withAttachments(
            bare, List.of(loop.attachTo("@land@").foreach("$All-Land$").build()));
    final Game byName =
        AttachmentsXml.withAttachments(bare, List.of(loop.attachTo("@land@").foreach(null).build()));

    // when, then
    assertThat(MapXml.withTerritoryAttachments(bare))
        .as("without a loop the land gets its attachments")
        .isNotSameAs(bare);
    assertThat(MapXml.withTerritoryAttachments(byForeach)).as("a foreach").isSameAs(byForeach);
    assertThat(MapXml.withTerritoryAttachments(byName)).as("@…@ in attachTo").isSameAs(byName);
  }
}
