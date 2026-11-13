package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;

/**
 * Of two attachments of one name on a thing TripleA keeps the last: G40 gives Russia a
 * territoryAttachment with 0 PUs and, further down, one with 3 PUs and the capital. Every read
 * and change is of that last one.
 */
final class LastAttachmentTest {
  private static final String RUSSIA = "Russia";

  private static List<Attachment> attachmentsOfRussia(final Game game) {
    return AttachmentsXml.attachments(game).stream()
        .filter(a -> AttachmentsXml.isNamed(a, "territoryAttachment"))
        .filter(a -> RUSSIA.equals(a.getAttachTo()))
        .toList();
  }

  @DisplayName("Russia's PUs, capital, flags and lists are read from and written to its last attachment")
  @Test
  void readsAndWritesTheLastAttachment() {
    // given
    final Game g40 = GameRulesXmlTest.givenG40();
    final Attachment first = attachmentsOfRussia(g40).getFirst();

    // when
    final Game worth = MapXml.withProduction(g40, RUSSIA, 9);
    final Game noCapital = MapXml.withCapital(g40, RUSSIA, null);
    final Game impassable = TerritoryXml.withFlag(g40, RUSSIA, TerritoryXml.IMPASSABLE);
    final Game owned =
        TerritoryXml.withValues(g40, RUSSIA, TerritoryXml.ORIGINAL_OWNER, List.of("Germans"));
    final Game built = TerritoryXml.withValues(g40, RUSSIA, TerritoryXml.UNIT_PRODUCTION, List.of("5"));

    // then
    assertThat(attachmentsOfRussia(g40)).as("G40 has two for Russia").hasSize(2);
    assertThat(MapXml.production(g40, RUSSIA)).as("the last one's 3 PUs, not the first one's 0").isEqualTo(3);
    assertThat(MapXml.capitalOf(g40, RUSSIA)).as("and its capital").contains("Russians");
    assertThat(MapXml.production(worth, RUSSIA)).as("a new value is read back").isEqualTo(9);
    assertThat(MapXml.capitalOf(noCapital, RUSSIA)).as("an unticked capital stays unticked").isEmpty();
    assertThat(MapXml.isImpassable(impassable, RUSSIA)).as("a ticked flag stays ticked").isTrue();
    assertThat(TerritoryXml.values(owned, RUSSIA, TerritoryXml.ORIGINAL_OWNER))
        .as("a list is read back")
        .containsExactly("Germans");
    assertThat(TerritoryXml.unitProduction(built, RUSSIA)).as("a number of a list too").isEqualTo(5);
    for (final Game changed : List.of(worth, noCapital, impassable, owned, built)) {
      assertThat(attachmentsOfRussia(changed)).as("still two").hasSize(2);
      assertThat(attachmentsOfRussia(changed).getFirst())
          .as("the first, which TripleA drops, is untouched")
          .isSameAs(first);
      assertThat(attachmentsOfRussia(changed).getLast())
          .as("the last is the one changed")
          .isNotSameAs(attachmentsOfRussia(g40).getLast());
    }
  }
}
