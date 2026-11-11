package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Optional;
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
}
