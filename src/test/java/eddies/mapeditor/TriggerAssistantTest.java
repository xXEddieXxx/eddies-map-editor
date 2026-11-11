package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.TriggerXml.Trigger;

final class TriggerAssistantTest {
  private static final String IRON_ORE = "triggerAttachment_Germans_4_Swedish_Iron_Ore";

  @TempDir Path folder;

  private MapProject givenG40() throws Exception {
    final Path xml = folder.resolve("ww2global40.xml");
    try (InputStream original =
        TriggerAssistantTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  /** The Swedish iron ore trigger with these options set, or added, in their joined form. */
  private static Trigger givenTrigger(final Game game, final String... nameValues) {
    final Trigger trigger =
        TriggerXml.trigger(
            game,
            AttachmentsXml.attachments(game).stream().map(a -> a.getName()).toList()
                .indexOf(IRON_ORE));
    final List<String> set = Stream.iterate(0, i -> i + 2).limit(nameValues.length / 2)
        .map(i -> nameValues[i]).toList();
    final List<Option> options = Stream.concat(
            AttachmentsXml.options(trigger.attachment()).stream()
                .filter(o -> !set.contains(o.getName()))
                .map(o -> TriggerXml.option(o.getName(), TriggerXml.joined(o))),
            Stream.iterate(0, i -> i + 2).limit(nameValues.length / 2)
                .map(i -> TriggerXml.option(nameValues[i], nameValues[i + 1])))
        .toList();
    return new Trigger(trigger.index(), TriggerXml.withOptions(trigger.attachment(), options),
        trigger.conditions());
  }

  @DisplayName("Opened and taken back unchanged, each combining and how often stays as it was")
  @ParameterizedTest(name = "conditionType {0}, uses {1}")
  @CsvSource({"AND,-1", "OR,1", "2,0", "0,3", "1-2,-1", "and,1"})
  void keepsCombiningAndUses(final String conditionType, final String uses) throws Exception {
    final MapProject project = givenG40();
    final Game game =
        TriggerXml.withTrigger(project.game(),
            givenTrigger(project.game(), "conditionType", conditionType, "uses", uses));
    project.edit(g -> game);
    final Trigger trigger = TriggerXml.trigger(game,
        AttachmentsXml.attachments(game).stream().map(a -> a.getName()).toList()
            .indexOf(IRON_ORE));

    final Trigger back = new TriggerAssistant(project, trigger).trigger();

    assertThat(TriggerXml.value(back.attachment(), "conditionType"))
        .as("the combining as written").contains(conditionType);
    assertThat(TriggerXml.value(back.attachment(), "uses")).as("the uses").contains(uses);
    assertThat(TriggerXml.withTrigger(game, back)).as("nothing changed").isSameAs(game);
  }

  @DisplayName("A condition name the game has not, eg made by a foreach, is kept")
  @ParameterizedTest(name = "{0}")
  @CsvSource({"conditions", "trigger"})
  void keepsUnknownConditionNames(final String option) throws Exception {
    final MapProject project = givenG40();
    final String names = "conditionAttachment_Germans_4_Control_Norway_And_Denmark"
        + ":conditionAttachment_Germans_@All-Players@";
    final Trigger trigger = givenTrigger(project.game(), "trigger", "", option, names);
    final Trigger kept = new Trigger(trigger.index(),
        TriggerXml.withOptions(trigger.attachment(),
            AttachmentsXml.options(trigger.attachment()).stream()
                .filter(o -> !o.getName().equals("trigger") || !TriggerXml.joined(o).isEmpty())
                .map(o -> TriggerXml.option(o.getName(), TriggerXml.joined(o)))
                .toList()),
        trigger.conditions());

    final Trigger back = new TriggerAssistant(project, kept).trigger();

    assertThat(TriggerXml.values(back.attachment(), option))
        .as("both names, the unknown one too")
        .containsExactly(names);
  }
}
