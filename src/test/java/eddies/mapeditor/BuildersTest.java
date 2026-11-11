package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.map.data.elements.Game;

final class BuildersTest {

  @DisplayName("A builder filled from a part of the model builds the same part, field by field")
  @Test
  void copiesEveryField() {
    final Game game = MapXmlTest.givenMinimap();

    final Game rebuilt = Builders.toBuilder(game).build();

    assertThat(rebuilt).as("a copy, not the game itself").isNotSameAs(game);
    assertThat(rebuilt).as("the game").usingRecursiveComparison().isEqualTo(game);
    assertThat(Builders.toBuilder(game.getMap()).build())
        .as("its map")
        .usingRecursiveComparison()
        .isEqualTo(game.getMap());
    assertThat(Builders.toBuilder(game.getInitialize()).build())
        .as("its start")
        .usingRecursiveComparison()
        .isEqualTo(game.getInitialize());
    assertThat(Builders.toBuilder(game.getProduction()).build())
        .as("its production")
        .usingRecursiveComparison()
        .isEqualTo(game.getProduction());
  }
}
