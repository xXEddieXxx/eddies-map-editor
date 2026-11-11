package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.TerritoryEffect;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;

final class TerritoryXmlTest {
  // 5 PUs in the minimap
  private static final String NORTH = "red north 1";
  private final Game minimap = MapXmlTest.givenMinimap();

  /** The minimap with red north 1 impassable, placing 3 units, first Italian and a forest. */
  private Game givenExtras() {
    final Game impassable = TerritoryXml.withFlag(minimap, NORTH, TerritoryXml.IMPASSABLE);
    final Game producing = TerritoryXml.withUnitProduction(impassable, NORTH, 3);
    final Game owned = TerritoryXml.withOriginalOwner(producing, NORTH, "Italians");
    final Game forest = TerritoryEffectsXml.withEffect(owned, "forest");
    return TerritoryXml.withEffect(forest, NORTH, "forest");
  }

  @DisplayName("Impassable, unit production, original owner and effects are set and cleared")
  @Test
  void setsAndClearsTheExtras() {
    final Game extras = givenExtras();
    final Game cleared =
        TerritoryXml.withEffects(
            TerritoryXml.withOriginalOwner(
                TerritoryXml.withUnitProduction(
                    TerritoryXml.withoutFlag(extras, NORTH, TerritoryXml.IMPASSABLE), NORTH, 5),
                NORTH,
                null),
            NORTH,
            List.of());

    assertThat(MapXml.isImpassable(extras, NORTH)).as("impassable").isTrue();
    assertThat(TerritoryXml.unitProduction(extras, NORTH)).as("its own").isEqualTo(3);
    assertThat(TerritoryXml.originalOwner(extras, NORTH)).as("set").contains("Italians");
    assertThat(MapXml.territoryEffects(extras, NORTH)).as("set").containsExactly("forest");
    assertThat(MapXml.isImpassable(cleared, NORTH)).as("passable again").isFalse();
    assertThat(TerritoryXml.unitProduction(cleared, NORTH)).as("its PUs again").isEqualTo(5);
    assertThat(TerritoryXml.values(cleared, NORTH, TerritoryXml.UNIT_PRODUCTION))
        .as("as many as its PUs need no option")
        .isEmpty();
    assertThat(TerritoryXml.originalOwner(cleared, NORTH)).as("cleared").isEmpty();
    assertThat(MapXml.territoryEffects(cleared, NORTH)).as("cleared").isEmpty();
    assertThat(MapXml.production(cleared, NORTH)).as("the PUs stay").isEqualTo(5);
    assertThat(MapXml.isImpassable(minimap, NORTH))
        .as("the game it started from stays, for undo")
        .isFalse();
  }

  @DisplayName("A change to what a territory has already is no change")
  @Test
  void sameValueIsNoChange() {
    final Game extras = givenExtras();

    assertThat(TerritoryXml.withUnitProduction(extras, NORTH, 3)).as("the same").isSameAs(extras);
    assertThat(TerritoryXml.withEffect(extras, NORTH, "forest")).as("has it").isSameAs(extras);
  }

  @DisplayName("The changed territory loads in the engine with its extras")
  @Test
  void extrasLoad(@TempDir final Path folder) {
    final GameData data = RulesXmlTest.givenLoaded(givenExtras(), folder);

    final TerritoryAttachment north =
        TerritoryAttachment.get(data.getMap().getTerritoryOrNull(NORTH)).orElseThrow();
    assertThat(north.getIsImpassable()).as("impassable").isTrue();
    assertThat(north.getUnitProduction())
        .as("written after production, which sets it too")
        .isEqualTo(3);
    assertThat(north.getOriginalOwner().map(GamePlayer::getName)).contains("Italians");
    assertThat(north.getTerritoryEffect()).extracting(TerritoryEffect::getName).contains("forest");
  }

  @DisplayName("PUs painted onto a territory with its own unit production keep that production")
  @Test
  void paintedPusKeepUnitProduction(@TempDir final Path folder) {
    final Game producing = TerritoryXml.withUnitProduction(minimap, NORTH, 2);
    // no production option left, so painting adds a new one
    final Game none = MapXml.withProduction(producing, NORTH, 0);
    final Game painted = MapXml.withProduction(none, NORTH, 3);

    final GameData data = RulesXmlTest.givenLoaded(painted, folder);

    final Territory north = data.getMap().getTerritoryOrNull(NORTH);
    assertThat(TerritoryAttachment.getProduction(north)).as("the painted PUs").isEqualTo(3);
    assertThat(TerritoryAttachment.getUnitProduction(north))
        .as("its own unit production stays")
        .isEqualTo(2);
  }
}
