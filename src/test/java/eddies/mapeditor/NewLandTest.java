package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;

/** Land made in the editor has its territory attachment; a foreach game's own are left alone. */
final class NewLandTest {
  private static final String SEA = "sea zone 1";

  /** The minimap with a territory attachment made by foreach, so its territories are left alone. */
  private static Game givenForeachGame() {
    final Game minimap = MapXmlTest.givenMinimap();
    return AttachmentsXml.withAttachments(
        minimap,
        Stream.concat(
                AttachmentsXml.attachments(minimap).stream(),
                Stream.of(
                    Attachment.builder()
                        .name("territoryAttachment")
                        .javaClass("TerritoryAttachment")
                        .type("territory")
                        .attachTo("@land@")
                        .foreach("$All-Land$")
                        .build()))
            .toList());
  }

  private static long attachmentsOf(final Game game, final String territory) {
    return AttachmentsXml.attachments(game).stream()
        .filter(a -> AttachmentsXml.isNamed(a, "territoryAttachment"))
        .filter(a -> territory.equals(a.getAttachTo()))
        .count();
  }

  @DisplayName("Sea made land and an owned sea zone get a territory attachment, once")
  @Test
  void landMadeHereHasItsAttachment() {
    // given
    final Game game = MapXmlTest.givenMinimap();

    // when
    final Game withLand = MapXml.withTerritory(game, "New Land", List.of());
    final Game seaMadeLand = MapXml.withLand(game, SEA);
    final Game ownedSea = MapXml.withOwner(game, SEA, "Russians");
    final Game newSea = MapXml.withWater(withLand, "New Land");

    // then
    assertThat(attachmentsOf(game, SEA)).as("the sea zone has none at first").isZero();
    assertThat(attachmentsOf(withLand, "New Land")).as("new land").isEqualTo(1);
    assertThat(MapXml.production(withLand, "New Land")).as("worth nothing yet").isZero();
    assertThat(attachmentsOf(seaMadeLand, SEA)).as("a sea zone made land").isEqualTo(1);
    assertThat(attachmentsOf(ownedSea, SEA)).as("a sea zone given an owner").isEqualTo(1);
    assertThat(attachmentsOf(MapXml.withOwner(ownedSea, SEA, "Italians"), SEA))
        .as("only if it has none")
        .isEqualTo(1);
    assertThat(attachmentsOf(MapXml.withLand(seaMadeLand, SEA), SEA)).as("never twice").isEqualTo(1);
    assertThat(attachmentsOf(newSea, "New Land"))
        .as("a new territory made a sea zone right away keeps no empty attachment")
        .isZero();
    assertThat(attachmentsOf(MapXml.withWater(ownedSea, SEA), SEA))
        .as("an owned sea zone keeps its own")
        .isEqualTo(1);
    assertThat(attachmentsOf(MapXml.withOwner(game, SEA, null), SEA))
        .as("no owner, no attachment")
        .isZero();
  }

  @DisplayName("In a game that makes territory attachments by foreach, an owner painted adds none")
  @Test
  void foreachGameKeepsItsAttachments() {
    // given: a territory may have its attachment from the loop, with its production and capital
    final Game game = givenForeachGame();
    final int attachments = AttachmentsXml.attachments(game).size();

    // when
    final Game painted = MapXml.withOwner(game, SEA, "Russians");
    final Game madeLand = MapXml.withLand(game, SEA);
    final Game withNew = MapXml.withTerritory(game, "New Land", List.of());

    // then
    assertThat(MapXml.owner(painted, SEA)).as("the owner is painted").contains("Russians");
    assertThat(AttachmentsXml.attachments(painted))
        .as("but no attachment is written that would take the place of the loop's")
        .hasSize(attachments);
    assertThat(AttachmentsXml.attachments(madeLand)).as("nor for sea made land").hasSize(attachments);
    assertThat(MapXml.withTerritoryAttachments(game)).as("nor on opening it").isSameAs(game);
    assertThat(attachmentsOf(withNew, "New Land"))
        .as("a territory made here is in no loop: it gets its own")
        .isEqualTo(1);
  }
}
