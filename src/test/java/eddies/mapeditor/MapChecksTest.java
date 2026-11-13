package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.generic.xml.reader.XmlMapper;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapChecks.Problem;

final class MapChecksTest {

  private static Game givenMinimapWithoutItaliansCapital() throws Exception {
    try (InputStream xml = MapChecksTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      return MapXml.withCapital(
          new XmlMapper(xml).mapXmlToObject(Game.class), "blue capitol", null);
    }
  }

  @DisplayName("What the guide's steps say is no problem: no nations, a nation without capital")
  @Test
  void whatStepsSayIsNoProblem() throws Exception {
    final Game withoutCapital = givenMinimapWithoutItaliansCapital();
    final Game withoutNations = MapProject.newEmpty().game();

    final List<Problem> problems =
        Stream.concat(
                MapChecks.quick(withoutCapital, MapFiles.none()).stream(),
                MapChecks.quick(withoutNations, MapFiles.none()).stream())
            .toList();

    assertThat(problems)
        .as("finished maps have nations without a capital on purpose; a new map has its steps")
        .isEmpty();
  }

  @DisplayName("A territory without neighbours is a note with its tool and territory, no problem")
  @Test
  void loneTerritoryIsANote() throws Exception {
    final Game withLoneTerritory =
        MapXml.withTerritory(givenMinimapWithoutItaliansCapital(), "Atlantis", List.of());

    final List<Problem> notes = MapChecks.notes(withLoneTerritory);

    assertThat(notes)
        .as("worth a look, shown at the territory, changed in Connections")
        .extracting(Problem::text, p -> p.tool().orElse(""), p -> p.territory().orElse(""))
        .containsExactly(
            tuple("Atlantis is not connected to any territory", "Connections", "Atlantis"));
    assertThat(MapChecks.quick(withLoneTerritory, MapFiles.none()))
        .as("many finished maps have such a territory on purpose: no problem")
        .isEmpty();
  }

  @DisplayName("A lone territory the rules name, eg an ownership dial, is not even a note")
  @Test
  void loneTerritoryNamedByRulesIsNoProblem() throws Exception {
    final Game withDial = givenLoneRingWith("triggerRing", "Italians", "territories", "Ring");

    assertThat(MapChecks.notes(withDial))
        .as("a trigger changes its owner, so it stays apart on purpose")
        .extracting(Problem::text)
        .doesNotContain("Ring is not connected to any territory");
  }

  @DisplayName("A lone territory named only by its own attachment is still a note")
  @Test
  void loneTerritoryNamedByItsOwnAttachmentIsAProblem() throws Exception {
    final Game withOwnAttachment =
        givenLoneRingWith("territoryAttachment", "Ring", "originalOwner", "Ring");

    assertThat(MapChecks.notes(withOwnAttachment))
        .as("no rule uses it, so a connection may be missing")
        .extracting(Problem::text)
        .contains("Ring is not connected to any territory");
  }

  private static Game givenLoneRingWith(
      final String attachment, final String attachTo, final String option, final String value)
      throws Exception {
    final Game game = MapXml.withTerritory(givenMinimapWithoutItaliansCapital(), "Ring", List.of());
    return AttachmentsXml.withAttachments(
        game,
        Stream.concat(
                AttachmentsXml.attachments(game).stream(),
                Stream.of(
                    Attachment.builder()
                        .name(attachment)
                        .attachTo(attachTo)
                        .javaClass("games.strategy.triplea.attachments.TriggerAttachment")
                        .type("player")
                        .options(List.of(Option.builder().name(option).value(value).build()))
                        .build()))
            .toList());
  }
}
