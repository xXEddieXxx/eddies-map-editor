package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.TechAdvance;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.experimental.ExtensionMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PropertyList;
import org.triplea.map.data.elements.PropertyList.Property;
import org.triplea.map.data.elements.PropertyList.Property.Value;

@ExtensionMethod(Builders.class)
final class TechXmlTest {
  private static final String JETS = "Air drops";
  private static final String AIR = "Air Advances";
  private final Game minimap = MapXmlTest.givenMinimap();

  /** The minimap with paratroopers and an own technology, the Russians researching both in Air. */
  private Game givenTechs() {
    final Game withTechs =
        TechXml.withTech(
            TechXml.withTech(minimap, JETS, TechAdvance.TECH_PROPERTY_PARATROOPERS), "Radio", null);
    return TechXml.withAtStart(
        TechXml.withTechIn(
            TechXml.withTechIn(withTechs, "Russians", JETS, AIR), "Russians", "Radio", AIR),
        "Italians",
        TechAdvance.TECH_PROPERTY_PARATROOPERS);
  }

  @DisplayName("Technologies, the categories nations research them in, and starting ones change")
  @Test
  void changesTechnology() {
    final Game techs = givenTechs();

    assertThat(TechXml.techNames(techs))
        .as("both added, after the standard ones")
        .endsWith(JETS, "Radio");
    assertThat(TechXml.categoryOf(techs, "Russians", JETS)).as("researched in Air").contains(AIR);
    assertThat(TechXml.categoryOf(techs, "Italians", JETS)).as("not researched").isEmpty();
    assertThat(TechXml.hasAtStart(techs, "Italians", TechAdvance.TECH_PROPERTY_PARATROOPERS))
        .as("the Italians start with paratroopers")
        .isTrue();
    final Game removed = TechXml.withoutTech(techs, JETS);
    assertThat(TechXml.categories(removed, "Russians")).as("the category stays").contains(AIR);
    assertThat(TechXml.categoryOf(removed, "Russians", JETS)).as("without it").isEmpty();
    assertThat(TechXml.categoryOf(removed, "Russians", "Radio"))
        .as("the other stays")
        .contains(AIR);
    assertThat(minimap.getTechnology()).as("the game it started from stays, for undo").isNull();
  }

  @DisplayName("Changed technology loads in the engine with the categories and starting techs")
  @Test
  void changedTechnologyLoads(@TempDir final Path folder) {
    final GameData data = RulesXmlTest.givenLoaded(givenTechs(), folder);

    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
    final TechnologyFrontier air =
        russians.getTechnologyFrontierList().getFrontiers().stream()
            .filter(f -> AIR.equals(f.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(air.getTechs()).as("both techs in the Russians' category").hasSize(2);
    assertThat(
            data.getPlayerList().getPlayerId("Italians").getTechAttachment().getParatroopers())
        .as("the Italians start with paratroopers")
        .isTrue();
  }

  @DisplayName("A removed nation takes its technology categories along, so the game still loads")
  @Test
  void removedNationLoads(@TempDir final Path folder) {
    final Game withoutRussians = MapXml.withoutNation(givenTechs(), "Russians");

    assertThat(TechXml.categories(withoutRussians, "Russians")).as("its categories gone").isEmpty();
    RulesXmlTest.givenLoaded(withoutRussians, folder);
  }

  @DisplayName("The first technology of a game without a list keeps TripleA's standard ones")
  @Test
  void firstTechKeepsTheStandardOnes(@TempDir final Path folder) {
    final GameData before = RulesXmlTest.givenLoaded(minimap, folder.resolve("before"));

    final GameData after =
        RulesXmlTest.givenLoaded(TechXml.withTech(minimap, "Radio", null), folder.resolve("after"));

    assertThat(after.getTechnologyFrontier().getTechs().stream().map(TechAdvance::getProperty))
        .as("the standard technologies, then the new one")
        .containsExactlyElementsOf(
            Stream.concat(
                    before.getTechnologyFrontier().getTechs().stream()
                        .map(TechAdvance::getProperty),
                    Stream.of("Radio"))
                .toList());
    assertThat(categories(after, "Russians"))
        .as("each nation researches them in the same categories as before")
        .isEqualTo(categories(before, "Russians"))
        .isNotEmpty();
  }

  private static Map<String, List<String>> categories(final GameData data, final String nation) {
    return data.getPlayerList().getPlayerId(nation).getTechnologyFrontierList().getFrontiers()
        .stream()
        .collect(
            Collectors.toMap(
                TechnologyFrontier::getName,
                f -> f.getTechs().stream().map(TechAdvance::getProperty).toList()));
  }

  @DisplayName("A technology built in as a standard one is told apart from it, as the engine does")
  @Test
  void techBuiltInAsAnotherIsItsOwn() {
    final String better = "Better jets";
    final String standard = "Technology Advances";
    final Game game =
        TechXml.withTechIn(
            TechXml.withTech(minimap, better, TechAdvance.TECH_PROPERTY_JET_POWER),
            "Russians",
            better,
            standard);

    final Game removed = TechXml.withoutTech(game, better);

    assertThat(TechXml.categoryOf(game, "Russians", better)).as("its own place").contains(standard);
    assertThat(TechXml.categoryOf(removed, "Russians", TechAdvance.TECH_PROPERTY_JET_POWER))
        .as("the standard jetPower stays when the other goes")
        .contains(standard);
  }

  @DisplayName("The standard technologies follow a WW2V3 option given as a <value> child")
  @Test
  void standardTechsReadTheValueChild() {
    final Game ww2v3 =
        minimap.toBuilder()
            .propertyList(
                PropertyList.builder()
                    .properties(
                        List.of(
                            Property.builder()
                                .name(Constants.WW2V3)
                                .valueProperty(Value.builder().data("true").build())
                                .build()))
                    .build())
            .build();

    assertThat(TechXml.categories(TechXml.withStandardTechs(ww2v3), "Russians"))
        .as("the WW2V3 categories")
        .containsExactly("Air and Naval Advances", "Land and Production Advances");
  }
}
