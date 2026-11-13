package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.generic.xml.reader.XmlMapper;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;

/** Names as old maps write them and TripleA still reads: "Attatchment", "ConvoyRoute". */
final class OldSpellingTest {
  private static final String LAND = "Gaul";
  private static final String SEA = "Sea Zone 1";

  /** A game as TripleA 1.8 maps wrote it: attatchment tags and names, options with capitals. */
  private static Game givenOldGame() throws Exception {
    final String xml =
        """
        <game>
          <map>
            <territory name="Gaul"/>
            <territory name="Sea Zone 1" water="true"/>
            <territory name="Sea Zone 2" water="true"/>
            <territory name="Alps"/>
          </map>
          <playerList><player name="Romans" optional="false"/></playerList>
          <unitList><unit name="infantry"/></unitList>
          <territoryEffectList><territoryEffect name="Forest"/></territoryEffectList>
          <attatchmentList>
            <attatchment name="territoryAttatchment" attatchTo="Gaul" type="territory"
                javaClass="games.strategy.triplea.attatchments.TerritoryAttachment">
              <option name="production" value="3"/>
              <option name="capital" value="Romans"/>
              <option name="victoryCity" value="1"/>
            </attatchment>
            <attatchment name="territoryAttatchment" attatchTo="Sea Zone 1" type="territory"
                javaClass="games.strategy.triplea.attatchments.TerritoryAttachment">
              <option name="ConvoyRoute" value="true"/>
              <option name="ConvoyAttached" value="Gaul"/>
              <option name="isImpassible" value="true"/>
            </attatchment>
            <attatchment name="unitAttatchment" attatchTo="infantry" type="unitType"
                javaClass="games.strategy.triplea.attatchments.UnitAttachment">
              <option name="TransportCost" value="2"/>
              <option name="isInfantry" value="true"/>
            </attatchment>
            <attatchment name="territoryAttatchment" attatchTo="Alps" type="territory"
                javaClass="games.strategy.triplea.attatchments.TerritoryAttachment">
              <option name="TerritoryEffect" value="Forest"/>
            </attatchment>
            <attatchment name="canalAttatchmentSuez" attatchTo="Sea Zone 2" type="territory"
                javaClass="games.strategy.triplea.attatchments.CanalAttachment">
              <option name="CanalName" value="Suez"/>
              <option name="LandTerritories" value="Gaul"/>
            </attatchment>
            <attatchment name="playerAttatchment" attatchTo="Romans" type="player"
                javaClass="games.strategy.triplea.attatchments.PlayerAttachment">
              <option name="ShareTechnology" value="Greeks"/>
            </attatchment>
            <attatchment name="politicalActionAttatchmentWar" attatchTo="Romans" type="player"
                javaClass="games.strategy.triplea.attatchments.PoliticalActionAttachment">
              <option name="RelationshipChange" value="Romans:Greeks:war"/>
            </attatchment>
          </attatchmentList>
        </game>
        """;
    return new XmlMapper(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
        .mapXmlToObject(Game.class);
  }

  private static List<Attachment> attachmentsOf(final Game game, final String attachTo) {
    return AttachmentsXml.attachments(game).stream()
        .filter(a -> attachTo.equals(a.getAttachTo()))
        .toList();
  }

  private static List<String> optionNames(final Game game, final String attachTo) {
    return attachmentsOf(game, attachTo).stream()
        .flatMap(a -> AttachmentsXml.options(a).stream())
        .map(Option::getName)
        .toList();
  }

  @DisplayName("A territoryAttatchment gives the territory its PUs, capital and victory city")
  @Test
  void readsOldAttachmentNames() throws Exception {
    final Game game = givenOldGame();

    assertThat(MapXml.production(game, LAND)).as("its PUs").isEqualTo(3);
    assertThat(MapXml.capitalOf(game, LAND)).as("its capital").contains("Romans");
    assertThat(MapXml.isVictoryCity(game, LAND)).as("its victory city").isTrue();
    assertThat(MapXml.nationsWithCapital(game, Set.of(LAND)))
        .as("the nations with a capital, read in one pass")
        .containsExactly("Romans");
    assertThat(UnitsXml.options(game, "infantry"))
        .as("a unitAttatchment's options")
        .extracting(Option::getName)
        .containsExactly("TransportCost", "isInfantry");
    assertThat(AttachmentsXml.hasDefaultName(attachmentsOf(game, LAND).getFirst()))
        .as("the name TripleA reads a territory's attachment by")
        .isTrue();
  }

  @DisplayName("A change goes into the old attachment, which keeps its name; no second one")
  @Test
  void changesOldAttachmentInPlace() throws Exception {
    final Game game = givenOldGame();

    final Game changed = MapXml.withCapital(MapXml.withProduction(game, LAND, 5), LAND, null);

    assertThat(attachmentsOf(changed, LAND))
        .as("still the one attachment, under the map's name")
        .extracting(Attachment::getName)
        .containsExactly("territoryAttatchment");
    assertThat(MapXml.production(changed, LAND)).as("the new PUs").isEqualTo(5);
    assertThat(optionNames(changed, LAND))
        .as("the capital taken out, the other options in place")
        .containsExactly("production", "victoryCity");
  }

  @DisplayName("An option is found as TripleA reads it: first letter in any case, old names")
  @Test
  void readsOptionsAsTripleA() throws Exception {
    final Game game = givenOldGame();

    assertThat(MapXml.hasTerritoryFlag(game, SEA, "convoyRoute"))
        .as("ConvoyRoute is convoyRoute")
        .isTrue();
    assertThat(TerritoryXml.values(game, TerritoryXml.key(SEA), "convoyAttached"))
        .as("ConvoyAttached is convoyAttached")
        .containsExactly(LAND);
    assertThat(MapXml.isImpassable(game, SEA)).as("isImpassible is isImpassable").isTrue();
    assertThat(MapXml.option(game, "unitAttachment", "infantry", "transportCost"))
        .as("TransportCost is transportCost")
        .contains("2");
    assertThat(AttachmentsXml.optionName("Convoyroute"))
        .as("only the first letter: TripleA does not know convoyroute")
        .isEqualTo("convoyroute");
    assertThat(TriggerXml.spec("RemoveUnits").map(TriggerXml.Spec::option))
        .as("a trigger's option too")
        .contains("removeUnits");
  }

  @DisplayName("A nation's list and an action's values are read and changed in the map's spelling")
  @Test
  void changesNationOptionsInPlace() throws Exception {
    final Game game = givenOldGame();
    final ActionsXml.Action war = ActionsXml.actions(game).getFirst();

    final Game changed =
        ActionsXml.withValues(
            RulesXml.withPlayerNames(game, "Romans", "shareTechnology", List.of("Greeks", "Gauls")),
            war,
            "relationshipChange",
            List.of("Romans:Greeks:war", "Romans:Gauls:war"));

    assertThat(war.isPolitical()).as("a politicalActionAttatchment is a political action").isTrue();
    assertThat(RulesXml.playerNames(game, "Romans", "shareTechnology"))
        .as("ShareTechnology of the playerAttatchment")
        .containsExactly("Greeks");
    assertThat(ActionsXml.values(game, war, "relationshipChange"))
        .as("RelationshipChange of the action")
        .containsExactly("Romans:Greeks:war");
    assertThat(optionNames(changed, "Romans"))
        .as("the nation's list once, each of the action's values, all as the map spells them")
        .containsExactly("ShareTechnology", "RelationshipChange", "RelationshipChange");
    assertThat(RulesXml.playerNames(changed, "Romans", "shareTechnology"))
        .as("the list changed")
        .containsExactly("Greeks", "Gauls");
    assertThat(attachmentsOf(changed, "Romans")).as("no attachment added").hasSize(2);
  }

  @DisplayName("Effects and canals are found by options with capitals; a renamed effect stays one")
  @Test
  void readsEffectsAndCanals() throws Exception {
    final Game game = givenOldGame();

    final Game renamed = TerritoryEffectsXml.withRenamedEffect(game, "Forest", "Woods");

    assertThat(TerritoryEffectsXml.territoriesWith(game))
        .as("TerritoryEffect names the territory's effect")
        .containsEntry("Forest", List.of("Alps"));
    assertThat(CanalXml.canals(game))
        .as("CanalName and LandTerritories of a canalAttatchment")
        .containsExactly(
            new CanalXml.Canal("Suez", List.of("Sea Zone 2"), List.of(LAND), List.of()));
    assertThat(optionNames(renamed, "Alps"))
        .as("the renamed effect in the one option, as the map spells it")
        .containsExactly("TerritoryEffect");
    assertThat(MapXml.territoryEffects(renamed, "Alps")).as("renamed").containsExactly("Woods");
  }

  @DisplayName("The assistant's options replace Uses and turns in place, without a second one")
  @Test
  void assistantChangesOptionsInPlace() {
    final Attachment trigger =
        Attachment.builder()
            .name("triggerAttachmentRise")
            .options(
                List.of(
                    TriggerXml.option("Uses", "1"),
                    TriggerXml.option("turns", "1"),
                    TriggerXml.option("RemoveUnits", "Gaul:infantry")))
            .build();

    final Attachment changed =
        TriggerXml.withOptions(
            trigger,
            List.of(
                TriggerXml.option("uses", "2"),
                TriggerXml.option("rounds", "3"),
                TriggerXml.option("removeUnits", "Gaul:infantry")));

    assertThat(AttachmentsXml.options(changed))
        .as("each option once, changed in place under the map's name")
        .extracting(Option::getName, TriggerXml::joined)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("Uses", "2"),
            org.assertj.core.groups.Tuple.tuple("turns", "3"),
            org.assertj.core.groups.Tuple.tuple("RemoveUnits", "Gaul:infantry"));
  }

  @DisplayName("Units' More options lists an old name, not an option written with a capital")
  @Test
  void moreOptionsOfAUnit() throws Exception {
    final Game game = givenOldGame();

    assertThat(UnitsEditor.moreOptions(game, "infantry"))
        .as("TransportCost shows in its section; isInfantry, which sets another, stays here")
        .extracting(Option::getName)
        .containsExactly("isInfantry");
  }

  @DisplayName("A changed option keeps the map's spelling and is not added a second time")
  @Test
  void changesOptionsInPlace() throws Exception {
    final Game game = givenOldGame();

    final Game changed =
        TerritoryXml.withValues(
            MapXml.withUnitOption(
                MapXml.withOption(game, TerritoryXml.key(SEA), "convoyRoute", "false"),
                "infantry",
                "transportCost",
                "3"),
            TerritoryXml.key(SEA),
            "convoyAttached",
            List.of("Gaul:Sea Zone 1"));

    assertThat(optionNames(changed, SEA))
        .as("the sea zone's options as the map spells them, each once")
        .containsExactly("ConvoyRoute", "ConvoyAttached", "isImpassible");
    assertThat(MapXml.hasTerritoryFlag(changed, SEA, "convoyRoute")).as("changed").isFalse();
    assertThat(TerritoryXml.values(changed, TerritoryXml.key(SEA), "convoyAttached"))
        .as("the list changed")
        .containsExactly("Gaul:Sea Zone 1");
    assertThat(optionNames(changed, "infantry"))
        .as("the unit's option, once")
        .containsExactly("TransportCost", "isInfantry");
    assertThat(MapXml.option(changed, "unitAttachment", "infantry", "transportCost"))
        .as("its new value")
        .contains("3");
  }
}
