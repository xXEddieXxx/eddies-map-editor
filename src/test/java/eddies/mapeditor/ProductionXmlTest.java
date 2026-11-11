package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.ProductionFrontier;
import games.strategy.engine.data.ProductionRule;
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
}
