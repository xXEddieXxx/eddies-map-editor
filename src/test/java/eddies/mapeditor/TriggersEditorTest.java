package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.Game;
import org.triplea.map.xml.writer.GameXmlWriter;
import eddies.mapeditor.TriggerXml.Trigger;
import eddies.mapeditor.TriggersEditor.Listing;

final class TriggersEditorTest {
  @TempDir Path folder;

  private Game givenG40() throws Exception {
    final Path xml = folder.resolve("ww2global40.xml");
    try (InputStream original =
        TriggersEditorTest.class.getClassLoader().getResourceAsStream("ww2global40.xml")) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml).game();
  }

  private Optional<String> whyNotLoading(final Game game) throws Exception {
    final Path xml = folder.resolve("checked.xml");
    GameXmlWriter.exportXml(game, xml);
    return MapChecks.findError(xml);
  }

  /** The game with a trigger of the first trigger's nation last that fires the first one. */
  private static Game givenFiredByAnother(final Game game, final Trigger fired) {
    final Trigger firing =
        TriggerXml.newTrigger(
            TriggerXml.newName(game, TriggerXml.TRIGGER, "_Fire", Set.of()), fired.nation());
    final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(game));
    attachments.add(
        TriggerXml.withOptions(
            firing.attachment(),
            List.of(
                TriggerXml.option("activateTrigger", fired.name() + ":1:false:false:false:false"))));
    return AttachmentsXml.withAttachments(game, attachments);
  }

  @Test
  @DisplayName("Rows show the short name with spaces and what the trigger does without its nation")
  void listsWithoutNation() throws Exception {
    // given
    final Game game = givenG40();

    // when
    final Listing listing = TriggersEditor.listing(game);

    // then
    assertThat(listing.triggers()).as("G40 has triggers").isNotEmpty();
    listing.triggers().forEach((key, trigger) -> {
      assertThat(TriggersEditor.title(trigger.name()))
          .as("the title of %s has neither the prefix nor underscores", trigger.name())
          .doesNotContain("triggerAttachment")
          .doesNotContain("_");
      assertThat(listing.details().get(key))
          .as("the detail of %s does not repeat its nation", key)
          .doesNotStartWith(trigger.nation() + ":")
          .isNotBlank();
    });
  }

  @Test
  @DisplayName("The nation filter counts each nation's triggers, in the game's order of nations")
  void countsPerNation() throws Exception {
    // given
    final Game game = givenG40();

    // when
    final Listing listing = TriggersEditor.listing(game);

    // then
    assertThat(listing.nations().values().stream().mapToInt(Integer::intValue).sum())
        .as("every listed trigger counts for its nation")
        .isEqualTo(listing.triggers().size());
    assertThat(listing.nations().get("Germans"))
        .as("Germans' count is the number of their listed triggers")
        .isEqualTo((int) listing.triggers().values().stream()
            .filter(t -> "Germans".equals(t.nation()))
            .count());
    assertThat(List.copyOf(listing.nations().keySet()))
        .as("nations come in the game's order")
        .isSubsetOf(MapXml.nations(game))
        .containsExactlyElementsOf(MapXml.nations(game).stream()
            .filter(listing.nations()::containsKey)
            .toList());
  }

  @Test
  @DisplayName("A duplicate comes last, shares the conditions and the game still loads")
  void duplicates() throws Exception {
    // given
    final Game game = givenG40();
    final Trigger original = TriggerXml.triggers(game).stream()
        .filter(t -> !t.conditionNames().isEmpty())
        .findFirst()
        .orElseThrow();

    // when
    final Game copied = TriggersEditor.withDuplicate(game, original);

    // then
    final Attachment copy = AttachmentsXml.attachments(copied).getLast();
    assertThat(copy.getName())
        .as("the copy has a new name after the original's")
        .isEqualTo("triggerAttachment_" + TriggerXml.shortName(original.name()) + "_copy");
    assertThat(copy.getAttachTo()).as("same nation").isEqualTo(original.nation());
    assertThat(TriggerXml.trigger(copied, AttachmentsXml.attachments(copied).size() - 1)
            .conditionNames())
        .as("the copy names the same conditions")
        .isEqualTo(original.conditionNames());
    assertThat(whyNotLoading(copied)).as("the game with the copy loads in TripleA").isEmpty();
  }

  @Test
  @DisplayName("A trigger another one fires is not removed, one nobody names is")
  void removesOnlyUnnamed() throws Exception {
    // given
    final Game plain = givenG40();
    final Trigger fired = TriggerXml.triggers(plain).getFirst();
    final Game game = givenFiredByAnother(plain, fired);

    // when
    final Optional<Game> refused = TriggersEditor.withoutTrigger(game, fired);
    final Trigger firing = TriggerXml.triggers(game).getLast();
    final Optional<Game> removed = TriggersEditor.withoutTrigger(game, firing);

    // then
    assertThat(refused).as("the game would not load without the fired trigger").isEmpty();
    assertThat(removed.map(g -> AttachmentsXml.attachments(g).size()))
        .as("the firing trigger, named by nobody, goes")
        .contains(AttachmentsXml.attachments(game).size() - 1);
    assertThat(whyNotLoading(removed.orElseThrow())).as("the game without it loads").isEmpty();
  }

  @Test
  @DisplayName("A game without triggers shows its empty state at once, no reading screen")
  void noTriggersShowsEmptyAtOnce() throws Exception {
    // given
    final MapProject project =
        new MapProject(Path.of("minimap.xml"), MapXmlTest.givenMinimap(), MapFiles.none());
    final List<String> buttons = new ArrayList<>();
    final List<String> labels = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final TriggersEditor editor = new TriggersEditor(project);
          shown(editor.page(), AbstractButton.class).forEach(b -> buttons.add(b.getText()));
          shown(editor.page(), JLabel.class).forEach(l -> labels.add(l.getText()));
        });

    // then
    assertThat(buttons).as("the way to a first trigger").contains("New trigger...");
    assertThat(labels).as("no reading screen").noneMatch(text -> text != null && text.startsWith("Reading"));
  }

  /** The components the page shows: those in a hidden card left out. */
  private static <T> Stream<T> shown(final Container in, final Class<T> type) {
    return Stream.of(in.getComponents())
        .filter(Component::isVisible)
        .flatMap(
            c ->
                Stream.concat(
                    Stream.of(c).filter(type::isInstance).map(type::cast),
                    c instanceof Container inner ? shown(inner, type) : Stream.empty()));
  }

  @Test
  @DisplayName("A row's second line is short: what happens, then its conditions by short name")
  void subtitleIsShortAndPlain() throws Exception {
    // given
    final Game game = givenG40();

    // when
    final Listing listing = TriggersEditor.listing(game);

    // then
    assertThat(listing.details().get("Germans_4_Swedish_Iron_Ore"))
        .as("the effect first, the conditions without their attachment prefix")
        .startsWith("Give 5 PUs · if Germans 4 Control Norway");
    assertThat(listing.details().get("AxisVictory1"))
        .as("conditions named short, not raw")
        .startsWith("Win with message Axis_Win_Original · if AxisVict");
    assertThat(TriggersEditor.conditionTitle("objectiveAttachment_Chinese_1_Burma_Road"))
        .as("any attachment prefix goes")
        .isEqualTo("Chinese 1 Burma Road");
    listing.details().forEach((key, line) -> assertThat(line)
        .as("%s fits a row; a longer one is cut at its end", key)
        .hasSizeLessThanOrEqualTo(52)
        .matches(l -> l.indexOf('…') < 0 || l.indexOf('…') == l.length() - 1,
            "cut at the end only"));
  }

  @Test
  @DisplayName("A row says how its conditions combine: none of them is not the same as if")
  void subtitleKeepsNoneOf() throws Exception {
    // given
    final Game game =
        MapXml.withOption(
            givenG40(),
            new MapXml.AttachmentKey(
                "triggerAttachment_Germans_4_Swedish_Iron_Ore",
                "games.strategy.triplea.attachments.TriggerAttachment",
                "player",
                "Germans"),
            "conditionType",
            "0");

    // when
    final Listing listing = TriggersEditor.listing(game);

    // then
    assertThat(listing.details().get("Germans_4_Swedish_Iron_Ore"))
        .as("conditionType 0 means none of them may hold")
        .startsWith("Give 5 PUs · if none of Germans 4");
  }
}
