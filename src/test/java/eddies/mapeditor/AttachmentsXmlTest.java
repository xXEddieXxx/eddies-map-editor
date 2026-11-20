package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import games.strategy.triplea.Constants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.xml.writer.GameXmlWriter;

final class AttachmentsXmlTest {
  @TempDir Path folder;

  private MapProject givenMinimap() throws Exception {
    final Path xml = folder.resolve("minimap.xml");
    try (InputStream original =
        AttachmentsXmlTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  private static Game withOptions(final Game game, final Option... options) {
    final int last = AttachmentsXml.attachments(game).size() - 1;
    Attachment attachment = AttachmentsXml.attachments(game).get(last);
    for (final Option option : options) {
      attachment = AttachmentsXml.withNewOption(attachment, option);
    }
    return AttachmentsXml.withAttachment(game, last, attachment);
  }

  private static Option option(final String name, final String value) {
    return Option.builder().name(name).value(value).build();
  }

  @DisplayName("A variable is of the kind of its elements, resolved through the variables it names;"
      + " a mixed one is of none, so no Choose list offers it")
  @Test
  void tellsAVariablesKind() throws Exception {
    final Game game =
        GameRulesXml.withVariable(
            GameRulesXml.withVariable(
                GameRulesXml.withVariable(
                    GameRulesXml.withVariable(
                        givenMinimap().game(), "Land", List.of("infantry", "armour")),
                    "Planes",
                    List.of("fighter", "$Land$")),
                "Sides",
                List.of("Russians", "Italians")),
            "Mixed",
            List.of("infantry", "Russians"));

    assertThat(GameVariables.ofKind(game, GameVariables.Kind.UNITS))
        .as("the unit variables, the nested one resolved, as $Name$")
        .containsExactly(
            Map.entry("$Land$", List.of("infantry", "armour")),
            Map.entry("$Planes$", List.of("fighter", "infantry", "armour")));
    assertThat(GameVariables.ofKind(game, GameVariables.Kind.NATIONS))
        .as("the nation variables")
        .containsOnlyKeys("$Sides$");
    assertThat(GameVariables.ofKind(game, GameVariables.Kind.TERRITORIES))
        .as("no variable holds territories")
        .isEmpty();
    assertThat(GameVariables.kindOf(game, List.of("infantry", "Russians")))
        .as("a mixed variable is of no kind")
        .isEmpty();
    assertThat(GameVariables.kindOf(game, List.of("Russians")))
        .contains(GameVariables.Kind.NATIONS);
  }

  @DisplayName("An attachment written for each element of a variable reads as one per element")
  @Test
  void expandsForEach() throws Exception {
    // given: a support for each land unit, and a trigger for each pair of nation and unit
    final Game withVariables =
        GameRulesXml.withVariable(
            GameRulesXml.withVariable(
                GameRulesXml.withVariable(
                    givenMinimap().game(), "Land", List.of("infantry", "armour")),
                "Planes",
                List.of("fighter", "$Land$")),
            "Sides",
            List.of("Russians", "Italians"));
    final Game withSupport =
        AttachmentsXml.withNewAttachment(
            withVariables, "UnitSupportAttachment", "supportAttachment_@Land@", "@Land@");
    final int support = AttachmentsXml.attachments(withSupport).size() - 1;
    final Game forEach =
        AttachmentsXml.withAttachment(
            withSupport,
            support,
            Builders.toBuilder(AttachmentsXml.attachments(withSupport).get(support))
                .foreach("$Land$")
                .options(
                    List.of(
                        option("unitType", "$Planes$"),
                        Option.builder().name("bonus").count("@Land@").value("1").build()))
                .build());
    final Game pairs =
        AttachmentsXml.withNewAttachment(
            forEach, "TriggerAttachment", "trigger_@Sides@_@Land@", "@Sides@");
    final int trigger = AttachmentsXml.attachments(pairs).size() - 1;
    final Game game =
        AttachmentsXml.withAttachment(
            pairs,
            trigger,
            Builders.toBuilder(AttachmentsXml.attachments(pairs).get(trigger))
                .foreach("$Sides$^$Land$")
                .build());

    // when
    final List<Attachment> expanded = AttachmentsXml.expanded(game);
    final List<Attachment> made =
        expanded.stream().filter(AttachmentsXml::isForEach).toList();

    // then
    assertThat(expanded).as("the ones written as they are").hasSize(
        AttachmentsXml.attachments(game).size() - 2 + 2 + 4);
    assertThat(made.subList(0, 2))
        .as("one support per land unit, @Land@ its unit, $Planes$ the planes' units joined")
        .extracting(
            Attachment::getName,
            Attachment::getAttachTo,
            a -> a.getOptions().get(0).getValue(),
            a -> a.getOptions().get(1).getCount())
        .containsExactly(
            tuple("supportAttachment_infantry", "infantry", "fighter:infantry:armour", "infantry"),
            tuple("supportAttachment_armour", "armour", "fighter:infantry:armour", "armour"));
    assertThat(made.subList(2, 6))
        .as("two variables joined by ^: one per pair")
        .extracting(Attachment::getName)
        .containsExactly(
            "trigger_Russians_infantry",
            "trigger_Russians_armour",
            "trigger_Italians_infantry",
            "trigger_Italians_armour");
    assertThat(made).as("each keeps its foreach, the mark of where it is changed")
        .allMatch(AttachmentsXml::isForEach);
    assertThat(AttachmentsXml.expanded(game)).as("the same game reads once").isSameAs(expanded);
  }

  // the two northern territories' attachments made by a loop behind the ones written for them
  private Game givenNorthMadeByLoop() throws Exception {
    return withLoop(
        GameRulesXml.withVariable(
            givenMinimap().game(), "North", List.of("red north 1", "red north 2")),
        "TerritoryAttachment",
        "territoryAttachment",
        "North",
        option("production", "7"),
        option("victoryCity", "1"));
  }

  // both nations' attachment of the kind made by a loop
  private Game givenNationsMadeByLoop(
      final String kind, final String name, final Option... options) throws Exception {
    return withLoop(
        GameRulesXml.withVariable(givenMinimap().game(), "Sides", List.of("Russians", "Italians")),
        kind,
        name,
        "Sides",
        options);
  }

  // an attachment written last, once for each element of the variable
  private static Game withLoop(
      final Game game,
      final String kind,
      final String name,
      final String variable,
      final Option... options) {
    final Game withLoop =
        AttachmentsXml.withNewAttachment(game, kind, name, "@" + variable + "@");
    final int loop = AttachmentsXml.attachments(withLoop).size() - 1;
    return AttachmentsXml.withAttachment(
        withLoop,
        loop,
        Builders.toBuilder(AttachmentsXml.attachments(withLoop).get(loop))
            .foreach("$" + variable + "$")
            .options(List.of(options))
            .build());
  }

  @DisplayName("An option naming a variable whose element is empty is skipped, as TripleA does")
  @Test
  void skipsAnOptionOfAnEmptyElement() throws Exception {
    // given: a capital only for the first of the loop's two territories
    final Game withVariables =
        GameRulesXml.withVariable(
            GameRulesXml.withVariable(
                givenMinimap().game(), "North", List.of("red north 1", "red north 2")),
            "Cap",
            List.of("Russians", ""));
    final Game withLoop =
        AttachmentsXml.withNewAttachment(
            withVariables, "TerritoryAttachment", "territoryAttachment", "@North@");
    final int loop = AttachmentsXml.attachments(withLoop).size() - 1;
    final Game game =
        AttachmentsXml.withAttachment(
            withLoop,
            loop,
            Builders.toBuilder(AttachmentsXml.attachments(withLoop).get(loop))
                .foreach("$North$:$Cap$")
                .options(List.of(option("production", "7"), option("capital", "@Cap@")))
                .build());

    // when
    final Game changed = MapXml.withProduction(game, "red north 2", 3);

    // then
    assertThat(MapXml.capitalOf(game, "red north 1")).as("the element given").contains("Russians");
    assertThat(MapXml.capitalOf(game, "red north 2")).as("no capital of nobody").isEmpty();
    assertThat(AttachmentsXml.kept(changed, "territoryAttachment", "red north 2"))
        .get()
        .extracting(a -> a.getOptions().stream().map(Option::getName).toList())
        .as("its own attachment has no option TripleA would not read")
        .isEqualTo(List.of("production"));
  }

  @DisplayName("A game changed where a loop makes the attachment still loads in TripleA")
  @Test
  void loadsAfterAChangeOfWhatALoopMakes() throws Exception {
    // given
    final Game changed = MapXml.withProduction(givenNorthMadeByLoop(), "red north 1", 3);
    final Path xml = folder.resolve("changed.xml");

    // when
    GameXmlWriter.exportXml(changed, xml);

    // then
    assertThat(MapChecks.findError(xml)).as("the engine loads the game").isEmpty();
  }

  @DisplayName("A nation's player options a loop makes are changed in its own attachment, by option,"
      + " by list, by row and as one more")
  @Test
  void changesANationsLoopMadePlayerOptions() throws Exception {
    // given
    final Game game =
        givenNationsMadeByLoop(
            "PlayerAttachment",
            Constants.PLAYER_ATTACHMENT_NAME,
            option("retainCapitalNumber", "2"),
            option("shareTechnology", "Italians"));

    // when
    final Game byOption = RulesXml.withPlayerOption(game, "Russians", "retainCapitalNumber", "3");
    final Game byList =
        RulesXml.withPlayerNames(game, "Russians", "shareTechnology", List.of("Russians"));
    final Game byRow = RulesXml.withPlayerOptionAt(game, "Russians", 0, "4");
    final Game added =
        RulesXml.withPlayerOptionAdded(game, "Russians", "placementLimit", "1:owned:all");

    // then
    assertThat(RulesXml.playerOption(game, "Russians", "retainCapitalNumber"))
        .as("read from the loop")
        .contains("2");
    assertThat(List.of(byOption, byList, byRow, added))
        .as("each leaves the other nation the loop's and loses none of the loop's options")
        .allSatisfy(
            changed -> {
              assertThat(RulesXml.playerOption(changed, "Italians", "retainCapitalNumber"))
                  .contains("2");
              assertThat(RulesXml.playerForEach(changed, "Italians")).isPresent();
              assertThat(RulesXml.playerForEach(changed, "Russians")).isEmpty();
              assertThat(RulesXml.playerOptions(changed, "Russians"))
                  .hasSizeGreaterThanOrEqualTo(2);
            });
    assertThat(RulesXml.playerOption(byOption, "Russians", "retainCapitalNumber")).contains("3");
    assertThat(RulesXml.playerNames(byList, "Russians", "shareTechnology"))
        .containsExactly("Russians");
    assertThat(RulesXml.playerOption(byRow, "Russians", "retainCapitalNumber")).contains("4");
    assertThat(RulesXml.playerOptions(added, "Russians")).hasSize(3);
    assertThat(RulesXml.withPlayerOptionAt(game, "Russians", 0, "2"))
        .as("the value it has: it goes on following the loop")
        .isSameAs(game);
  }

  @DisplayName("A nation's rules a loop makes are changed and taken away in its own attachment")
  @Test
  void changesANationsLoopMadeRules() throws Exception {
    // given
    final Game game =
        givenNationsMadeByLoop(
            "RulesAttachment",
            Constants.RULES_ATTACHMENT_NAME,
            option("placementPerTerritory", "3"),
            option("placementAnyTerritory", "true"));

    // when
    final Game changed = RulesXml.withNationRule(game, "Russians", "placementPerTerritory", "5");
    final Game without = RulesXml.withoutNationRule(game, "Russians", "placementPerTerritory");

    // then
    assertThat(RulesXml.nationRule(game, "Russians", "placementPerTerritory"))
        .as("read from the loop")
        .contains("3");
    assertThat(RulesXml.nationRule(changed, "Russians", "placementPerTerritory")).contains("5");
    assertThat(RulesXml.nationRule(changed, "Russians", "placementAnyTerritory"))
        .as("the loop's other rule is not lost")
        .contains("true");
    assertThat(RulesXml.nationRule(without, "Russians", "placementPerTerritory")).isEmpty();
    assertThat(RulesXml.nationRule(without, "Russians", "placementAnyTerritory")).contains("true");
    assertThat(RulesXml.nationRule(without, "Italians", "placementPerTerritory"))
        .as("the other nation still the loop's")
        .contains("3");
  }

  @DisplayName("A territory's list option and a new unit type's copy read what a loop makes")
  @Test
  void listsAndCopiesReadWhatALoopMakes() throws Exception {
    // given
    final Game north = givenNorthMadeByLoop();
    final Game units =
        withLoop(
            GameRulesXml.withVariable(north, "Land", List.of("infantry")),
            "UnitAttachment",
            Constants.UNIT_ATTACHMENT_NAME,
            "Land",
            option("attack", "4"));

    // when
    final Game without = TerritoryXml.withValues(north, "red north 1", "victoryCity", List.of());
    final Game copied = MapXml.withUnitType(units, "guard", "infantry");

    // then
    assertThat(TerritoryXml.values(north, "red north 1", "victoryCity"))
        .as("read from the loop")
        .containsExactly("1");
    assertThat(TerritoryXml.values(without, "red north 1", "victoryCity")).isEmpty();
    assertThat(MapXml.production(without, "red north 1"))
        .as("the loop's other value is not lost")
        .isEqualTo(7);
    assertThat(UnitsXml.option(copied, "guard", "attack"))
        .as("a new unit type starts from what the loop gives the other")
        .contains("4");
    assertThat(UnitsXml.loop(copied, "guard")).as("with an attachment of its own").isEmpty();
  }

  @DisplayName("A territory attachment a loop makes is read as TripleA keeps it, the last")
  @Test
  void readsWhatALoopMakes() throws Exception {
    // given
    final Game game = givenNorthMadeByLoop();

    // then
    assertThat(MapXml.production(game, "red north 1"))
        .as("the loop's 7 PUs, not the 5 written for it before the loop")
        .isEqualTo(7);
    assertThat(MapXml.isVictoryCity(game, "red north 2")).as("the loop's option").isTrue();
    assertThat(MapXml.production(game, "red capitol")).as("one the loop does not make").isEqualTo(10);
  }

  @DisplayName("A change of what a loop makes gives the thing its own attachment with all the loop"
      + " gives it; the loop and its other things stay")
  @Test
  void changesWhatALoopMakesInACopy() throws Exception {
    // given
    final Game game = givenNorthMadeByLoop();
    final int loop = AttachmentsXml.attachments(game).size() - 1;

    // when
    final Game changed = MapXml.withProduction(game, "red north 1", 3);
    final Game without = MapXml.withoutVictoryCity(changed, "red north 1");

    // then
    assertThat(MapXml.production(changed, "red north 1")).as("the change").isEqualTo(3);
    assertThat(MapXml.isVictoryCity(changed, "red north 1"))
        .as("what else the loop gives it is not lost")
        .isTrue();
    assertThat(AttachmentsXml.attachments(changed).get(loop))
        .as("the loop as it was")
        .isSameAs(AttachmentsXml.attachments(game).get(loop));
    assertThat(AttachmentsXml.attachments(changed).get(loop + 1))
        .as("its own attachment, behind the loop, no loop itself")
        .matches(a -> "red north 1".equals(a.getAttachTo()) && !AttachmentsXml.isForEach(a));
    assertThat(MapXml.production(changed, "red north 2")).as("the other still the loop's").isEqualTo(7);
    assertThat(MapXml.isVictoryCity(without, "red north 1"))
        .as("a value of the loop's can be taken away")
        .isFalse();
    assertThat(AttachmentsXml.leftLoops(game, changed))
        .as("told, as it no longer follows the loop")
        .containsExactly("red north 1");
    assertThat(AttachmentsXml.leftLoops(changed, without)).as("only once").isEmpty();
    assertThat(MapXml.withProduction(game, "red north 1", 7))
        .as("the value it has: no change, it follows the loop")
        .isSameAs(game);
  }

  @DisplayName("The option names TripleA's attachment classes take are offered")
  @Test
  void knowsTheOptionsOfEachKind() throws Exception {
    final Game game = givenMinimap().game();

    final List<String> trigger = AttachmentsXml.knownOptions(game, "TriggerAttachment");
    final List<String> unit = AttachmentsXml.knownOptions(game, "UnitAttachment");

    assertThat(trigger)
        .as("a trigger's options, read from TripleA's class")
        .contains("conditions", "notification", "trigger", "uses", "when")
        .doesNotContain("name", "attachedTo");
    assertThat(unit).as("a unit's options").contains("movement", "attack", "isSea");
    assertThat(AttachmentsXml.knownOptions(game, "NoSuchAttachment"))
        .as("a kind TripleA does not know has none of its own")
        .isEmpty();
  }

  @DisplayName("A condition and a trigger added, a unit's option changed and one removed: it loads")
  @Test
  void changedGameStillLoads() throws Exception {
    final MapProject project = givenMinimap();
    final int units =
        AttachmentsXml.attachments(project.game()).stream()
            .map(AttachmentsXml::kind)
            .toList()
            .indexOf("UnitAttachment");
    final Attachment unit = AttachmentsXml.attachments(project.game()).get(units);

    project.edit(
        g ->
            withOptions(
                AttachmentsXml.withNewAttachment(
                    g, "RulesAttachment", "conditionAttachmentHome", "Russians"),
                Option.builder()
                    .name("directOwnershipTerritories")
                    .value("red capitol")
                    .count("1")
                    .build()));
    project.edit(
        g ->
            withOptions(
                AttachmentsXml.withNewAttachment(
                    g, "TriggerAttachment", "triggerAttachmentHome", "Russians"),
                option("conditions", "conditionAttachmentHome"),
                option("notification", "Home"),
                option("when", "before:russianCombatMove")));
    project.edit(
        g ->
            AttachmentsXml.withAttachment(
                g,
                units,
                AttachmentsXml.withoutOption(
                    AttachmentsXml.withOption(unit, 0, option("movement", "2")), 1)));
    project.undo();
    final Attachment afterUndo = AttachmentsXml.attachments(project.game()).get(units);
    project.redo();
    final Path xml = folder.resolve("changed.xml");
    GameXmlWriter.exportXml(project.game(), xml);

    assertThat(afterUndo.getOptions())
        .as("a change is one step to undo")
        .isEqualTo(unit.getOptions());
    assertThat(AttachmentsXml.options(AttachmentsXml.attachments(project.game()).get(units)))
        .as("the unit's first option changed, its second gone, the rest kept")
        .hasSize(unit.getOptions().size() - 1)
        .first()
        .satisfies(o -> assertThat(o.getValue()).as("the new value").isEqualTo("2"));
    assertThat(MapChecks.findError(xml)).as("the engine loads the game").isEmpty();
  }

  @DisplayName("A change keeps what the attachment holds beyond it, eg foreach")
  @Test
  void keepsWhatItDoesNotChange() throws Exception {
    final Game game =
        AttachmentsXml.withNewAttachment(
            givenMinimap().game(), "TriggerAttachment", "triggerAttachmentAll", "Russians");
    final int last = AttachmentsXml.attachments(game).size() - 1;
    final Game withForeach =
        AttachmentsXml.withAttachment(
            game,
            last,
            Builders.toBuilder(AttachmentsXml.attachments(game).get(last))
                .foreach("$all$")
                .build());

    final Game changed =
        AttachmentsXml.withAttachment(
            withForeach,
            last,
            AttachmentsXml.withNewOption(
                AttachmentsXml.attachments(withForeach).get(last), option("uses", "1")));

    assertThat(AttachmentsXml.attachments(changed).get(last).getForeach())
        .as("foreach is kept")
        .isEqualTo("$all$");
    assertThat(AttachmentsXml.attachments(changed).get(last).getJavaClass())
        .as("the class as TripleA names it")
        .isEqualTo("games.strategy.triplea.attachments.TriggerAttachment");
  }

  @DisplayName("An option without a name is never added, nor does a change take its name away")
  @Test
  void refusesOptionsWithoutName() throws Exception {
    final Game game =
        AttachmentsXml.withNewAttachment(
            givenMinimap().game(), "TriggerAttachment", "triggerAttachment1", "Russians");
    final Attachment trigger =
        AttachmentsXml.withNewOption(
            AttachmentsXml.attachments(game).getLast(), option("uses", "1"));

    final Attachment added = AttachmentsXml.withNewOption(trigger, option(" ", "1"));
    final Attachment renamed = AttachmentsXml.withOption(trigger, 0, option("", "1"));

    assertThat(added).as("no option without a name added").isSameAs(trigger);
    assertThat(renamed).as("no option's name emptied").isSameAs(trigger);
  }

  @DisplayName("A new attachment takes the name TripleA reads its kind by, numbered where it may")
  @Test
  void newAttachmentsTakeTheNamesTripleAReads() throws Exception {
    final Game game = givenMinimap().game();
    final Map<String, String> tripleAs =
        Map.ofEntries(
            Map.entry("TriggerAttachment", Constants.TRIGGER_ATTACHMENT_PREFIX),
            Map.entry("RulesAttachment", Constants.RULES_CONDITION_PREFIX),
            Map.entry("PlayerAttachment", Constants.PLAYER_ATTACHMENT_NAME),
            Map.entry("PoliticalActionAttachment", Constants.POLITICALACTION_ATTACHMENT_PREFIX),
            Map.entry("UserActionAttachment", Constants.USERACTION_ATTACHMENT_PREFIX),
            Map.entry("TechAttachment", Constants.TECH_ATTACHMENT_NAME),
            Map.entry("TerritoryAttachment", Constants.TERRITORY_ATTACHMENT_NAME),
            Map.entry("CanalAttachment", Constants.CANAL_ATTACHMENT_PREFIX),
            Map.entry("UnitAttachment", Constants.UNIT_ATTACHMENT_NAME),
            Map.entry("UnitSupportAttachment", Constants.SUPPORT_ATTACHMENT_PREFIX),
            Map.entry("TerritoryEffectAttachment", Constants.TERRITORYEFFECT_ATTACHMENT_NAME),
            Map.entry("RelationshipTypeAttachment", Constants.RELATIONSHIPTYPE_ATTACHMENT_NAME),
            Map.entry("TechAbilityAttachment", Constants.TECH_ABILITY_ATTACHMENT_NAME));

    final String first = AttachmentsXml.newName(game, "TriggerAttachment");
    final Game withFirst =
        AttachmentsXml.withNewAttachment(game, "TriggerAttachment", first, "Russians");

    assertThat(AttachmentsXml.KINDS.keySet())
        .as("every kind has a name")
        .isEqualTo(tripleAs.keySet());
    tripleAs.forEach(
        (kind, name) ->
            assertThat(AttachmentsXml.newName(game, kind))
                .as("%s is read by a name starting %s", kind, name)
                .startsWith(name));
    assertThat(AttachmentsXml.newName(game, "UnitAttachment"))
        .as("a kind read by its exact name")
        .isEqualTo("unitAttachment");
    assertThat(AttachmentsXml.newName(withFirst, "TriggerAttachment"))
        .as("a second trigger gets a name of its own")
        .isNotEqualTo(first);
    assertThat(AttachmentsXml.has(withFirst, first, "Russians"))
        .as("the first is there, so adding it again is refused")
        .isTrue();
  }

  @DisplayName("Every kind has plain names, and the kind's usual name is told from a name of its own")
  @Test
  void namesKindsPlainly() {
    final Attachment relationship =
        AttachmentsXml.newAttachment(
            "RelationshipTypeAttachment", "relationshipTypeAttachment", "War");
    final Attachment trigger =
        AttachmentsXml.newAttachment("TriggerAttachment", "triggerAttachment_X", "Germans");

    assertThat(AttachmentsXml.KIND_NAMES.keySet())
        .as("every kind TripleA makes has plain names")
        .isEqualTo(AttachmentsXml.KINDS.keySet());
    assertThat(AttachmentsXml.kindName("TriggerAttachment").many())
        .as("the plural for the kind filter")
        .isEqualTo("Triggers");
    assertThat(AttachmentsXml.kindName("FooAttachment").one())
        .as("a kind the editor does not know stays as TripleA names it")
        .isEqualTo("FooAttachment");
    assertThat(AttachmentsXml.hasDefaultName(relationship))
        .as("relationshipTypeAttachment is the name TripleA reads it by")
        .isTrue();
    assertThat(AttachmentsXml.hasDefaultName(trigger))
        .as("a trigger with a name of its own")
        .isFalse();
  }
}
