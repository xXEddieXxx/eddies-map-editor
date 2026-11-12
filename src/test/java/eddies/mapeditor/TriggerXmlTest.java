package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.xml.writer.GameXmlWriter;
import eddies.mapeditor.TriggerXml.Problems;
import eddies.mapeditor.TriggerXml.Spec;
import eddies.mapeditor.TriggerXml.Trigger;

final class TriggerXmlTest {
  private static final String G40 = "ww2global40.xml";
  private static final String MINIMAP = "minimap.xml";

  @TempDir Path folder;

  private MapProject givenMap(final String file) throws Exception {
    final Path xml = folder.resolve(file);
    try (InputStream original = TriggerXmlTest.class.getClassLoader().getResourceAsStream(file)) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  private Optional<String> whyNotLoading(final Game game) throws Exception {
    final Path xml = folder.resolve("checked.xml");
    GameXmlWriter.exportXml(game, xml);
    return MapChecks.findError(xml);
  }

  private static Spec spec(final String option) {
    return TriggerXml.spec(option).orElseThrow();
  }

  private static Option given(final String name, final String value, final String count) {
    return Option.builder().name(name).value(value).count(count).build();
  }

  /** A trigger of the nation that gives PUs when its new condition holds. */
  private static Trigger givenPayingTrigger(
      final Game game, final String nation, final Attachment condition) {
    final Trigger fresh =
        TriggerXml.newTrigger(
            TriggerXml.newName(game, TriggerXml.TRIGGER, "Pay", Set.of()), nation);
    return new Trigger(
        -1,
        TriggerXml.withOptions(
            fresh.attachment(),
            List.of(
                TriggerXml.option("conditions", condition.getName()),
                TriggerXml.option("resource", "PUs"),
                TriggerXml.option("resourceCount", "5"))),
        List.of(condition));
  }

  private static Attachment givenCondition(
      final Game game, final String nation, final String check, final String value) {
    return TriggerXml.withOptions(
        AttachmentsXml.newAttachment(
            TriggerXml.CONDITION,
            TriggerXml.newName(game, TriggerXml.CONDITION, "Home", Set.of()),
            nation),
        List.of(TriggerXml.option(check, value)));
  }

  private static int indexOf(final Game game, final String name) {
    return AttachmentsXml.attachments(game).stream().map(Attachment::getName).toList()
        .indexOf(name);
  }

  @DisplayName("Count and value join as TripleA reads them and go back as map makers write them")
  @Test
  void splitsAndJoinsCountAndValue() {
    final Option placement = given("placement", "Germany:infantry:armour", "2");
    final Option property = given("unitProperty", "canBeGivenByTerritoryTo",
        "-reset-Germans:Italians");
    final Option attachment = given("playerAttachmentName", "TriggerAttachment",
        "triggerAttachmentKamikaze");

    final List<String> placementPieces =
        TriggerXml.split(spec("placement"), TriggerXml.joined(placement));
    final List<String> propertyPieces =
        TriggerXml.split(spec("unitProperty"), TriggerXml.joined(property));
    final Option placementBack =
        TriggerXml.communityForm("placement", TriggerXml.joined(placement));
    final Option propertyBack =
        TriggerXml.communityForm("unitProperty", TriggerXml.joined(property));
    final Option attachmentBack =
        TriggerXml.communityForm("playerAttachmentName", TriggerXml.joined(attachment));

    assertThat(placementPieces)
        .as("the count, the territory and the units")
        .containsExactly("2", "Germany", "infantry:armour");
    assertThat(TriggerXml.split(spec("placement"), "Germany:infantry"))
        .as("a placement without a count")
        .containsExactly("", "Germany", "infantry");
    assertThat(propertyPieces)
        .as("the new value with its -reset- and colons, then the property")
        .containsExactly("-reset-Germans:Italians", "canBeGivenByTerritoryTo");
    assertThat(List.of(placementBack.getCount(), placementBack.getValue()))
        .as("the placement's count stays the count")
        .containsExactly("2", "Germany:infantry:armour");
    assertThat(List.of(propertyBack.getCount(), propertyBack.getValue()))
        .as("the new value in the count, the property in the value")
        .containsExactly("-reset-Germans:Italians", "canBeGivenByTerritoryTo");
    assertThat(List.of(attachmentBack.getCount(), attachmentBack.getValue()))
        .as("the attachment's name in the count, its kind in the value")
        .containsExactly("triggerAttachmentKamikaze", "TriggerAttachment");
  }

  @DisplayName("Every trigger and condition of minimap and Global 1940, read and written back, is"
      + " unchanged")
  @Test
  void writingBackChangesNothing() throws Exception {
    for (final String file : List.of(MINIMAP, G40)) {
      final Game game = givenMap(file).game();
      final List<Trigger> triggers = TriggerXml.triggers(game);

      Game written = game;
      for (final Trigger trigger : triggers) {
        written =
            TriggerXml.withTrigger(
                written,
                new Trigger(
                    trigger.index(),
                    rewritten(trigger.attachment()),
                    trigger.conditions().stream().map(TriggerXmlTest::rewritten).toList()));
      }

      assertThat(written).as("%s: the same game, nothing rewritten", file).isSameAs(game);
      if (file.equals(G40)) {
        assertThat(triggers).as("Global 1940 has triggers to read").hasSizeGreaterThan(40);
        assertThat(
                triggers.stream()
                    .flatMap(t -> Stream.concat(Stream.of(t.attachment()),
                        t.conditions().stream()))
                    .flatMap(a -> AttachmentsXml.options(a).stream())
                    .filter(o -> TriggerXml.spec(o.getName()).isPresent())
                    .filter(o -> !TriggerXml.join(spec(o.getName()),
                            TriggerXml.split(spec(o.getName()), TriggerXml.joined(o)))
                        .equals(TriggerXml.joined(o)))
                    .map(TriggerXml::joined)
                    .toList())
            .as("every option the assistant knows splits and joins to itself")
            .isEmpty();
      }
    }
  }

  /** The attachment as the assistant gives it back: every option in its joined form. */
  private static Attachment rewritten(final Attachment attachment) {
    return TriggerXml.withOptions(
        attachment,
        AttachmentsXml.options(attachment).stream()
            .map(o -> TriggerXml.option(o.getName(), TriggerXml.joined(o)))
            .toList());
  }

  @DisplayName("A new trigger with a new condition on minimap: the condition comes first, it loads")
  @Test
  void newTriggerWithNewConditionLoads() throws Exception {
    final Game game = givenMap(MINIMAP).game();
    final Attachment condition =
        givenCondition(game, "Russians", "directOwnershipTerritories", "1:red capitol");
    final Trigger trigger = givenPayingTrigger(game, "Russians", condition);

    final Game changed = TriggerXml.withTriggersOn(TriggerXml.withTrigger(game, trigger));

    assertThat(indexOf(changed, condition.getName()))
        .as("the condition right before the trigger")
        .isEqualTo(indexOf(changed, trigger.name()) - 1);
    assertThat(TriggerXml.problems(TriggerXml.withTriggersOn(game), MapTexts.none(), trigger).errors())
        .as("no problems")
        .isEmpty();
    assertThat(whyNotLoading(changed)).as("TripleA loads it").isEmpty();
  }

  @DisplayName("A new trigger on Global 1940 using one of its conditions loads")
  @Test
  void triggerReusingConditionLoads() throws Exception {
    final Game game = givenMap(G40).game();
    final Attachment existing =
        TriggerXml.condition(game, "conditionAttachment_French_1_Liberation").orElseThrow();
    final Trigger trigger = givenPayingTrigger(game, "French", existing);

    final Game changed = TriggerXml.withTrigger(game, trigger);

    assertThat(AttachmentsXml.attachments(changed))
        .as("only the trigger is added")
        .hasSize(AttachmentsXml.attachments(game).size() + 1);
    assertThat(whyNotLoading(changed)).as("TripleA loads it").isEmpty();
  }

  @DisplayName("A new condition for an early trigger of Global 1940 goes before it and loads")
  @Test
  void newConditionOfEarlyTriggerLoads() throws Exception {
    final Game game = givenMap(G40).game();
    final int index = indexOf(game, "triggerAttachment_Chinese_Artillery_Supplies");
    final Trigger early = TriggerXml.trigger(game, index);
    final Attachment condition = givenCondition(game, "Chinese", "directOwnershipTerritories",
        "1:Yunnan");
    final Trigger changedTrigger =
        new Trigger(
            index,
            TriggerXml.withOptions(
                early.attachment(),
                List.of(
                    TriggerXml.option("conditions",
                        "objectiveAttachment_Chinese_1_Burma_Road:" + condition.getName()),
                    TriggerXml.option("frontier", "productionChinese_Burma_Road_Open"))),
            Stream.concat(early.conditions().stream(), Stream.of(condition)).toList());

    final Game changed = TriggerXml.withTrigger(game, changedTrigger);

    assertThat(indexOf(changed, condition.getName()))
        .as("the new condition before the trigger")
        .isLessThan(indexOf(changed, early.name()));
    assertThat(whyNotLoading(changed)).as("TripleA loads it").isEmpty();
  }

  @DisplayName("Problems: unknown territory, chance 7:6, unknown step, duplicate condition, Use"
      + " Triggers off, firing a later trigger")
  @Test
  void findsProblems() throws Exception {
    final Game game = givenMap(MINIMAP).game();
    final Attachment condition =
        givenCondition(game, "Russians", "directOwnershipTerritories", "Atlantis");
    final Attachment duplicate =
        AttachmentsXml.newAttachment(TriggerXml.CONDITION, condition.getName(), "Italians");
    final Game withLater =
        AttachmentsXml.withNewAttachment(game, TriggerXml.TRIGGER, "triggerAttachmentLater",
            "Italians");
    final Trigger trigger =
        new Trigger(
            1,
            TriggerXml.withOptions(
                AttachmentsXml.newAttachment(TriggerXml.TRIGGER, "myTrigger", "Russians"),
                List.of(
                    TriggerXml.option("conditions", condition.getName()),
                    TriggerXml.option("chance", "7:6"),
                    TriggerXml.option("when", "after:noSuchStep"),
                    TriggerXml.option("activateTrigger",
                        "triggerAttachmentLater:1:false:false:false:false"))),
            List.of(condition, duplicate));

    final Problems problems = TriggerXml.problems(withLater, MapTexts.none(), trigger);

    assertThat(String.join("\n", problems.errors()))
        .as("each problem told")
        .contains("Atlantis is not in the game")
        .contains("not 7:6")
        .contains("noSuchStep is not in the game")
        .contains("Another condition is called " + condition.getName())
        .contains("Use Triggers is off")
        .contains("Trigger triggerAttachmentLater comes after this one");
  }

  @DisplayName("A name without triggerAttachment is a hint for a new trigger only; a later trigger"
      + " of the same name and nation replaces the earlier, a warning")
  @Test
  void namesAreWarnings() throws Exception {
    final Game game = givenMap(G40).game();
    final int index = indexOf(game, "triggerAttachment_Germans_3_Presence_In_Egypt");
    final Trigger existing = TriggerXml.trigger(game, index);
    final Game withCopy =
        AttachmentsXml.withAttachments(game,
            Stream.concat(AttachmentsXml.attachments(game).stream(),
                Stream.of(existing.attachment())).toList());
    final Trigger fresh =
        new Trigger(-1, AttachmentsXml.newAttachment(TriggerXml.TRIGGER, "Vichy_active",
            "Germans"), List.of());

    final Problems earlier = TriggerXml.problems(withCopy, MapTexts.none(), existing);
    final Problems named = TriggerXml.problems(game, MapTexts.none(), fresh);

    assertThat(earlier.errors()).as("a namesake keeps nothing from loading").isEmpty();
    assertThat(String.join("\n", earlier.warnings()))
        .as("the earlier one is ignored")
        .contains("replaces this one");
    assertThat(named.errors()).as("TripleA fires a trigger of any name").isEmpty();
    assertThat(String.join("\n", named.warnings()))
        .as("a new trigger is told the usual start")
        .contains("usually starts with triggerAttachment");
  }

  @DisplayName("Combining as a number means exactly that many, 0 none; uses count rounds, 0 is"
      + " used up")
  @Test
  void saysExactlyForNumbers() throws Exception {
    final Game game = givenMap(G40).game();
    final Trigger trigger =
        TriggerXml.trigger(game, indexOf(game, "triggerAttachment_Germans_4_Swedish_Iron_Ore"));

    final String two = TriggerXml.sentence(game, withConditionType(trigger, "2"));
    final String none = TriggerXml.sentence(game, withConditionType(trigger, "0"));

    assertThat(two).as("2: exactly two hold").contains("if exactly 2 of: ");
    assertThat(none).as("0: none holds").contains("if none of: ");
    assertThat(TriggerXml.often(withUses(trigger, "3").attachment()))
        .as("uses count rounds")
        .isEqualTo("In 3 rounds.");
    assertThat(TriggerXml.often(withUses(trigger, "0").attachment()))
        .as("0 uses: used up")
        .startsWith("Used up");
  }

  private static Trigger withUses(final Trigger trigger, final String uses) {
    return new Trigger(trigger.index(),
        TriggerXml.withOptions(trigger.attachment(),
            Stream.concat(
                    AttachmentsXml.options(trigger.attachment()).stream()
                        .map(o -> TriggerXml.option(o.getName(), TriggerXml.joined(o))),
                    Stream.of(TriggerXml.option("uses", uses)))
                .toList()),
        trigger.conditions());
  }

  private static Trigger withConditionType(final Trigger trigger, final String type) {
    final List<Option> options =
        AttachmentsXml.options(trigger.attachment()).stream()
            .map(o -> "conditionType".equals(o.getName()) ? TriggerXml.option(o.getName(), type)
                : TriggerXml.option(o.getName(), TriggerXml.joined(o)))
            .toList();
    return new Trigger(trigger.index(), TriggerXml.withOptions(trigger.attachment(), options),
        trigger.conditions());
  }

  @DisplayName("A Global 1940 trigger in plain words")
  @Test
  void saysWhatTriggerDoes() throws Exception {
    final Game game = givenMap(G40).game();
    final Trigger trigger =
        TriggerXml.trigger(game, indexOf(game, "triggerAttachment_Germans_3_Presence_In_Egypt"));

    final String sentence = TriggerXml.sentence(game, trigger);

    assertThat(sentence)
        .as("who, the condition, what it gives and when")
        .startsWith("Germans: if Germans ")
        .contains("give 3 PUs")
        .contains("at the end of the turn")
        .endsWith("Every round.");
  }

  @DisplayName("At war with and technologies without a count, or 0, read \"none of\"; territory"
      + " checks without one read as all")
  @Test
  void saysNoneOfForAtWarAndTechs() {
    final List<String> phrases = Stream.of(
            List.of("atWarPlayers", "0:Russians"), List.of("atWarPlayers", "Russians"),
            List.of("atWarPlayers", "1:Russians:Germans"), List.of("techs", "jetPower"),
            List.of("techs", "2:jetPower:radar"), List.of("directOwnershipTerritories", "France"),
            List.of("directOwnershipTerritories", "2:France:Italy"))
        .map(p -> TriggerXml.phrase(spec(p.get(0)), p.get(1)))
        .toList();

    assertThat(phrases)
        .as("TripleA needs none of them at war or researched for an empty count or 0"
            + " (RulesAttachment.checkAtWar, checkTechs)")
        .containsExactly(
            "are at war with none of Russians", "are at war with none of Russians",
            "are at war with at least 1 of Russians, Germans", "have none of jetPower",
            "have at least 2 of jetPower, radar", "own France", "own 2 of France, Italy");
    assertThat(TriggerXml.countWord(spec("directOwnershipTerritories"), ""))
        .as("a territory check without a count needs all").isEqualTo("all");
  }

  @DisplayName("A relationship change reads \"become ... (if ... now)\" in plain words, without"
      + " the bracket for any")
  @Test
  void saysRelationshipChangesPlainly() {
    final String neutral = TriggerXml.phrase(spec("relationshipChange"),
        "Japanese:Americans:anyNeutral:War");
    final String any = TriggerXml.phrase(spec("relationshipChange"),
        "Japanese:Americans:any:War");

    assertThat(neutral).as("the was in plain words")
        .isEqualTo("Japanese and Americans become War (if any neutral now)");
    assertThat(any).as("any is no condition").isEqualTo("Japanese and Americans become War");
    assertThat(Stream.of("any", "anyAllied", "anyNeutral", "anyWar", "Allied")
            .map(TriggerXml::plain).toList())
        .as("TripleA's words for kinds of relations, a type as it is")
        .containsExactly("anything", "any allied", "any neutral", "any war", "Allied");
  }

  @DisplayName("Rounds read as \"in round 1, 3 to 5, 8 and later\"")
  @Test
  void saysRounds() {
    final String rounds = TriggerXml.phrase(spec("rounds"), "1:3-5:8-+");

    assertThat(rounds).as("TripleA's round syntax in words")
        .isEqualTo("in round 1, 3 to 5, 8 and later");
  }

  @DisplayName("A resource with an amount reads as one effect; without one it warns that it gives"
      + " nothing")
  @Test
  void pairsResourceAndAmount() throws Exception {
    final Game game = givenMap(MINIMAP).game();
    final Trigger fresh = TriggerXml.newTrigger(
        TriggerXml.newName(game, TriggerXml.TRIGGER, "Pay", Set.of()), "Russians");

    final Trigger paying = givenEffects(fresh, TriggerXml.resourceOptions("10", "PUs"));
    final Trigger taking = givenEffects(fresh, TriggerXml.resourceOptions("-3", "PUs"));
    final Trigger lone = givenEffects(fresh, TriggerXml.resourceOptions("", "PUs"));

    assertThat(TriggerXml.clauses(game, paying).effects()).as("one effect")
        .containsExactly("give 10 PUs");
    assertThat(TriggerXml.clauses(game, taking).effects()).as("a negative amount takes")
        .containsExactly("take 3 PUs");
    assertThat(AttachmentsXml.options(lone.attachment())).as("no amount, no resourceCount")
        .extracting(Option::getName).containsExactly("resource");
    assertThat(TriggerXml.problems(game, MapTexts.none(), lone).warnings())
        .as("TripleA gives nothing without an amount (TriggerAttachment resourceCount 0)")
        .contains("Give PUs or a resource: no amount yet, so it gives nothing.");
    assertThat(TriggerXml.problems(game, MapTexts.none(), paying).warnings())
        .as("with an amount no such warning")
        .noneMatch(w -> w.contains("no amount yet"));
  }

  private static Trigger givenEffects(final Trigger trigger, final List<Option> options) {
    return new Trigger(trigger.index(), TriggerXml.withOptions(trigger.attachment(), options),
        trigger.conditions());
  }

  @DisplayName("The clauses of a Global 1940 trigger are filled and make up its sentence")
  @Test
  void clausesMakeTheSentence() throws Exception {
    final Game game = givenMap(G40).game();
    final Trigger trigger =
        TriggerXml.trigger(game, indexOf(game, "triggerAttachment_Germans_4_Swedish_Iron_Ore"));

    final TriggerXml.Clauses clauses = TriggerXml.clauses(game, trigger);

    assertThat(List.of(clauses.nation(), clauses.conditions(), clauses.when(), clauses.often()))
        .as("every part said").allMatch(p -> !p.isEmpty());
    assertThat(clauses.effects()).as("what it does").isNotEmpty();
    assertThat(TriggerXml.sentence(game, trigger))
        .as("the sentence is the clauses joined")
        .isEqualTo(clauses.nation() + ": " + clauses.conditions() + ", "
            + String.join(", ", clauses.effects()) + ", " + clauses.when() + ". "
            + clauses.often());
  }

  @DisplayName("The war type is a relationship type of the war kind; minimap, without types, has"
      + " none")
  @Test
  void findsWarType() throws Exception {
    final Game g40 = givenMap(G40).game();
    final Game minimap = givenMap(MINIMAP).game();

    final String war = TriggerXml.warType(g40);
    final String none = TriggerXml.warType(minimap);

    assertThat(RulesXml.relationshipTypes(g40)).as("a type of the map").contains(war);
    assertThat(RulesXml.archetype(g40, war)).as("a declaration of war needs a war type")
        .isEqualTo("war");
    assertThat(none).as("no types, no war type").isEmpty();
  }

  @DisplayName("One undo takes back the trigger, its new condition and its message")
  @Test
  void oneUndoTakesAllBack() throws Exception {
    final MapProject project = givenMap(MINIMAP);
    final Game before = project.game();
    final Attachment condition =
        givenCondition(before, "Russians", "directOwnershipTerritories", "red capitol");
    final Trigger paying = givenPayingTrigger(before, "Russians", condition);
    final Trigger trigger =
        new Trigger(-1,
            TriggerXml.withOptions(paying.attachment(),
                List.of(TriggerXml.option("notification", "RedWins"))),
            paying.conditions());

    project.edit(
        g -> TriggerXml.withTriggersOn(TriggerXml.withTrigger(g, trigger)),
        t -> t.with(MapTexts.Kind.NOTIFICATIONS, "RedWins", "Red has won"));
    final int added = AttachmentsXml.attachments(project.game()).size();
    project.undo();

    assertThat(added).as("the trigger and its condition were added")
        .isEqualTo(AttachmentsXml.attachments(before).size() + 2);
    assertThat(project.game()).as("the game as before").isSameAs(before);
    assertThat(project.texts().value(MapTexts.Kind.NOTIFICATIONS, "RedWins"))
        .as("the message gone too")
        .isEmpty();
  }

  @DisplayName("A relationship condition reads as the two nations and their relationship, not as"
      + " what the condition's nation sees")
  @Test
  void saysRelationshipConditionsPlainly() throws Exception {
    final Game game = givenMap(G40).game();

    final List<String> sentences = Stream.of(
            "conditionAttachmentAxisNotYetAtWarWithTrueNeutrals",
            "conditionAttachmentAxis_Friendly_With_TrueNeutrals",
            "conditionAttachmentRussiansNotYetAtWarWithTrueNeutralsButAlliesAre")
        .map(name -> TriggerXml.conditionSentence(TriggerXml.condition(game, name).orElseThrow()))
        .toList();

    assertThat(sentences)
        .as("anyNeutral and anyWar match each type of that kind, a type only itself"
            + " (RulesAttachment.checkRelationships)")
        .containsExactly(
            "Germans and Neutral_True are neutral",
            "Germans and Neutral_True have the relationship Friendly_Neutral",
            "Russians and Neutral_True are neutral and British and Neutral_True are at war"
                + " and Russians and Germans are at war");
    assertThat(TriggerXml.phrase(spec("relationship"), "Germans:Russians:anyWar:3"))
        .as("the rounds it must have lasted")
        .isEqualTo("Germans and Russians are at war for 3 rounds or more");
  }

  @DisplayName("A check whose places are not chosen yet says what to choose")
  @Test
  void saysWhatToChoose() {
    final String phrase = TriggerXml.phrase(spec("directOwnershipTerritories"), "");

    assertThat(phrase).as("not a sentence that stops after own")
        .isEqualTo("own … (choose territories)");
  }
}
