package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import java.awt.Dimension;
import java.awt.Point;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.generic.xml.reader.XmlMapper;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.triplea.map.data.elements.Initialize.ResourceInitialize.ResourceGiven;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.map.data.elements.Production.PlayerProduction;
import org.triplea.map.data.elements.Production.ProductionFrontier.FrontierRules;
import org.triplea.map.data.elements.PropertyList.Property;
import org.triplea.map.xml.writer.GameXmlWriter;
import eddies.mapeditor.MapPicture.DetectedMap;
import eddies.mapeditor.MapPicture.DetectedTerritory;
import eddies.mapeditor.MapPicture.Neighbours;

final class MapXmlTest {
  private static final String CAPITAL = "red capitol";
  private static final String NORTH = "red north 1";

  private final Game minimap = givenMinimap();

  static Game givenMinimap() {
    try (InputStream xml = MapXmlTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      return new XmlMapper(xml).mapXmlToObject(Game.class);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DisplayName("The territories are read with their owner, value, capital and victory city")
  @Test
  void readsTerritories() {
    assertThat(MapXml.territories(minimap)).as("the minimap's 20 territories").hasSize(20);
    assertThat(MapXml.owner(minimap, CAPITAL)).as("from the ownerInitialize").contains("Russians");
    assertThat(MapXml.production(minimap, CAPITAL))
        .as("from the territory attachment's production")
        .isEqualTo(10);
    assertThat(MapXml.isCapital(minimap, CAPITAL)).as("its attachment names a capital").isTrue();
    assertThat(MapXml.isVictoryCity(minimap, CAPITAL)).as("its victoryCity is not 0").isTrue();
    assertThat(MapXml.isWater(minimap, "sea zone 1")).as("marked water in the map").isTrue();
    assertThat(MapXml.neighbors(minimap, CAPITAL))
        .as("connections count in both directions")
        .contains(NORTH, "neutral territory 1");
  }

  @DisplayName("A change makes a new game and leaves the one it started from as it was")
  @Test
  void changesLeaveTheOriginal() {
    final Game changed = MapXml.withOwner(minimap, NORTH, "Italians");

    assertThat(MapXml.owner(changed, NORTH)).as("the new game has the change").contains("Italians");
    assertThat(MapXml.owner(minimap, NORTH))
        .as("undo goes back to the game before, so it must not change")
        .contains("Russians");
  }

  @DisplayName("Owner, value and units change in place, and 0 or none takes them out")
  @Test
  void changesTerritories() {
    final Game game =
        MapXml.withUnits(
            MapXml.withProduction(MapXml.withOwner(minimap, NORTH, null), CAPITAL, 0),
            NORTH,
            "Russians",
            "infantry",
            5);

    assertThat(MapXml.owner(game, NORTH)).as("neutral").isEmpty();
    assertThat(MapXml.production(game, CAPITAL)).as("the value set to 0").isZero();
    assertThat(MapXml.option(game, "territoryAttachment", CAPITAL, "production"))
        .as("a value of 0 is not written")
        .isEmpty();
    assertThat(MapXml.units(game, NORTH))
        .as("the 2 infantry became 5, the artillery stayed")
        .extracting(UnitPlacement::getUnitType, UnitPlacement::getQuantity)
        .contains(tuple("infantry", 5))
        .contains(tuple("artillery", 1));
    assertThat(MapXml.units(MapXml.withUnits(game, NORTH, "Russians", "infantry", 0), NORTH))
        .as("no infantry left")
        .extracting(UnitPlacement::getUnitType)
        .doesNotContain("infantry");
  }

  @DisplayName("Copied units replace the target's, the copied owner's turned to the target's owner")
  @Test
  void copiesUnits() {
    final Game given = MapXml.withOwner(minimap, "neutral territory 1", "Italians");

    final Game copied = MapXml.withUnitsCopied(given, NORTH, "neutral territory 1");

    assertThat(MapXml.units(copied, "neutral territory 1"))
        .as("the Russian garrison of the north, now Italian")
        .extracting(UnitPlacement::getUnitType, UnitPlacement::getOwner)
        .containsExactlyInAnyOrderElementsOf(
            MapXml.units(given, NORTH).stream()
                .map(u -> tuple(u.getUnitType(), "Italians"))
                .toList());
    assertThat(MapXml.units(copied, NORTH))
        .as("the copied territory keeps its units")
        .isEqualTo(MapXml.units(given, NORTH));
    assertThat(MapXml.units(MapXml.withTerritoryUnits(copied, NORTH, List.of()), NORTH))
        .as("cleared")
        .isEmpty();
  }

  @DisplayName("Clearing a territory without units changes nothing, so it makes no undo step")
  @Test
  void clearsNothing() {
    final Game cleared = MapXml.withTerritoryUnits(minimap, NORTH, List.of());

    assertThat(MapXml.withTerritoryUnits(cleared, NORTH, List.of()))
        .as("nothing left to clear: the same game")
        .isSameAs(cleared);
  }

  @DisplayName("A connection is added once and removed in either direction")
  @Test
  void changesConnections() {
    final Game connected = MapXml.withConnection(minimap, CAPITAL, "sea zone 4");

    assertThat(MapXml.neighbors(connected, "sea zone 4"))
        .as("a connection counts from its other end too")
        .contains(CAPITAL);
    assertThat(
            MapXml.neighbors(MapXml.withoutConnection(connected, "sea zone 4", CAPITAL), CAPITAL))
        .as("removed though given the other way round")
        .doesNotContain("sea zone 4");
  }

  @DisplayName("A territory without an attachment gets one for its first value")
  @Test
  void addsMissingAttachments() {
    final Game game = MapXml.withProduction(minimap, "sea zone 1", 2);

    assertThat(MapXml.production(game, "sea zone 1"))
        .as("read from the newly added attachment")
        .isEqualTo(2);
  }

  @DisplayName("A unit stat the map leaves out reads as the given default; removing restores it")
  @Test
  void unitStatsUseTheirDefaults() {
    final Game withCost = MapXml.withUnitOption(minimap, "infantry", "transportCost", "0");

    final Game without = MapXml.withoutUnitOption(withCost, "infantry", "transportCost");

    assertThat(MapXml.unitStat(withCost, "infantry", "transportCost", -1))
        .as("a value equal to 0 is written, not dropped, as the engine's default is -1")
        .isZero();
    assertThat(MapXml.option(without, "unitAttachment", "infantry", "transportCost"))
        .as("taken out of the attachment")
        .isEmpty();
    assertThat(MapXml.unitStat(without, "infantry", "transportCost", -1))
        .as("an absent stat reads as the default given")
        .isEqualTo(-1);
  }

  @DisplayName("Nations, units and options change as the map maker shows them")
  @Test
  void changesNationsUnitsAndOptions() {
    final Game game =
        MapXml.withUnitCost(
            MapXml.withUnitOption(
                MapXml.withAlliance(
                    MapXml.withStartingResource(minimap, "Italians", MapXml.PUS, 50),
                    "Italians",
                    "Axis"),
                "infantry",
                "attack",
                "2"),
            "infantry",
            4);

    assertThat(MapXml.startingResource(game, "Italians", MapXml.PUS))
        .as("the starting PUs replaced")
        .isEqualTo(50);
    assertThat(MapXml.alliances(game, "Italians"))
        .as("in the one alliance given")
        .containsExactly("Axis");
    assertThat(MapXml.unitStat(game, "infantry", "attack", 0)).as("the stat written").isEqualTo(2);
    assertThat(MapXml.unitCost(game, "infantry")).as("the PUs of its rule").contains(4);
  }

  @DisplayName("A renamed territory keeps its owner, units, neighbours, values and listings")
  @Test
  void renamesTerritories() {
    final Game listing =
        MapXml.withUnitOption(minimap, "infantry", "territories", CAPITAL + ":" + NORTH + "0");

    final Game game = MapXml.withRenamedTerritory(listing, CAPITAL, "Moscow");

    assertThat(MapXml.territories(game))
        .as("renamed in the map")
        .contains("Moscow")
        .doesNotContain(CAPITAL);
    assertThat(MapXml.owner(game, "Moscow")).as("the owner moved along").contains("Russians");
    assertThat(MapXml.production(game, "Moscow"))
        .as("the territory attachment follows the name")
        .isEqualTo(10);
    assertThat(MapXml.units(game, "Moscow"))
        .as("the units at the start too")
        .hasSameSizeAs(MapXml.units(minimap, CAPITAL));
    assertThat(MapXml.neighbors(game, NORTH))
        .as("the connections name it")
        .contains("Moscow")
        .doesNotContain(CAPITAL);
    assertThat(MapXml.option(game, "unitAttachment", "infantry", "territories"))
        .as("only a whole name between ':' is renamed")
        .contains("Moscow:" + NORTH + "0");
  }

  /** The map maker's template with three territories, nations Red and Blue owning one each. */
  static Game givenTemplateWithTwoNations() {
    final DetectedMap world =
        new DetectedMap(
            new Dimension(30, 10),
            List.of(
                new DetectedTerritory("West", false, List.of(), new Point(5, 5)),
                new DetectedTerritory("East", false, List.of(), new Point(15, 5)),
                new DetectedTerritory("Sea Zone 1", true, List.of(), new Point(25, 5))),
            List.of(new Neighbours("West", "East"), new Neighbours("East", "Sea Zone 1")));
    final Game twoNations =
        MapXml.withNation(
            MapXml.withNation(MapXml.withWorld(MapProject.newEmpty().game(), world), "Red"),
            "Blue");
    return MapXml.withCapital(
        MapXml.withOwner(
            MapXml.withOwner(MapXml.withProduction(twoNations, "West", 3), "West", "Red"),
            "East",
            "Blue"),
        "West",
        "Red");
  }

  @DisplayName("The new map's template with two nations and their territories loads in the engine")
  @Test
  void templateWithNationsLoads(@TempDir final Path folder) {
    final Game game = givenTemplateWithTwoNations();
    final Path xml = folder.resolve("new.xml");

    GameXmlWriter.exportXml(game, xml);

    assertThat(MapChecks.findError(xml)).as("the engine loads the game").isEmpty();
    assertThat(steps(game))
        .as("the bids once at the start, then each nation's turn before the end of the round")
        .containsExactly(
            "gameInitDelegate",
            "redBid",
            "redBidPlace",
            "blueBid",
            "blueBidPlace",
            "redTech",
            "redTechActivation",
            "redPurchase",
            "redCombatMove",
            "redBattle",
            "redNonCombatMove",
            "redPlace",
            "redEndTurn",
            "blueTech",
            "blueTechActivation",
            "bluePurchase",
            "blueCombatMove",
            "blueBattle",
            "blueNonCombatMove",
            "bluePlace",
            "blueEndTurn",
            "endRoundStep");
    assertThat(MapXml.alliances(game, "Red")).as("an alliance of its own").containsExactly("Red");
    final GameData data = GameParser.parse(xml, false).orElseThrow();
    final GamePlayer red = data.getPlayerList().getPlayerId("Red");
    assertThat(data.getProperties().get("Red bid", -1))
        .as("a bid of its own, none at first")
        .isZero();
    assertThat(data.getProperties().get(Constants.TECH_DEVELOPMENT, true))
        .as("no research unless the players choose it")
        .isFalse();
    assertThat(red.getTechnologyFrontierList().getFrontiers())
        .as("the engine's standard technologies for the tech steps")
        .isNotEmpty();
    assertThat(data.getSequence().getStep(1))
        .as("Red's bid, once")
        .extracting(GameStep::getName, GameStep::getMaxRunCount)
        .containsExactly("redBid", 1);
  }

  @DisplayName("A removed nation leaves nothing that names it")
  @Test
  void removesNations() {
    final Game game = MapXml.withoutNation(givenTemplateWithTwoNations(), "Red");

    assertThat(MapXml.nations(game)).as("only Blue is left").containsExactly("Blue");
    assertThat(MapXml.alliances(game, "Red")).as("no alliance").isEmpty();
    assertThat(steps(game)).as("no step of its turn").noneMatch(step -> step.startsWith("red"));
    assertThat(MapXml.properties(game))
        .as("no bid option")
        .extracting(Property::getName)
        .doesNotContain("Red bid")
        .contains("Blue bid");
    assertThat(MapXml.owner(game, "West")).as("its territory has no owner").isEmpty();
    assertThat(MapXml.isCapital(game, "West")).as("nor a capital").isFalse();
    assertThat(game.getProduction().getPlayerProductions())
        .as("no production of it")
        .extracting(PlayerProduction::getPlayer)
        .containsExactly("Blue");
    assertThat(game.getInitialize().getResourceInitialize().getResourcesGiven())
        .as("no PUs of it")
        .extracting(ResourceGiven::getPlayer)
        .containsExactly("Blue");
  }

  @DisplayName("A new unit type is like the one it copies and is bought; removing takes all back")
  @Test
  void addsAndRemovesUnitTypes() {
    final Game added = MapXml.withUnitType(givenTemplateWithTwoNations(), "Ranger", "armour");

    final Game removed =
        MapXml.withoutUnitType(MapXml.withUnits(added, "West", "Red", "Ranger", 2), "Ranger");

    assertThat(MapXml.unitStat(added, "Ranger", "attack", 0)).as("armour's attack").isEqualTo(3);
    assertThat(MapXml.unitCost(added, "Ranger")).as("armour's cost").contains(5);
    assertThat(added.getProduction().getProductionFrontiers().get(0).getFrontierRules())
        .as("for sale")
        .extracting(FrontierRules::getName)
        .contains("buyRanger");
    assertThat(MapXml.unitTypes(removed)).as("the type is gone").doesNotContain("Ranger");
    assertThat(MapXml.units(removed, "West")).as("its units at the start too").isEmpty();
    assertThat(removed.getProduction().getProductionFrontiers().get(0).getFrontierRules())
        .as("and its rule")
        .extracting(FrontierRules::getName)
        .doesNotContain("buyRanger");
    assertThat(MapXml.option(removed, "unitAttachment", "Ranger", "attack"))
        .as("and its stats")
        .isEmpty();
  }

  private static List<String> steps(final Game game) {
    return game.getGamePlay().getSequence().getSteps().stream().map(Step::getName).toList();
  }

  @DisplayName("The changed game loads in the engine with the changes")
  @Test
  void changedGameLoads(@TempDir final Path folder) {
    final Game game =
        MapXml.withUnitOption(
            MapXml.withProduction(MapXml.withOwner(minimap, NORTH, "Italians"), NORTH, 7),
            "infantry",
            "defense",
            "3");
    final Path xml = folder.resolve("changed.xml");

    GameXmlWriter.exportXml(game, xml);

    final GameData data = GameParser.parse(xml, false).orElseThrow();
    assertThat(data.getMap().getTerritoryOrThrow(NORTH).getOwner().getName())
        .as("the engine reads the new owner")
        .isEqualTo("Italians");
    assertThat(
            TerritoryAttachment.get(data.getMap().getTerritoryOrThrow(NORTH))
                .map(TerritoryAttachment::getProduction))
        .as("the engine reads the new value")
        .contains(7);
    final UnitAttachment infantry =
        data.getUnitTypeList().getUnitTypeOrThrow("infantry").getUnitAttachment();
    assertThat(infantry.getDefense(data.getPlayerList().getPlayerId("Russians")))
        .as("the engine reads the new stat")
        .isEqualTo(3);
  }

  @DisplayName("A removed territory is left in no list, connection, owner, units or attachment")
  @Test
  void removesTerritory() {
    final Game given = minimap;

    final Game without = MapXml.withoutTerritory(given, NORTH);

    assertThat(MapXml.territories(without)).as("off the map").doesNotContain(NORTH);
    assertThat(MapXml.connections(without))
        .as("no connection names it")
        .noneMatch(c -> NORTH.equals(c.getT1()) || NORTH.equals(c.getT2()));
    assertThat(MapXml.owner(without, NORTH)).as("no owner at the start").isEmpty();
    assertThat(MapXml.units(without, NORTH)).as("no units at the start").isEmpty();
    assertThat(RulesXml.whereNamed(without, NORTH)).as("no attachment names it").isEmpty();
    assertThat(MapXml.territories(without)).as("the others stay").hasSize(19);
  }

  @DisplayName("A territory goes with the canals through it; what else names it is told")
  @Test
  void removesCanalsWithTheTerritory() {
    final Game g40 = GameRulesXmlTest.givenG40();

    final Game withoutTransJordan = MapXml.withoutTerritory(g40, "Trans-Jordan");
    final Game withoutEgypt = MapXml.withoutTerritory(g40, "Egypt");

    assertThat(CanalXml.canals(withoutTransJordan))
        .as("Suez, on both its sea zones, needs Trans-Jordan")
        .noneMatch(c -> c.lands().contains("Trans-Jordan"));
    assertThat(RulesXml.whereNamed(withoutTransJordan, "Trans-Jordan"))
        .as("nothing else names it")
        .isEmpty();
    assertThat(RulesXml.whereNamed(withoutEgypt, "Egypt"))
        .as("the rules still name Egypt, eg as directPresenceTerritories")
        .isNotEmpty();
  }

  @DisplayName("A new territory is connected once to each neighbour")
  @Test
  void addsTerritory() {
    final Game given = MapXml.withConnection(minimap, "Atlantis", NORTH);

    final Game with = MapXml.withTerritory(given, "Atlantis", List.of(NORTH, CAPITAL));

    assertThat(MapXml.territories(with)).as("last on the map").endsWith("Atlantis");
    assertThat(MapXml.neighbors(with, "Atlantis"))
        .as("its neighbours")
        .containsExactlyInAnyOrder(NORTH, CAPITAL);
    assertThat(MapXml.connections(with))
        .as("the connection it had is not doubled")
        .hasSize(MapXml.connections(minimap).size() + 2);
  }
}
