package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.ProductionFrontier;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RepairFrontier;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.attachments.TriggerAttachment;
import java.nio.file.Path;
import java.util.List;
import lombok.experimental.ExtensionMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Production;
import org.triplea.map.data.elements.ResourceList;
import org.triplea.map.data.elements.ResourceList.Resource;
import org.triplea.map.xml.writer.GameXmlWriter;

@ExtensionMethod(Builders.class)
final class ProductionXmlTest {
  private static final String NAVY = "navy";
  private final Game minimap = MapXmlTest.givenMinimap();

  /** The minimap with a frontier "navy" the Russians buy from: infantry and 2 carriers for 20. */
  private Game givenNavy() {
    final Game withFrontier = ProductionXml.withFrontier(minimap, NAVY);
    final Game withRule = ProductionXml.withNewRule(withFrontier, NAVY, "carrier", 16);
    final String rule = ProductionXml.rulesIn(withRule, NAVY).get(0);
    final Game changedRule =
        ProductionXml.withResult(
            ProductionXml.withCost(withRule, rule, MapXml.PUS, 20), rule, "carrier", 2);
    return ProductionXml.withoutRule(
        ProductionXml.withRuleIn(
            ProductionXml.withFrontierOf(changedRule, "Russians", NAVY), NAVY, "buyInfantry"),
        "buyBomber");
  }

  @DisplayName("Frontiers, rules and what a nation buys from change, each a new game")
  @Test
  void changesProduction() {
    final Game navy = givenNavy();

    assertThat(ProductionXml.rulesIn(navy, NAVY))
        .as("the new rule, its name free as buyCarrier is taken, then the one added")
        .containsExactly("buyCarrier2", "buyInfantry");
    assertThat(ProductionXml.frontierOf(navy, "Russians")).as("moved").contains(NAVY);
    assertThat(ProductionXml.rulesIn(navy, "production"))
        .as("a removed rule is out of every frontier")
        .doesNotContain("buyBomber");
    assertThat(ProductionXml.frontierOf(ProductionXml.withoutFrontier(navy, NAVY), "Russians"))
        .as("a nation of a removed frontier buys nothing")
        .isEmpty();
    assertThat(ProductionXml.frontiers(minimap))
        .as("the game it started from stays, for undo")
        .doesNotContain(NAVY);
  }

  @DisplayName("Attachments that name a nation or a rule are found, so it is not removed")
  @Test
  void findsNamesInAttachments() {
    assertThat(RulesXml.isNamedInAttachments(minimap, "Russians"))
        .as("its techAttachment is attached to it")
        .isTrue();
    assertThat(RulesXml.isNamedInAttachments(minimap, NAVY)).as("named nowhere").isFalse();
    final Game taken =
        MapXml.withOption(
            minimap,
            new MapXml.AttachmentKey(
                "triggerAttachment", TriggerAttachment.class.getName(), "player", "Russians"),
            "productionRule",
            "production:-buyInfantry");
    assertThat(RulesXml.isNamedInAttachments(taken, "buyInfantry"))
        .as("a trigger taking it out of a frontier, with a - before it")
        .isTrue();
  }

  @DisplayName("Changed production loads in the engine with the nation's frontier and costs")
  @Test
  void changedProductionLoads(@TempDir final Path folder) {
    final Path xml = folder.resolve("navy.xml");
    GameXmlWriter.exportXml(givenNavy(), xml);

    assertThat(MapChecks.findError(xml)).as("the engine loads the game").isEmpty();
    final GameData data = GameParser.parse(xml, false).orElseThrow();
    final ProductionFrontier frontier =
        data.getPlayerList().getPlayerId("Russians").getProductionFrontier();
    assertThat(frontier.getName()).as("the Russians buy from the new frontier").isEqualTo(NAVY);
    final ProductionRule carrier = frontier.getRules().get(0);
    assertThat(carrier.getCosts().getInt(data.getResourceList().getResourceOrThrow(MapXml.PUS)))
        .as("the changed cost")
        .isEqualTo(20);
    assertThat(carrier.getResults().totalValues()).as("two carriers").isEqualTo(2);
  }

  @DisplayName("A new rule costs PUs, else the game's first resource, else nothing")
  @Test
  void newRuleCostsAResourceTheGameHas() {
    final Game noPus =
        minimap.toBuilder()
            .resourceList(
                ResourceList.builder()
                    .resources(List.of(Resource.builder().name("Gold").build()))
                    .build())
            .build();
    final Game noResources =
        minimap.toBuilder()
            .resourceList(ResourceList.builder().resources(List.of()).build())
            .build();

    assertThat(ProductionXml.cost(newRule(minimap), MapXml.PUS)).as("in PUs").isEqualTo(5);
    assertThat(ProductionXml.cost(newRule(noPus), "Gold")).as("in its resource").isEqualTo(5);
    assertThat(newRule(noResources).getCosts()).as("no cost").isEmpty();
  }

  private static Production.ProductionRule newRule(final Game game) {
    final List<Production.ProductionRule> rules =
        ProductionXml.rules(ProductionXml.withNewRule(game, "production", "carrier", 5));
    return rules.get(rules.size() - 1);
  }

  @DisplayName("Repair rules, frontiers and which a nation uses change, and the engine reads them")
  @Test
  void changesRepair(@TempDir final Path folder) {
    for (final Game game : List.of(minimap, GameRulesXmlTest.givenG40())) {
      final Game repaired =
          ProductionXml.withRepairChange(
              game,
              g ->
                  ProductionXml.withFrontierOf(
                      ProductionXml.withCost(
                          ProductionXml.withNewRule(
                              ProductionXml.withFrontier(g, "fieldRepair"),
                              "fieldRepair",
                              "infantry",
                              1,
                              "repair"),
                          "repairInfantry",
                          MapXml.PUS,
                          2),
                      "Russians",
                      "fieldRepair"));

      final Game view = ProductionXml.repairView(repaired);
      assertThat(ProductionXml.rulesIn(view, "fieldRepair"))
          .as("the new repair rule, named for what it repairs")
          .containsExactly("repairInfantry");
      assertThat(ProductionXml.frontierOf(view, "Russians")).as("moved").contains("fieldRepair");
      assertThat(ProductionXml.rules(repaired))
          .as("the rules to buy stay as they were")
          .isEqualTo(ProductionXml.rules(game));
      assertThat(ProductionXml.withRepairChange(game, g -> g))
          .as("a change of nothing is no step")
          .isSameAs(game);
      final GameData data =
          RulesXmlTest.givenLoaded(repaired, folder.resolve(game.hashCode() + ""));
      final RepairFrontier frontier =
          data.getPlayerList().getPlayerId("Russians").getRepairFrontier();
      assertThat(frontier.getName()).as("the engine's repair frontier").isEqualTo("fieldRepair");
      assertThat(
              frontier.getRules().get(0).getCosts()
                  .getInt(data.getResourceList().getResourceOrThrow(MapXml.PUS)))
          .as("at the changed cost")
          .isEqualTo(2);
    }
  }

  @DisplayName("Resources are added, renamed and removed with the nations' amounts at the start")
  @Test
  void changesResources(@TempDir final Path folder) {
    for (final Game game : List.of(minimap, GameRulesXmlTest.givenG40())) {
      final Game added = ProductionXml.withResource(game, "Oil");
      final Game given = MapXml.withStartingResource(added, "Russians", "Oil", 7);
      final Game sold = ProductionXml.withNewRule(given, "production", "Oil", 2);
      final Game renamed = ProductionXml.withRenamedResource(sold, "Oil", "Fuel");

      assertThat(ProductionXml.resources(renamed))
          .as("renamed")
          .contains("Fuel")
          .doesNotContain("Oil");
      assertThat(MapXml.startingResource(renamed, "Russians", "Fuel"))
          .as("with the amount at the start")
          .isEqualTo(7);
      assertThat(ProductionXml.whyResourceStays(renamed, "Fuel"))
          .as("a rule gives it, so it stays")
          .isPresent();
      assertThat(ProductionXml.whyResourceNotRenamed(renamed, MapXml.PUS))
          .as("TripleA reads PUs by name")
          .isPresent();
      final GameData data =
          RulesXmlTest.givenLoaded(renamed, folder.resolve(game.hashCode() + ""));
      assertThat(
              data.getPlayerList()
                  .getPlayerId("Russians")
                  .getResources()
                  .getQuantity(data.getResourceList().getResourceOrThrow("Fuel")))
          .as("the engine gives the nation its amount")
          .isEqualTo(7);
      final Game removed = ProductionXml.withoutResource(given, "Oil");
      assertThat(ProductionXml.resources(removed)).as("removed").doesNotContain("Oil");
      assertThat(MapXml.startingResource(removed, "Russians", "Oil"))
          .as("with its amounts")
          .isZero();
      RulesXmlTest.givenLoaded(removed, folder.resolve("removed" + game.hashCode()));
    }
  }

  @DisplayName("A resource an attachment names is neither renamed nor removed")
  @Test
  void keepsNamedResources() {
    final Game g40 = GameRulesXmlTest.givenG40();

    assertThat(ProductionXml.whyResourceStays(g40, "SuicideAttackTokens"))
        .as("the Japanese suicideAttackResources name it")
        .hasValueSatisfying(why -> assertThat(why).contains("playerAttachment of Japanese"));
    assertThat(ProductionXml.whyResourceNotRenamed(g40, "techTokens"))
        .as("TripleA rolls for techs with it")
        .isPresent();
  }

  @DisplayName("Choosing a frontier's nations moves the ticked, leaves the unticked without one")
  @Test
  void setsFrontierUsers(@TempDir final Path folder) {
    final String tech = "productionIndustrialTechnology";

    final Game moved = ProductionXml.withFrontierUsers(minimap, tech, List.of("Italians"));
    final Game none = ProductionXml.withFrontierUsers(moved, "production", List.of());

    assertThat(ProductionXml.frontierOf(moved, "Italians")).as("ticked, moved").contains(tech);
    assertThat(ProductionXml.frontierOf(moved, "Russians"))
        .as("a nation of another frontier stays")
        .contains("production");
    assertThat(ProductionXml.frontierOf(none, "Russians")).as("unticked, buys nothing").isEmpty();
    assertThat(ProductionXml.frontierOf(none, "Italians"))
        .as("a nation of another frontier is not touched")
        .contains(tech);
    final GameData data = RulesXmlTest.givenLoaded(moved, folder);
    assertThat(data.getPlayerList().getPlayerId("Italians").getProductionFrontier().getName())
        .as("the engine reads the moved nation's frontier")
        .isEqualTo(tech);
  }

  @DisplayName("A new rule gives its amount for its cost, and the game loads")
  @Test
  void newRuleWithAmountLoads(@TempDir final Path folder) {
    final Game sold = ProductionXml.withNewRule(minimap, "production", "armour", 2, 5, "buy");

    final GameData data = RulesXmlTest.givenLoaded(sold, folder);
    final ProductionRule rule =
        data.getPlayerList().getPlayerId("Russians").getProductionFrontier().getRules().stream()
            .filter(r -> r.getName().equals("buyArmour2"))
            .findFirst()
            .orElseThrow();
    assertThat(rule.getResults().totalValues()).as("two armour").isEqualTo(2);
    assertThat(rule.getCosts().getInt(data.getResourceList().getResourceOrThrow(MapXml.PUS)))
        .as("for 5 PUs")
        .isEqualTo(5);
  }
}
