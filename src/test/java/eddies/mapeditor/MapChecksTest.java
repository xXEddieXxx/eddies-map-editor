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
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.MapChecks.Problem;

final class MapChecksTest {

  private static Game givenMinimapWithoutItaliansCapital() throws Exception {
    try (InputStream xml = MapChecksTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      return MapXml.withCapital(
          new XmlMapper(xml).mapXmlToObject(Game.class), "blue capitol", null);
    }
  }

  @DisplayName("A nation without a capital is shown at a territory of its own")
  @Test
  void noCapitalShowsATerritoryOfTheNation() throws Exception {
    final Game game = givenMinimapWithoutItaliansCapital();

    final Optional<String> shown =
        MapChecks.quick(game, MapFiles.none()).stream()
            .filter(problem -> problem.text().equals("Italians has no capital"))
            .findFirst()
            .flatMap(Problem::territory);

    assertThat(shown.flatMap(territory -> MapXml.owner(game, territory)))
        .as("Show opens a territory the nation owns, where Territories sets its capital")
        .contains("Italians");
  }

  @DisplayName("A problem that names a tool tells it, so Home opens that tool")
  @Test
  void problemsNameTheirTool() throws Exception {
    final Game withoutNations = MapProject.newEmpty().game();
    final Game withLoneTerritory =
        MapXml.withTerritory(givenMinimapWithoutItaliansCapital(), "Atlantis", List.of());

    final List<Problem> problems =
        Stream.concat(
                MapChecks.quick(withoutNations, MapFiles.none()).stream(),
                MapChecks.quick(withLoneTerritory, MapFiles.none()).stream())
            .toList();

    assertThat(problems)
        .as("nations are added in Nations, borders checked in Connections")
        .filteredOn(p -> p.text().startsWith("No nations yet") || p.text().startsWith("Atlantis "))
        .extracting(Problem::text, p -> p.tool().orElse(""))
        .containsExactly(
            tuple("No nations yet: add them in Nations", "Nations"),
            tuple("Atlantis is not connected to any territory", "Connections"));
  }
}
