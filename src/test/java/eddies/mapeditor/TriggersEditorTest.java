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
import javax.swing.JSplitPane;
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

  /** G40 with a German trigger last that shows the message Germans_Win before their combat move. */
  private static Game givenMessageTrigger(final Game game) {
    final List<Attachment> attachments = new ArrayList<>(AttachmentsXml.attachments(game));
    attachments.add(
        TriggerXml.withOptions(
            TriggerXml.newTrigger("triggerAttachment_Germans_Cheer", "Germans").attachment(),
            List.of(
                TriggerXml.option("notification", "Germans_Win"),
                TriggerXml.option("when", "before:germansCombatMove"))));
    return AttachmentsXml.withAttachments(game, attachments);
  }

  @Test
  @DisplayName("A message shows by the start of its text, a step by what happens in it; the key and"
      + " the step's name are for the tool tip and the search")
  void showsMessagesAndStepsInPlainWords() throws Exception {
    // given
    final Game game = givenMessageTrigger(givenG40());
    final MapTexts texts =
        MapTexts.none()
            .with(
                MapTexts.Kind.NOTIFICATIONS,
                "Germans_Win",
                "<body>Germany has taken <b>Moscow</b> and the war in the East is over</body>");
    final Trigger trigger = TriggerXml.triggers(game).getLast();

    // when
    final TriggerXml.Clauses clauses =
        TriggerXml.clauses(game, trigger, TriggerXml.names(game, texts));
    final TriggerXml.Clauses withoutText = TriggerXml.clauses(game, trigger);
    final Listing listing = TriggersEditor.listing(game, TriggerXml.names(game, texts));

    // then
    assertThat(clauses.effects())
        .as("the first 40 characters of the text, without its HTML")
        .containsExactly("show message “Germany has taken Moscow and the war in…”");
    assertThat(clauses.when())
        .as("the step as Turn order calls it, and whose it is")
        .isEqualTo("before Move to attack · Germans");
    assertThat(List.of(clauses.thenIds(), clauses.whenSteps()))
        .as("the names in the map's files, for the tool tips")
        .containsExactly("Germans_Win", "before:germansCombatMove");
    assertThat(withoutText.effects())
        .as("a message without a text keeps its key")
        .containsExactly("show message Germans_Win");
    assertThat(listing.ids().get("Germans_Cheer"))
        .as("the list's search finds the trigger by its id and its message's key")
        .isEqualTo("triggerAttachment_Germans_Cheer Germans_Win");
    assertThat(List.of(listing.does().get("Germans_Cheer"), listing.whens().get("Germans_Cheer")))
        .as("what it is grouped by: what it does, and when")
        .containsExactly("Show a message", "Before Move to attack");
  }

  @Test
  @DisplayName("An effect on a purchase list names the list as Production does and the rule by"
      + " what it sells; the ids stay for the tool tip")
  void showsPurchaseListsInPlainWords() throws Exception {
    // given: G40's Chinese buy from productionChinese_Burma_Road_Open
    final Game game = givenG40();
    final String rule = ProductionXml.rules(game).getFirst().getName();
    final String sells =
        ProductionXml.result(ProductionXml.rules(game).getFirst()).orElseThrow().getResourceOrUnit();
    final Trigger trigger =
        new Trigger(
            -1,
            TriggerXml.withOptions(
                TriggerXml.newTrigger("triggerAttachment_Chinese_Buy", "Chinese").attachment(),
                List.of(
                    TriggerXml.option(
                        "productionRule", "productionChinese_Burma_Road_Open:" + rule))),
            List.of());

    // when
    final TriggerXml.Clauses clauses = TriggerXml.clauses(game, trigger);

    // then
    assertThat(clauses.effects())
        .as("the list by its nation, the rule by its unit")
        .containsExactly("in Chinese: " + sells);
    assertThat(clauses.thenIds())
        .as("as the game's XML has it")
        .isEqualTo("productionChinese_Burma_Road_Open:" + rule);
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
    assertThat(buttons).as("the way to a first trigger").contains("Add trigger…");
    assertThat(labels).as("no reading screen").noneMatch(text -> text != null && text.startsWith("Reading"));
  }

  @Test
  @DisplayName("With Use Triggers off the list links to Game options, as no trigger fires")
  void triggersOffLinksToGameOptions() throws Exception {
    // given
    final MapProject on =
        new MapProject(Path.of("g40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
    final MapProject off =
        new MapProject(
            Path.of("g40.xml"),
            MapXml.withPropertyValue(GameRulesXmlTest.givenG40(), TriggerXml.USE_TRIGGERS, "false"),
            MapFiles.none());
    final List<Boolean> shown = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          shown.add(givenGameOptionsLinkShown(new TriggersEditor(on)));
          shown.add(givenGameOptionsLinkShown(new TriggersEditor(off)));
        });

    // then
    assertThat(shown).as("only while Use Triggers is off").containsExactly(false, true);
  }

  @Test
  @DisplayName("With no trigger chosen the list takes half the page; with one, 40 %, 320 to 560 px")
  void listGrowsWithThePage() throws Exception {
    // given
    final MapProject project =
        new MapProject(Path.of("g40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
    final TriggersEditor editor = new TriggersEditor(project);
    final JSplitPane split =
        all(editor.page())
            .filter(JSplitPane.class::isInstance)
            .map(JSplitPane.class::cast)
            .findFirst()
            .orElseThrow();
    final List<Integer> widths = new ArrayList<>();

    // when: the page of a 1280 px window, then of a 1920 px one
    for (final int width : List.of(1080, 1700)) {
      split.setSize(width, 800);
      // the resize is told on the event queue
      SwingUtilities.invokeAndWait(() -> widths.add(split.getDividerLocation()));
    }

    // then
    assertThat(widths).as("no trigger chosen: half of each page").containsExactly(540, 850);
    assertThat(
            List.of(
                TriggersEditor.listWidth(700, true),
                TriggersEditor.listWidth(1080, true),
                TriggersEditor.listWidth(1700, true)))
        .as("one chosen: two fifths, not under 320 nor over 560, the rest for what it does")
        .containsExactly(320, 432, 560);
  }

  @DisplayName("A divider the map maker dragged stays where it was put when the page grows")
  @Test
  void draggedDividerStays() throws Exception {
    // given
    final MapProject project =
        new MapProject(Path.of("g40.xml"), GameRulesXmlTest.givenG40(), MapFiles.none());
    final JSplitPane split =
        all(new TriggersEditor(project).page())
            .filter(JSplitPane.class::isInstance)
            .map(JSplitPane.class::cast)
            .findFirst()
            .orElseThrow();
    split.setSize(1080, 800);
    SwingUtilities.invokeAndWait(() -> {});
    final List<Integer> widths = new ArrayList<>();

    // when: dragged to 500, then the window grows
    split.setDividerLocation(500);
    split.setSize(1700, 800);
    SwingUtilities.invokeAndWait(() -> widths.add(split.getDividerLocation()));

    // then
    assertThat(widths).as("the map maker's choice kept").containsExactly(500);
  }

  private static boolean givenGameOptionsLinkShown(final TriggersEditor editor) {
    return all(editor.page())
        .filter(c -> c instanceof AbstractButton b && "Game options ›".equals(b.getText()))
        .anyMatch(c -> c.getParent().isVisible());
  }

  private static Stream<Component> all(final Component in) {
    return in instanceof Container container
        ? Stream.concat(
            Stream.of(in), Stream.of(container.getComponents()).flatMap(TriggersEditorTest::all))
        : Stream.of(in);
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
        .startsWith("Win with message Axis_Win_Original · if Axis");
    assertThat(TriggersEditor.conditionTitle("objectiveAttachment_Chinese_1_Burma_Road"))
        .as("any attachment prefix goes")
        .isEqualTo("Chinese 1 Burma Road");
    listing.details().forEach((key, line) -> assertThat(line)
        .as("%s fits a row of the widest list; a longer one is cut at its end", key)
        .hasSizeLessThanOrEqualTo(90)
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
