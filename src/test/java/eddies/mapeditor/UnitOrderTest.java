package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.util.UnitSeparator;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement.UnitPlacementBuilder;

/**
 * The order a territory's units take its unit places in, {@link MapXml#unitsAsDrawn}: each key of
 * TripleA's UnitSeparator with a small game, and against TripleA's own order of the loaded game.
 */
final class UnitOrderTest {
  private static final String RUSSIANS = "Russians";
  private static final String ITALIANS = "Italians";
  private static final String GERMANS = "Germans";
  // owned by the Russians, the Italians, nobody
  private static final String RED = "red north 1";
  private static final String BLUE = "blue north 1";
  private static final String NEUTRAL = "neutral territory 1";
  private static final String SEA = "sea zone 1";

  private final Game minimap = MapXmlTest.givenMinimap();

  @TempDir Path folder;

  @DisplayName("The territory owner's units come first, whatever the players' order")
  @Test
  void ownerFirst() {
    final Game game =
        givenUnits(minimap, BLUE, unit(RUSSIANS, "infantry"), unit(ITALIANS, "infantry"));

    thenDrawn(game, BLUE, ITALIANS + " infantry", RUSSIANS + " infantry");
  }

  @DisplayName("Units of nobody come first in a territory of nobody")
  @Test
  void neutralFirstOnNeutralLand() {
    final Game game =
        givenUnits(minimap, NEUTRAL, unit(RUSSIANS, "infantry"), unit(null, "infantry"));

    thenDrawn(game, NEUTRAL, "Neutral infantry", RUSSIANS + " infantry");
  }

  @DisplayName("Owners not at war with the territory's owner come before those at war: by alliance")
  @Test
  void alliesBeforeEnemies() {
    final Game game =
        givenUnits(
            givenGermansAlliedToItalians(),
            BLUE,
            unit(null, "infantry"),
            unit(RUSSIANS, "infantry"),
            unit(GERMANS, "infantry"));

    thenDrawn(game, BLUE, GERMANS + " infantry", "Neutral infantry", RUSSIANS + " infantry");
  }

  @DisplayName("At war is what the relationships at the start say, before the alliances")
  @Test
  void warByRelationship() {
    final Game types =
        RulesXml.withRelationshipType(
            RulesXml.withRelationshipType(
                givenGermansAlliedToItalians(), "Peace", Constants.RELATIONSHIP_ARCHETYPE_NEUTRAL),
            "Feud",
            Constants.RELATIONSHIP_ARCHETYPE_WAR);
    final Game game =
        givenUnits(
            RulesXml.withRelationship(
                RulesXml.withRelationship(types, ITALIANS, RUSSIANS, "Peace"),
                GERMANS,
                ITALIANS,
                "Feud"),
            BLUE,
            unit(GERMANS, "infantry"),
            unit(RUSSIANS, "infantry"));

    assertThat(RulesXml.startAtWar(game, RUSSIANS, ITALIANS))
        .as("enemies by alliance, but the relationship is neutral")
        .isFalse();
    assertThat(RulesXml.startAtWar(game, ITALIANS, GERMANS))
        .as("allies by alliance, but the relationship is war, given either way round")
        .isTrue();
    thenDrawn(game, BLUE, RUSSIANS + " infantry", GERMANS + " infantry");
  }

  @DisplayName("Owners alike to the territory's owner go by the game's list of players")
  @Test
  void playersOrder() {
    final Game game =
        givenUnits(minimap, NEUTRAL, unit(ITALIANS, "infantry"), unit(RUSSIANS, "infantry"));

    thenDrawn(game, NEUTRAL, RUSSIANS + " infantry", ITALIANS + " infantry");
  }

  @DisplayName("The owner counts before the unit: an enemy's factory comes after the owner's tank")
  @Test
  void ownerBeforeKind() {
    final Game game = givenUnits(minimap, RED, unit(ITALIANS, "factory"), unit(RUSSIANS, "armour"));

    thenDrawn(game, RED, RUSSIANS + " armour", ITALIANS + " factory");
  }

  @DisplayName("Unit types that cannot move come first")
  @Test
  void staticFirst() {
    final Game game =
        givenUnits(
            minimap,
            RED,
            unit(RUSSIANS, "infantry"),
            unit(RUSSIANS, "aaGun"),
            unit(RUSSIANS, "factory"));

    thenDrawn(game, RED, RUSSIANS + " factory", RUSSIANS + " aaGun", RUSSIANS + " infantry");
  }

  @DisplayName("Then those that cannot move in combat move, so isAA units, also with a high attack")
  @Test
  void antiAirNext() {
    final Game game =
        givenUnits(
            MapXml.withUnitOption(minimap, "aaGun", "attack", "3"),
            RED,
            unit(RUSSIANS, "infantry"),
            unit(RUSSIANS, "aaGun"));

    thenDrawn(game, RED, RUSSIANS + " aaGun", RUSSIANS + " infantry");
  }

  @DisplayName("canNotMoveDuringCombatMove written false after isAA counts, as the last one does")
  @Test
  void antiAirThatMovesInCombatMove() {
    final Game game =
        givenUnits(
            MapXml.withUnitOption(
                MapXml.withUnitOption(minimap, "aaGun", "attack", "3"),
                "aaGun",
                "canNotMoveDuringCombatMove",
                "false"),
            RED,
            unit(RUSSIANS, "aaGun"),
            unit(RUSSIANS, "infantry"));

    assertThat(UnitsXml.cannotMoveInCombatMove(minimap, "aaGun")).as("isAA alone").isTrue();
    assertThat(UnitsXml.cannotMoveInCombatMove(game, "aaGun")).as("taken back after").isFalse();
    thenDrawn(game, RED, RUSSIANS + " infantry", RUSSIANS + " aaGun");
  }

  @DisplayName("In a sea zone: sea units, then air units, then land units")
  @Test
  void atSea() {
    final Game game =
        givenUnits(
            minimap,
            SEA,
            unit(RUSSIANS, "infantry"),
            unit(RUSSIANS, "fighter"),
            unit(RUSSIANS, "battleship"));

    thenDrawn(game, SEA, RUSSIANS + " battleship", RUSSIANS + " fighter", RUSSIANS + " infantry");
  }

  @DisplayName("On land: land units before air units, also with a higher attack")
  @Test
  void landBeforeAir() {
    final Game game =
        givenUnits(
            MapXml.withUnitOption(minimap, "fighter", "attack", "1"),
            RED,
            unit(RUSSIANS, "fighter"),
            unit(RUSSIANS, "artillery"));

    thenDrawn(game, RED, RUSSIANS + " artillery", RUSSIANS + " fighter");
  }

  @DisplayName("Then by maxBuiltPerPlayer, those without a limit first")
  @Test
  void maxBuiltPerPlayer() {
    final Game game =
        givenUnits(
            MapXml.withUnitOption(minimap, "infantry", "maxBuiltPerPlayer", "1"),
            RED,
            unit(RUSSIANS, "infantry"),
            unit(RUSSIANS, "armour"));

    thenDrawn(game, RED, RUSSIANS + " armour", RUSSIANS + " infantry");
  }

  @DisplayName("Then by attack, the weakest first, whatever the unit types' order")
  @Test
  void attack() {
    final Game game =
        givenUnits(minimap, RED, unit(RUSSIANS, "armour"), unit(RUSSIANS, "artillery"));

    thenDrawn(game, RED, RUSSIANS + " artillery", RUSSIANS + " armour");
  }

  @DisplayName("An attack over the dice's sides counts as the sides: the unit types' order decides")
  @Test
  void attackUpToTheDice() {
    final Game game =
        givenUnits(
            MapXml.withUnitOption(
                MapXml.withUnitOption(minimap, "infantry", "attack", "9"), "armour", "attack", "7"),
            RED,
            unit(RUSSIANS, "armour"),
            unit(RUSSIANS, "infantry"));

    thenDrawn(game, RED, RUSSIANS + " infantry", RUSSIANS + " armour");
  }

  @DisplayName("At last by the game's list of unit types")
  @Test
  void unitTypesOrder() {
    final Game game =
        givenUnits(
            MapXml.withUnitOption(minimap, "artillery", "attack", "1"),
            RED,
            unit(RUSSIANS, "artillery"),
            unit(RUSSIANS, "infantry"));

    thenDrawn(game, RED, RUSSIANS + " infantry", RUSSIANS + " artillery");
  }

  @DisplayName("Undrawn unit types and lines of 0 take no place; a type's lines are one stack")
  @Test
  void undrawnAndStacks() {
    final Game game =
        givenUnits(
            minimap,
            RED,
            unit(RUSSIANS, "infantry").quantity(2),
            unit(RUSSIANS, "factory"),
            unit(RUSSIANS, "armour").quantity(0),
            unit(RUSSIANS, "infantry").quantity(3));

    assertThat(MapXml.unitsAsDrawn(game, RED, Set.of("factory")))
        .as("no factory, as it is not drawn, no tank, as its line has 0; the infantry in one stack")
        .extracting(UnitPlacement::getUnitType, UnitPlacement::getQuantity)
        .containsExactly(tuple("infantry", 5));
    assertThat(MapXml.units(game, RED)).as("the game keeps its lines").hasSize(4);
  }

  private Game givenGermansAlliedToItalians() {
    return MapXml.withAlliance(MapXml.withNation(minimap, GERMANS), GERMANS, ITALIANS);
  }

  private static Game givenUnits(
      final Game game, final String territory, final UnitPlacementBuilder... units) {
    return MapXml.withTerritoryUnits(
        game,
        territory,
        Arrays.stream(units).map(u -> u.territory(territory).build()).toList());
  }

  private static UnitPlacementBuilder unit(final String owner, final String type) {
    return UnitPlacement.builder().owner(owner).unitType(type).quantity(1);
  }

  /** The editor's order is the expected one, and the one TripleA gives the loaded game. */
  private void thenDrawn(final Game game, final String territory, final String... expected) {
    assertThat(MapXml.unitsAsDrawn(game, territory, Set.of()))
        .as("the order read from the game file")
        .extracting(
            u ->
                (u.getOwner() == null ? Constants.PLAYER_NAME_NEUTRAL : u.getOwner())
                    + " "
                    + u.getUnitType())
        .containsExactly(expected);
    assertThat(tripleaOrder(game, territory))
        .as("TripleA's own order of the loaded game")
        .containsExactly(expected);
  }

  private List<String> tripleaOrder(final Game game, final String territoryName) {
    final GameData data = RulesXmlTest.givenLoaded(game, folder);
    final Territory territory = data.getMap().getTerritoryOrThrow(territoryName);
    return UnitSeparator.getSortedUnitCategories(
            territory.getUnits(), territory, data.getPlayerList().getPlayers().getFirst())
        .stream()
        .map(category -> category.getOwner().getName() + " " + category.getType().getName())
        .toList();
  }
}
