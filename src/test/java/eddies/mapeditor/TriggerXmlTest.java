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
        .contains("give PUs, amount 3")
        .contains("at the end of the turn")
        .endsWith("Every round.");
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
}
