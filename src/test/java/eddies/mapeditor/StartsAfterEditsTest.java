package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.triplea.Constants;
import games.strategy.triplea.settings.ClientSetting;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay.Delegate;
import org.triplea.map.data.elements.VariableList;
import org.triplea.map.data.elements.VariableList.Variable.Element;
import org.triplea.map.data.elements.VariableList.Variable;
import org.triplea.map.xml.writer.GameXmlWriter;
import eddies.mapeditor.MapChecks.Problem;

/** Changes the editor makes that TripleA's parser took but its game then stopped at. */
final class StartsAfterEditsTest {
  @TempDir Path folder;

  @BeforeAll
  static void givenTripleASettings() {
    ClientSetting.initialize();
  }

  private Path givenFileOf(final Game game) throws Exception {
    final Path xml = folder.resolve("game" + System.nanoTime() + ".xml");
    GameXmlWriter.exportXml(game, xml);
    return xml;
  }

  private static Attachment givenAttachment(
      final String name, final String nation, final String javaClass, final Option... options) {
    return Attachment.builder()
        .name(name)
        .attachTo(nation)
        .javaClass("games.strategy.triplea.attachments." + javaClass)
        .type("player")
        .options(List.of(options))
        .build();
  }

  /** The minimap where a trigger of the Russians asks for a condition attached to the Italians. */
  private static Game givenConditionOfItaliansAskedByRussians() {
    final Game minimap = MapXmlTest.givenMinimap();
    final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(minimap));
    attachments.add(
        givenAttachment(
            "conditionAttachmentRound2",
            "Italians",
            "RulesAttachment",
            Option.builder().name("rounds").value("2").build()));
    attachments.add(
        givenAttachment(
            "triggerAttachmentRussiansBonus",
            "Russians",
            "TriggerAttachment",
            Option.builder().name("conditions").value("conditionAttachmentRound2").build(),
            Option.builder().name("resource").value("PUs").build(),
            Option.builder().name("resourceCount").value("5").build()));
    return AttachmentsXml.withAttachments(minimap, attachments);
  }

  private static List<String> texts(final List<Problem> problems) {
    return problems.stream().map(Problem::text).toList();
  }

  @DisplayName("An owner painted on new land adds its attachment behind the territories' too")
  @Test
  void paintedOwnerAddsTheAttachmentAmongTheOthers() {
    // given: new land without an attachment, in a game whose unit attachments come later
    final Game minimap = MapXmlTest.givenMinimap();
    final List<Attachment> territoriesFirst =
        Stream.concat(
                AttachmentsXml.attachments(minimap).stream()
                    .filter(a -> AttachmentsXml.isNamed(a, "territoryAttachment")),
                AttachmentsXml.attachments(minimap).stream()
                    .filter(a -> !AttachmentsXml.isNamed(a, "territoryAttachment")))
            .toList();
    final Game withLand =
        MapXml.withTerritory(
            AttachmentsXml.withAttachments(minimap, territoriesFirst),
            "Atlantis",
            List.of("red capitol"));
    final Game bare =
        AttachmentsXml.withAttachments(
            withLand,
            AttachmentsXml.attachments(withLand).stream()
                .filter(a -> !"Atlantis".equals(a.getAttachTo()))
                .toList());

    // when: the split of a territory with an owner does this
    final List<Attachment> attachments =
        AttachmentsXml.attachments(MapXml.withOwner(bare, "Atlantis", "Russians"));

    // then
    final List<String> names = attachments.stream().map(Attachment::getName).toList();
    assertThat(names.indexOf("unitAttachment"))
        .as("given: unit attachments follow the territories'")
        .isGreaterThan(names.indexOf("territoryAttachment"));
    assertThat(attachments.stream().map(Attachment::getAttachTo).toList().indexOf("Atlantis"))
        .as("read before the unit attachments, one of which may ask for every territory's")
        .isBetween(0, names.indexOf("unitAttachment") - 1);
  }

  @DisplayName("A new territory's attachment goes behind the last territory attachment")
  @Test
  void newTerritoryAttachmentJoinsTheOthers() {
    // given: land without an attachment; unit attachments follow the territories' in the game
    final Game minimap = MapXmlTest.givenMinimap();
    final Game withLand = MapXml.withTerritory(minimap, "Atlantis", List.of("red capitol"));

    // when
    final List<Attachment> attachments =
        AttachmentsXml.attachments(MapXml.withTerritoryAttachments(withLand));

    // then
    final int added =
        attachments.indexOf(
            attachments.stream()
                .filter(a -> "Atlantis".equals(a.getAttachTo()))
                .findFirst()
                .orElseThrow());
    final int lastBefore =
        AttachmentsXml.attachments(minimap).stream()
            .filter(a -> AttachmentsXml.isNamed(a, "territoryAttachment"))
            .mapToInt(AttachmentsXml.attachments(minimap)::indexOf)
            .max()
            .orElseThrow();
    assertThat(added)
        .as("TripleA reads attachments in order: one read later asks for every territory's")
        .isEqualTo(lastBefore + 1);
    assertThat(attachments)
        .as("nothing else lost")
        .hasSize(AttachmentsXml.attachments(minimap).size() + 1);
  }

  @DisplayName("A renamed territory is renamed in the variables' lists too")
  @Test
  void renamedTerritoryFollowsInVariables() throws Exception {
    // given: a variable listing territories, as options and foreach read them
    final Game minimap = MapXmlTest.givenMinimap();
    final Game withVariable =
        Builders.toBuilder(minimap)
            .variableList(
                VariableList.builder()
                    .variables(
                        List.of(
                            Variable.builder()
                                .name("Capitals")
                                .elements(
                                    List.of(
                                        Element.builder().name("red capitol").build(),
                                        Element.builder().name("blue capitol").build()))
                                .build()))
                    .build())
            .build();

    // when
    final Game renamed = MapXml.withRenamedTerritory(withVariable, "blue capitol", "Rome");

    // then
    assertThat(renamed.getVariableList().getVariables().getFirst().getElements())
        .as("TripleA does not load a game whose variable names a territory it does not have")
        .extracting(Element::getName)
        .containsExactly("red capitol", "Rome");
    assertThat(MapChecks.findError(givenFileOf(renamed))).as("TripleA loads it").isEmpty();
  }

  @DisplayName("A nation is not removed while another's trigger asks for a condition attached to it")
  @Test
  void nationWithAConditionOthersAskForStays() throws Exception {
    // given
    final MapProject project =
        MapProject.open(givenFileOf(givenConditionOfItaliansAskedByRussians()));
    final Game before = project.game();

    // when
    final List<String> stillNamed = project.removeNation("Italians");

    // then
    assertThat(stillNamed)
        .as("the condition would go with the nation: told where it is asked for")
        .contains("triggerAttachmentRussiansBonus of Russians: conditions");
    assertThat(project.game()).as("refused: the game as it was").isSameAs(before);
  }

  @DisplayName("A step that activates technology brings the delegate named tech it asks")
  @Test
  void techActivationBringsTheTechDelegate() throws Exception {
    // given: a game without technology, and the kind of step Turn order offers
    final Game minimap = MapXmlTest.givenMinimap();
    final Game withoutTech =
        Builders.toBuilder(minimap)
            .gamePlay(
                Builders.toBuilder(minimap.getGamePlay())
                    .delegates(
                        minimap.getGamePlay().getDelegates().stream()
                            .filter(d -> !d.getName().startsWith("tech"))
                            .toList())
                    .build())
            .build();
    final GameRulesXml.DelegateKind activation =
        GameRulesXml.DELEGATE_KINDS.stream()
            .filter(kind -> kind.javaClass().endsWith("TechActivationDelegate"))
            .findFirst()
            .orElseThrow();

    // when
    final Game withStep = GameRulesXml.withDelegateStep(withoutTech, 1, activation, "Russians");

    // then
    assertThat(withStep.getGamePlay().getDelegates())
        .as("TripleA asks the delegate of this very name what was researched")
        .extracting(Delegate::getName)
        .contains("tech");
    assertThat(MapChecks.startError(givenFileOf(withStep), null))
        .as("the game plays through the step")
        .isEmpty();
    assertThat(texts(MapChecks.quick(withStep, MapFiles.none())))
        .as("nothing to tell")
        .noneMatch(text -> text.contains("activates technology"));
  }

  private static GameRulesXml.DelegateKind givenKind(final String javaClass) {
    return GameRulesXml.DELEGATE_KINDS.stream()
        .filter(kind -> kind.javaClass().endsWith(javaClass))
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("A step that activates technology without a delegate named tech is a problem")
  @Test
  void techActivationWithoutTheTechDelegateIsAProblem() {
    // given: the step as a map written by hand has it, the delegate named tech missing
    final Game withStep =
        GameRulesXml.withDelegateStep(
            MapXmlTest.givenMinimap(), 1, givenKind("TechActivationDelegate"), "Russians");
    final Game withoutTech =
        Builders.toBuilder(withStep)
            .gamePlay(
                Builders.toBuilder(withStep.getGamePlay())
                    .delegates(
                        withStep.getGamePlay().getDelegates().stream()
                            .filter(d -> !"tech".equals(d.getName()))
                            .toList())
                    .build())
            .build();

    // when
    final List<Problem> problems = MapChecks.quick(withoutTech, MapFiles.none());

    // then
    assertThat(problems)
        .as("TripleA asks the delegate named tech what was researched, and stops without it")
        .anySatisfy(
            problem -> {
              assertThat(problem.text()).contains("activates technology");
              assertThat(problem.tool()).contains("Turn order");
            });
  }

  @DisplayName("An infantry per territory without a unit type infantry is a problem of Turn order")
  @Test
  void purchaseByTerritoryNeedsInfantry() {
    // given: the option on and a step that buys without PUs
    final String option = Constants.PRODUCTION_PER_VALUED_TERRITORY_RESTRICTED;
    final Game withInfantry =
        GameRulesXml.withDelegateStep(
            GameRulesXml.withOptionValue(
                GameRulesXml.withNewOption(
                    MapXmlTest.givenMinimap(), option, GameRulesXml.Kind.YES_NO),
                option,
                "true"),
            1,
            givenKind("NoPUPurchaseDelegate"),
            "Russians");
    final Game withoutInfantry = MapXml.withoutUnitType(withInfantry, "infantry");

    // when
    final List<Problem> problems = MapChecks.quick(withoutInfantry, MapFiles.none());

    // then
    assertThat(MapXml.unitTypes(withoutInfantry)).as("given").doesNotContain("infantry");
    assertThat(problems)
        .as("TripleA gives an infantry per territory there and stops without the unit type")
        .anySatisfy(
            problem -> {
              assertThat(problem.text()).contains("no unit type infantry");
              assertThat(problem.tool()).contains("Turn order");
            });
    assertThat(texts(MapChecks.quick(withInfantry, MapFiles.none())))
        .as("with infantry: nothing to say")
        .noneMatch(text -> text.contains("no unit type infantry"));
  }

  @DisplayName("A unit tint that is no color is a problem of Map look")
  @Test
  void unitTintThatIsNoColorIsAProblem() {
    // given
    final MapFiles files = MapFiles.none().withProperty(MapFiles.UNIT_TINT + "Russians", "red");

    // when
    final List<Problem> problems = MapChecks.quick(MapXmlTest.givenMinimap(), files);

    // then
    assertThat(problems)
        .as("TripleA stops where it draws the nation's units")
        .anySatisfy(
            problem -> {
              assertThat(problem.text()).contains("Russians's unit color").contains("red");
              assertThat(problem.tool()).contains("Map look");
            });
  }

  @DisplayName("A unit type whose attachment a foreach may make is not taken for a land unit at sea")
  @Test
  void unitTypeMadeByForeachIsNoLandUnitAtSea() {
    // given: units at sea of a type without a written attachment
    final Game galleys =
        MapXml.withUnits(MapXmlTest.givenMinimap(), "sea zone 1", "Russians", "galley", 3);
    final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(galleys));
    attachments.add(
        Attachment.builder()
            .name("unitAttachment")
            .attachTo("@ship@")
            .foreach("$Ships$")
            .javaClass("games.strategy.triplea.attachments.UnitAttachment")
            .type("unitType")
            .options(List.of(Option.builder().name("isSea").value("true").build()))
            .build());

    // when
    final List<Problem> byForeach =
        MapChecks.quick(AttachmentsXml.withAttachments(galleys, attachments), MapFiles.none());

    // then
    assertThat(texts(MapChecks.quick(galleys, MapFiles.none())))
        .as("given: without the loop a unit type without attachment is a land unit")
        .anyMatch(text -> text.contains("land units"));
    assertThat(texts(byForeach))
        .as("the loop may make it a ship: not known here, so nothing is said")
        .noneMatch(text -> text.contains("land units"));
  }

  @DisplayName("Land units in a sea zone without a transport for them are a problem of that zone")
  @Test
  void landUnitsAtSeaAreAProblem() throws Exception {
    // given
    final Game minimap = MapXmlTest.givenMinimap();
    final Game atSea = MapXml.withUnits(minimap, "sea zone 1", "Russians", "infantry", 3);
    final Game carried = MapXml.withUnits(atSea, "sea zone 1", "Russians", "transport", 2);

    // when
    final List<Problem> ofUnitsAtSea = MapChecks.quick(atSea, MapFiles.none());

    // then
    assertThat(ofUnitsAtSea)
        .as("three infantry, no transport: TripleA stops when the game begins")
        .anySatisfy(
            problem -> {
              assertThat(problem.text()).contains("land units").contains("infantry");
              assertThat(problem.territory()).contains("sea zone 1");
            });
    assertThat(MapChecks.startError(givenFileOf(atSea), null))
        .as("as TripleA says it")
        .hasValueSatisfying(why -> assertThat(why).contains("sea transports"));
    assertThat(texts(MapChecks.quick(carried, MapFiles.none())))
        .as("two transports of 5 carry three infantry of 2")
        .noneMatch(text -> text.contains("land units"));
    assertThat(MapChecks.startError(givenFileOf(carried), null)).as("TripleA starts it").isEmpty();
  }

  @DisplayName("Pacific Theater without a nation Japanese is a problem of the game options")
  @Test
  void pacificTheaterNeedsJapanese() {
    // given
    final Game pacific =
        GameRulesXml.withOptionValue(
            GameRulesXml.withNewOption(
                MapXmlTest.givenMinimap(), "Pacific Theater", GameRulesXml.Kind.YES_NO),
            "Pacific Theater",
            "true");

    // when
    final List<Problem> problems = MapChecks.quick(pacific, MapFiles.none());

    // then
    assertThat(problems)
        .as("TripleA asks Japanese for its victory points at the end of a round")
        .anySatisfy(
            problem -> {
              assertThat(problem.text()).contains("Pacific Theater").contains("Japanese");
              assertThat(problem.tool()).contains("Game options");
            });
    assertThat(texts(MapChecks.quick(MapXmlTest.givenMinimap(), MapFiles.none())))
        .as("without the option: nothing to say")
        .noneMatch(text -> text.contains("Pacific"));
  }

  /**
   * The minimap making its territory attachments in a loop over a variable of all its land, as
   * big maps do: no capital is named in it.
   */
  private static Game givenGameByForeach() {
    final Game minimap = MapXmlTest.givenMinimap();
    final List<Element> land =
        MapXml.territories(minimap).stream()
            .filter(t -> !MapXml.isWater(minimap, t))
            .map(t -> Element.builder().name(t).build())
            .toList();
    final List<Attachment> attachments =
        new ArrayList<>(
            AttachmentsXml.attachments(minimap).stream()
                .filter(a -> !AttachmentsXml.isNamed(a, "territoryAttachment"))
                .toList());
    attachments.add(
        Attachment.builder()
            .name("territoryAttachment")
            .attachTo("@land@")
            .foreach("$AllLand$")
            .javaClass("games.strategy.triplea.attachments.TerritoryAttachment")
            .type("territory")
            .options(List.of(Option.builder().name("production").value("1").build()))
            .build());
    return Builders.toBuilder(AttachmentsXml.withAttachments(minimap, attachments))
        .variableList(
            VariableList.builder()
                .variables(List.of(Variable.builder().name("AllLand").elements(land).build()))
                .build())
        .build();
  }

  @DisplayName("A nation that loses its capital becomes optional in a game with foreach too")
  @Test
  void nationWithoutCapitalIsOptionalBesideForeach() {
    // given: loops make the territory attachments, but none names a capital
    final Game game = givenGameByForeach();

    // when
    final Game whole = MapXml.withoutCapitalOptional(game);

    // then
    assertThat(whole.getPlayerList().getPlayers())
        .as("no capital is left in this game: every nation with steps may have none")
        .allMatch(player -> Boolean.TRUE.equals(player.getOptional()));
  }
}
