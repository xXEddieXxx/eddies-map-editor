package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import eddies.mapeditor.MapTexts.Kind;
import eddies.mapeditor.ObjectiveXml.Objective;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.experimental.ExtensionMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.xml.writer.GameXmlWriter;

@ExtensionMethod(Builders.class)
final class ObjectiveXmlTest {
  private static final String TRADE = "objectiveAttachment_Germans_1_Trade_with_Russia";
  private static final String BURMA_ROAD = "objectiveAttachment_Chinese_1_Burma_Road";
  private static final String ISLANDS = "objectiveAttachment_ANZAC_1_Control_Strategic_Islands";

  @TempDir Path folder;

  private static Objective givenObjective(final Game game, final String name) {
    return ObjectiveXml.objectives(game).stream()
        .filter(o -> o.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  /** G40 with its objective at this place replaced. */
  private static Game givenChanged(
      final Game game, final Objective objective, final Attachment changed) {
    return AttachmentsXml.withAttachment(game, objective.index(), changed);
  }

  private Optional<String> whyNotLoading(final Game game) throws Exception {
    final Path xml = folder.resolve("checked.xml");
    GameXmlWriter.exportXml(game, xml);
    return MapChecks.findError(xml);
  }

  @Test
  @DisplayName("G40's nine national objectives are read with nation, PUs and plain words")
  void readsObjectives() {
    // given
    final Game game = GameRulesXmlTest.givenG40();

    // when
    final List<Objective> objectives = ObjectiveXml.objectives(game);

    // then
    assertThat(objectives).as("every attachment with an objectiveValue").hasSize(9);
    final Objective trade = givenObjective(game, TRADE);
    assertThat(trade.nation()).as("whose it is").isEqualTo("Germans");
    assertThat(ObjectiveXml.reward(trade.attachment())).as("what it pays").isEqualTo("+5 PUs");
    assertThat(ObjectiveXml.summary(game, trade.attachment(), Map.of()))
        .as("its check in plain words")
        .isEqualTo("Germans are at war with none of Russians");
    assertThat(
            ObjectiveXml.reward(
                givenObjective(game, "objectiveAttachment_Germans_2_Control_Novgorod_Or_"
                        + "Volgograd_Or_Russia")
                    .attachment()))
        .as("a count of each pays per territory")
        .isEqualTo("+5 PUs each");
    assertThat(
            ObjectiveXml.summary(
                game,
                givenObjective(game, BURMA_ROAD).attachment(),
                TriggerXml.conditionsByName(game)))
        .as("an objective that only names a condition says what that one checks")
        .startsWith("not: Japanese own with allies 1 of India");
  }

  @Test
  @DisplayName("Other PUs change only that option: the rest, its counts and order stay")
  void changesPusOnly() throws Exception {
    // given
    final Game game = GameRulesXmlTest.givenG40();
    final Objective islands = givenObjective(game, ISLANDS);
    final List<Option> before = AttachmentsXml.options(islands.attachment());

    // when
    final Attachment changed = ObjectiveXml.with(islands.attachment(), ObjectiveXml.VALUE, "7");
    final Game after = givenChanged(game, islands, changed);

    // then
    final List<Option> options = AttachmentsXml.options(changed);
    assertThat(ObjectiveXml.value(changed)).as("the new PUs").isEqualTo(7);
    assertThat(options.subList(1, options.size()))
        .as("the check with its count and the nations it counts for are the same objects")
        .containsExactlyElementsOf(before.subList(1, before.size()));
    assertThat(AttachmentsXml.attachments(after))
        .as("no attachment more or less")
        .hasSameSizeAs(AttachmentsXml.attachments(game));
    assertThat(whyNotLoading(after)).as("the game still loads in TripleA").isEmpty();
  }

  @Test
  @DisplayName("Uses are set and taken out again; unchanged, the attachment is the same object")
  void setsAndClearsUses() {
    // given
    final Attachment trade = givenObjective(GameRulesXmlTest.givenG40(), TRADE).attachment();

    // when
    final Attachment once = ObjectiveXml.with(trade, ObjectiveXml.USES, "1");
    final Attachment always = ObjectiveXml.without(once, ObjectiveXml.USES);

    // then
    assertThat(ObjectiveXml.uses(trade)).as("G40 pays every round").isEmpty();
    assertThat(ObjectiveXml.uses(once)).as("pays once").contains(1);
    assertThat(AttachmentsXml.options(always))
        .as("the options as they were")
        .containsExactlyElementsOf(AttachmentsXml.options(trade));
    assertThat(ObjectiveXml.with(trade, ObjectiveXml.VALUE, "5"))
        .as("no change, no new attachment")
        .isSameAs(trade);
  }

  @Test
  @DisplayName("Options written the old way are read: ObjectiveValue, and turns for rounds")
  void readsOldSpellings() {
    // given
    final Attachment old =
        AttachmentsXml.newAttachment(TriggerXml.CONDITION, "objectiveAttatchmentOld", "Germans")
            .toBuilder()
            .options(
                List.of(
                    Option.builder().name("ObjectiveValue").value("4").build(),
                    Option.builder().name("turns").value("1-3").build()))
            .build();

    // when
    final Attachment changed = ObjectiveXml.with(old, ObjectiveXml.VALUE, "6");

    // then
    assertThat(ObjectiveXml.isObjective(old)).as("an objective by its ObjectiveValue").isTrue();
    assertThat(ObjectiveXml.summary(GameRulesXmlTest.givenG40(), old, Map.of()))
        .as("turns is the old name of rounds")
        .isEqualTo("Germans in round 1 to 3");
    assertThat(AttachmentsXml.options(changed))
        .as("the map's spelling of the name stays")
        .extracting(Option::getName, Option::getValue)
        .containsExactly(
            tuple("ObjectiveValue", "6"),
            tuple("turns", "1-3"));
    assertThat(
            ObjectiveXml.whyNotPaid(
                GameRulesXmlTest.givenG40(), new Objective(0, old)))
        .as("the old spelling of the name's start is paid as well")
        .isEmpty();
  }

  @Test
  @DisplayName("The checks of the shared fields replace the checks only: PUs and uses stay")
  void takesChecksOnly() {
    // given an objective changed here since the fields were filled
    final Attachment filled = givenObjective(GameRulesXmlTest.givenG40(), TRADE).attachment();
    final Attachment now =
        ObjectiveXml.with(ObjectiveXml.with(filled, ObjectiveXml.VALUE, "9"), "uses", "2");
    final Attachment fields =
        TriggerXml.withOptions(
            filled,
            List.of(
                TriggerXml.option(ObjectiveXml.VALUE, "5"),
                TriggerXml.option("atWarPlayers", "1:Russians:British")));

    // when
    final Attachment changed = ObjectiveXml.withChecksOf(now, fields);

    // then
    assertThat(ObjectiveXml.value(changed)).as("the PUs set meanwhile").isEqualTo(9);
    assertThat(ObjectiveXml.uses(changed)).as("the uses set meanwhile").contains(2);
    assertThat(TriggerXml.value(changed, "atWarPlayers"))
        .as("the check as the fields have it")
        .contains("1:Russians:British");
    assertThat(AttachmentsXml.options(changed).get(1).getCount())
        .as("written as map makers do, the count beside the value")
        .isEqualTo("1");
  }

  @Test
  @DisplayName("A new objective gets the name the game pays by, comes last and loads")
  void addsObjective() throws Exception {
    // given
    final Game game = GameRulesXmlTest.givenG40();

    // when
    final String name = ObjectiveXml.newName(game, "Germans");
    final Game added = ObjectiveXml.withNew(game, name, "Germans", 4);

    // then
    assertThat(name).as("the start TripleA pays by").isEqualTo("objectiveAttachment_Germans_1");
    final Attachment last = AttachmentsXml.attachments(added).getLast();
    assertThat(last.getName()).as("after all it could name").isEqualTo(name);
    assertThat(last.getJavaClass())
        .as("a condition of TripleA")
        .isEqualTo("games.strategy.triplea.attachments.RulesAttachment");
    assertThat(ObjectiveXml.objectives(added)).as("one more objective").hasSize(10);
    assertThat(ObjectiveXml.newName(added, "Germans"))
        .as("the next free number")
        .isEqualTo("objectiveAttachment_Germans_2");
    assertThat(whyNotLoading(added)).as("the game still loads in TripleA").isEmpty();
  }

  @Test
  @DisplayName("A copy has the same options under a free name and loads")
  void duplicatesObjective() throws Exception {
    // given
    final Game game = GameRulesXmlTest.givenG40();
    final Objective islands = givenObjective(game, ISLANDS);

    // when
    final String name = ObjectiveXml.copyName(game, islands);
    final Game copied = ObjectiveXml.withDuplicate(game, islands, name);

    // then
    final Objective copy = givenObjective(copied, ISLANDS + "_copy");
    assertThat(copy.nation()).as("of the same nation").isEqualTo("ANZAC");
    assertThat(AttachmentsXml.options(copy.attachment()))
        .as("with the same options")
        .containsExactlyElementsOf(AttachmentsXml.options(islands.attachment()));
    assertThat(ObjectiveXml.copyName(copied, islands))
        .as("a second copy gets a number")
        .isEqualTo(ISLANDS + "_copy2");
    assertThat(whyNotLoading(copied)).as("the game still loads in TripleA").isEmpty();
  }

  @Test
  @DisplayName("An objective a trigger names is not removed; one nothing names is")
  void removesUnlessNamed() throws Exception {
    // given
    final Game game = GameRulesXmlTest.givenG40();

    // when
    final Optional<Game> withoutNamed =
        ObjectiveXml.without(game, givenObjective(game, BURMA_ROAD));
    final Optional<Game> withoutTrade = ObjectiveXml.without(game, givenObjective(game, TRADE));

    // then
    assertThat(withoutNamed)
        .as("triggerAttachment_Chinese_Artillery_Supplies names the Burma Road objective")
        .isEmpty();
    assertThat(withoutTrade).as("nothing names the trade objective").isPresent();
    assertThat(ObjectiveXml.objectives(withoutTrade.get()))
        .as("one objective fewer")
        .hasSize(8)
        .noneMatch(o -> o.name().equals(TRADE));
    assertThat(whyNotLoading(withoutTrade.get())).as("the game still loads in TripleA").isEmpty();
  }

  @Test
  @DisplayName("Without the game option National Objectives no objective pays; it is turned on")
  void tellsGameOptionOff() {
    // given
    final Game on = GameRulesXmlTest.givenG40();
    final Game off = MapXml.withPropertyValue(on, ObjectiveXml.GAME_OPTION, "false");

    // when
    final Game onAgain = ObjectiveXml.withOn(off);

    // then
    assertThat(ObjectiveXml.isOn(on)).as("G40 plays with national objectives").isTrue();
    assertThat(ObjectiveXml.isOn(off)).as("turned off").isFalse();
    assertThat(ObjectiveXml.isOn(onAgain)).as("turned on again").isTrue();
    assertThat(ObjectiveXml.withOn(on)).as("on already: no change").isSameAs(on);
  }

  @Test
  @DisplayName("An objective named otherwise than objectiveAttachment is told as never paid")
  void tellsWrongName() {
    // given
    final Game game = GameRulesXmlTest.givenG40();
    final Game added = ObjectiveXml.withNew(game, "conditionAttachmentPays", "Germans", 3);

    // when
    final List<String> reasons =
        ObjectiveXml.whyNotPaid(added, givenObjective(added, "conditionAttachmentPays"));

    // then
    assertThat(reasons)
        .as("TripleA pays only attachments whose name starts with objectiveAttachment")
        .singleElement()
        .asString()
        .contains("objectiveAttachment");
    assertThat(ObjectiveXml.whyNotPaid(game, givenObjective(game, TRADE)))
        .as("G40's own are paid")
        .isEmpty();
  }

  @Test
  @DisplayName("An objective's text gets the key the game reads and a place in its nation's group")
  void writesTextWithGroup() {
    // given
    final Game game = GameRulesXmlTest.givenG40();
    final String prefix = "World_War_II_Global_1940_Original.";
    final MapTexts texts =
        new MapTexts(
            Map.of(Kind.OBJECTIVES, prefix + "TABLEGROUP.03;Japanese=objectiveAttachment_J\n"));

    // when
    final MapTexts first = texts.withObjectiveText(game, "Germans", TRADE, "<b>5 PUs</b> trade");
    final MapTexts second = first.withObjectiveText(game, "Germans", "objectiveAttachment_G2", "x");
    final MapTexts japanese =
        texts.withObjectiveText(game, "Japanese", "objectiveAttachment_J2", "y");
    final MapTexts removed = second.withoutObjectiveText(game, "Germans", TRADE);

    // then
    assertThat(first.value(Kind.OBJECTIVES, prefix + "Germans;" + TRADE))
        .as("<game name with _>.<nation>;<attachment name>, as TripleA's ObjectivePanel reads")
        .contains("<b>5 PUs</b> trade");
    assertThat(first.value(Kind.OBJECTIVES, prefix + "TABLEGROUP.04;Germans"))
        .as("a new group of the nation after the others, as the game shows only grouped ones")
        .contains(TRADE);
    assertThat(second.value(Kind.OBJECTIVES, prefix + "TABLEGROUP.04;Germans"))
        .as("the nation's group lists the next one too")
        .contains(TRADE + ";objectiveAttachment_G2");
    assertThat(japanese.value(Kind.OBJECTIVES, prefix + "TABLEGROUP.03;Japanese"))
        .as("a group the file has is added to")
        .contains("objectiveAttachment_J;objectiveAttachment_J2");
    assertThat(removed.value(Kind.OBJECTIVES, prefix + "Germans;" + TRADE))
        .as("the removed one's text is gone")
        .isEmpty();
    assertThat(removed.value(Kind.OBJECTIVES, prefix + "TABLEGROUP.04;Germans"))
        .as("and its group no longer lists it")
        .contains("objectiveAttachment_G2");
    assertThat(
            removed
                .withoutObjectiveText(game, "Germans", "objectiveAttachment_G2")
                .text(Kind.OBJECTIVES))
        .as("a group that lists nothing any more goes, the other nation's stays")
        .doesNotContain("Germans")
        .contains("TABLEGROUP.03;Japanese");
  }

  @Test
  @DisplayName("An unfinished check is held back alone: the check it changes stays as it was")
  void holdsBackOnlyTheUnfinishedCheck() {
    // given the trade objective with its check emptied, a new one unfinished and "invert" set
    final Game game = GameRulesXmlTest.givenG40();
    final Attachment trade = givenObjective(game, TRADE).attachment();
    final Attachment fields =
        trade.toBuilder()
            .options(
                List.of(
                    TriggerXml.option(ObjectiveXml.VALUE, "5"),
                    TriggerXml.option("atWarPlayers", "0:Martians"),
                    TriggerXml.option("directOwnershipTerritories", ""),
                    TriggerXml.option("invert", "true")))
            .build();

    // when
    final List<Option> held = ObjectiveXml.unreadableChecks(game, trade, fields);
    final Attachment written =
        ObjectiveXml.withChecksOf(trade, ObjectiveXml.withReadableChecks(game, trade, fields));

    // then
    assertThat(held).as("the two checks the game could not read").hasSize(2);
    assertThat(TriggerXml.value(written, "atWarPlayers"))
        .as("the check being changed is still as the map has it")
        .contains("0:Russians");
    assertThat(TriggerXml.value(written, "directOwnershipTerritories"))
        .as("the new check without territories is not written")
        .isEmpty();
    assertThat(TriggerXml.value(written, "invert"))
        .as("what reads is written at once")
        .contains("true");
    assertThat(TriggerXml.checkProblem(game, written)).as("the game reads all of it").isEmpty();
    assertThat(ObjectiveXml.unreadableChecks(game, fields, fields))
        .as("a problem the map has already holds nothing back")
        .isEmpty();
  }

  @Test
  @DisplayName("Conditions are named under the option the objective uses, and taken out again")
  void namesConditions() {
    // given
    final Attachment trade = givenObjective(GameRulesXmlTest.givenG40(), TRADE).attachment();
    final Attachment old = ObjectiveXml.with(trade, TriggerXml.OLD_CONDITIONS, "conditionA");

    // when
    final Attachment named = ObjectiveXml.withConditions(trade, List.of("conditionA", "b"));
    final Attachment renamed = ObjectiveXml.withConditions(old, List.of("conditionB"));

    // then
    assertThat(TriggerXml.value(named, ObjectiveXml.CONDITIONS))
        .as("joined as the game reads them")
        .contains("conditionA:b");
    assertThat(ObjectiveXml.value(named)).as("its PUs stay").isEqualTo(5);
    assertThat(TriggerXml.value(renamed, TriggerXml.OLD_CONDITIONS))
        .as("the old option name of the map stays")
        .contains("conditionB");
    assertThat(AttachmentsXml.options(ObjectiveXml.withConditions(named, List.of())))
        .as("none: the options as before")
        .containsExactlyElementsOf(AttachmentsXml.options(trade));
  }
}
