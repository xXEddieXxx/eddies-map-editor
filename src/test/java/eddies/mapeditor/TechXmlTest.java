package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.attachments.TechAttachment;
import games.strategy.triplea.delegate.TechAdvance;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.experimental.ExtensionMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PropertyList;
import org.triplea.map.data.elements.PropertyList.Property;
import org.triplea.map.data.elements.PropertyList.Property.Value;
import eddies.mapeditor.TechXml.UnitNumber;

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

  /**
   * The minimap with what real maps give technologies, eg 41 Oztea: bonuses per unit type, rockets,
   * airborne forces, a gained ability, and an option no TripleA knows yet.
   */
  private Game givenAbilities() {
    final Game withRadar =
        TechXml.withAbilityOptions(
            minimap,
            "longRangeAir",
            options ->
                List.of(
                    Option.builder().name("radarBonus").value("1:aaGun").build(),
                    Option.builder().name("radarBonus").value("factory").count("2").build()));
    return TechXml.withAbilityOptions(
        withRadar,
        "rocket",
        options ->
            List.of(
                Option.builder().name("rocketDiceNumber").value("1:aaGun").build(),
                Option.builder().name("rocketDistance").value("4").build(),
                Option.builder().name("futureBonus").value("7:infantry").build(),
                Option.builder().name("airborneTypes").value("infantry").build(),
                Option.builder().name("airborneTypes").value("armour").build(),
                Option.builder().name("airborneTargettedByAA").value("AA:infantry").build(),
                Option.builder().name("unitAbilitiesGained").value("armour:canBlitz").build()));
  }

  @DisplayName("What a technology gives reads in plain parts: numbers per unit type, lists, numbers")
  @Test
  void readsWhatATechnologyGives() {
    final Game game = givenAbilities();

    assertThat(TechXml.abilityOptions(game, "longRangeAir").stream().map(TechXml::unitNumber))
        .as("a bonus per unit type, also one written with a count as TripleA joins it")
        .containsExactly(
            Optional.of(new UnitNumber("radarBonus", 1, "aaGun")),
            Optional.of(new UnitNumber("radarBonus", 2, "factory")));
    assertThat(TechXml.abilityNumber(game, "rocket", TechXml.ROCKET_DISTANCE))
        .as("a number that is set")
        .isEqualTo(4);
    assertThat(TechXml.abilityNumber(game, "rocket", TechXml.ROCKETS_PER_TERRITORY))
        .as("one that is not: TripleA's own, 1")
        .isEqualTo(1);
    assertThat(TechXml.abilityNumber(game, "rocket", TechXml.REPAIR_DISCOUNT))
        .as("no discount is -1, shown as a blank word")
        .isEqualTo(-1);
    assertThat(TechXml.abilityUnits(game, "rocket", TechXml.AIRBORNE_TYPES))
        .as("a list over its entries")
        .containsExactly("infantry", "armour");
    assertThat(TechXml.gaining(game, "rocket", TechXml.CAN_BLITZ))
        .as("who gains blitz")
        .containsExactly("armour");
    assertThat(TechXml.shotAtBy(game, "rocket"))
        .as("anti-air by its kind")
        .containsExactly(Map.entry("AA", List.of("infantry")));
    assertThat(TechXml.otherAbilityOptions(game, "rocket"))
        .as("the one option the editor has no words for")
        .extracting(Option::getName)
        .containsExactly("futureBonus");
    assertThat(TechXml.ability(game, "superSub")).as("a technology without any").isEmpty();
    assertThat(
            TechXml.abilityOptions(
                AttachmentsXml.withAttachments(
                    minimap,
                    List.of(
                        Builders.toBuilder(
                                AttachmentsXml.newAttachment(
                                    "TechAbilityAttachment", "techAbilityAttachment", "Super subs"))
                            .options(
                                List.of(
                                    Option.builder().name("attackBonus").value("1:submarine").build()))
                            .build())),
                "superSub"))
        .as("attached to the name the game shows, as eg Operation Dandelion does: TripleA finds it")
        .hasSize(1);
    assertThat(game.getTechnology())
        .as("no list is written with it: TripleA attaches to its standard technologies too")
        .isNull();
  }

  @DisplayName("A changed value changes only its option; the others, also unknown ones, stay")
  @Test
  void changesOneValue(@TempDir final Path folder) {
    final Game game = givenAbilities();
    final List<Option> before = TechXml.abilityOptions(game, "rocket");

    final Game farther = TechXml.withAbilityNumber(game, "rocket", TechXml.ROCKET_DISTANCE, 5);
    final Game moreDice =
        TechXml.withUnitNumberAt(farther, "rocket", 0, new UnitNumber("rocketDiceNumber", 2, "aaGun"));
    final Game bombard =
        TechXml.withGaining(moreDice, "rocket", TechXml.CAN_BOMBARD, List.of("armour", "battleship"));
    final Game unset = TechXml.withAbilityNumber(bombard, "rocket", TechXml.ROCKET_DISTANCE, 0);

    final List<Option> after = TechXml.abilityOptions(farther, "rocket");
    assertThat(after).as("as many options, in their places").hasSameSizeAs(before);
    assertThat(after.get(1).getValue()).as("the distance").isEqualTo("5");
    for (final int kept : List.of(0, 2, 3, 4, 5, 6)) {
      assertThat(after.get(kept)).as("option %s is untouched", kept).isSameAs(before.get(kept));
    }
    assertThat(TechXml.withAbilityNumber(game, "rocket", TechXml.ROCKET_DISTANCE, 4))
        .as("the value it has is no change, so no step")
        .isSameAs(game);
    assertThat(TechXml.withAbilityNumber(game, "rocket", TechXml.REPAIR_DISCOUNT, 150))
        .as("more than TripleA takes, 100 %, is not written: the game would not load")
        .isSameAs(game);
    assertThat(TechXml.withAbilityNumber(game, "rocket", TechXml.ROCKET_DISTANCE, -1))
        .as("nor a number below 0 where none is TripleA's own")
        .isSameAs(game);
    assertThat(
            TechXml.abilityNumber(
                TechXml.withAbilityNumber(game, "rocket", TechXml.REPAIR_DISCOUNT, 100),
                "rocket",
                TechXml.REPAIR_DISCOUNT))
        .as("the most it takes is written")
        .isEqualTo(100);
    assertThat(
            TechXml.withUnitNumberAt(game, "rocket", 1, new UnitNumber("rocketDiceNumber", 2, "aaGun")))
        .as("a row that is not this number, eg after an undo, changes nothing")
        .isSameAs(game);
    assertThat(TechXml.abilityOptions(moreDice, "rocket").getFirst().getValue())
        .as("the number for the unit type")
        .isEqualTo("2:aaGun");
    assertThat(TechXml.abilityOptions(bombard, "rocket").stream().map(TechXml::read))
        .as("blitz kept, bombard added, a unit an entry, where the entry was")
        .endsWith("armour:canBlitz:canBombard", "battleship:canBombard");
    assertThat(TechXml.abilityOptions(unset, "rocket"))
        .as("TripleA's own number takes the option out")
        .extracting(Option::getName)
        .doesNotContain("rocketDistance")
        .contains("futureBonus");
    assertThat(TechXml.abilityOptions(unset, "longRangeAir"))
        .as("another technology's options are the same objects")
        .containsExactlyElementsOf(TechXml.abilityOptions(game, "longRangeAir"));
    // futureBonus is made up; without it TripleA takes all the rest
    RulesXmlTest.givenLoaded(
        TechXml.withAbilityOptions(
            unset,
            "rocket",
            options -> options.stream().filter(o -> !"futureBonus".equals(o.getName())).toList()),
        folder);
  }

  @DisplayName("The first value makes the attachment TripleA reads; research costs per nation")
  @Test
  void makesTheAttachment(@TempDir final Path folder) {
    final Game subs =
        TechXml.withUnitNumberAdded(minimap, "superSub", new UnitNumber("attackBonus", 2, "submarine"));
    final Game airborne =
        TechXml.withShotAtBy(
            TechXml.withAbilityUnits(
                TechXml.withAbilityFlag(subs, "jetPower", TechXml.AIRBORNE_FORCES),
                "jetPower",
                TechXml.AIRBORNE_BASES,
                List.of("factory")),
            "jetPower",
            "AA",
            List.of("infantry", "armour"));
    final Game costly = TechXml.withTechCost(airborne, "Russians", 7);

    final Attachment made = TechXml.ability(subs, "superSub").orElseThrow().getValue();
    assertThat(List.of(made.getName(), made.getType(), made.getJavaClass()))
        .as("name, type and class as TripleA reads a technology's abilities")
        .containsExactly(
            "techAbilityAttachment",
            "technology",
            "games.strategy.triplea.attachments.TechAbilityAttachment");
    assertThat(TechXml.abilityOptions(airborne, "jetPower").stream().map(TechXml::read))
        .as("a flag, a list and the anti-air's targets as TripleA writes them")
        .containsExactly("true", "factory", "AA:infantry:armour");
    assertThat(TechXml.withoutAbilityFlag(airborne, "superSub", TechXml.AIRBORNE_FORCES))
        .as("a flag that is not set is not taken out")
        .isSameAs(airborne);
    assertThat(TechXml.techCost(minimap, "Russians")).as("TripleA's 5 when not set").isEqualTo(5);
    assertThat(TechXml.withTechCost(minimap, "Russians", 5)).as("5 again: no step").isSameAs(minimap);
    assertThat(TechXml.ability(TechXml.withoutAbility(subs, "superSub"), "superSub"))
        .as("removed: TripleA's standard effect counts again")
        .isEmpty();
    final GameData loaded = RulesXmlTest.givenLoaded(costly, folder);
    assertThat(
            ((TechAttachment)
                    loaded
                        .getPlayerList()
                        .getPlayerId("Russians")
                        .getAttachment(Constants.TECH_ATTACHMENT_NAME))
                .getTechCost())
        .as("TripleA charges the Russians 7 PUs a roll")
        .isEqualTo(7);
    assertThat(
            TechAbilityAttachment.getAllowAirborneForces(
                List.of(loaded.getTechnologyFrontier().getAdvanceByProperty("jetPower"))))
        .as("TripleA finds the attachment on the technology, also in a game without a list")
        .isTrue();
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
