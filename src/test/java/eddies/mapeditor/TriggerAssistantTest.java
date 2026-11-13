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
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JComboBox;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.xml.writer.GameXmlWriter;
import eddies.mapeditor.TriggerAssistant.Pattern;
import eddies.mapeditor.TriggerAssistant.Step;
import eddies.mapeditor.TriggerXml.Part;
import eddies.mapeditor.TriggerXml.Piece;
import eddies.mapeditor.TriggerXml.Shape;
import eddies.mapeditor.TriggerXml.Trigger;

final class TriggerAssistantTest {
  private static final String IRON_ORE = "triggerAttachment_Germans_4_Swedish_Iron_Ore";
  private static final String WAR_JAPANESE = "triggerAttachment_Americans_War_Japanese";

  @TempDir Path folder;

  private MapProject givenG40() throws Exception {
    return givenMap("ww2global40.xml");
  }

  private MapProject givenMap(final String file) throws Exception {
    final Path xml = folder.resolve(file);
    try (InputStream original =
        TriggerAssistantTest.class.getClassLoader().getResourceAsStream(file)) {
      Files.copy(original, xml);
    }
    return MapProject.open(xml);
  }

  private Optional<String> whyNotLoading(final Game game) throws Exception {
    final Path xml = folder.resolve("checked.xml");
    GameXmlWriter.exportXml(game, xml);
    return MapChecks.findError(xml);
  }

  private static int indexOf(final Game game, final String name) {
    return AttachmentsXml.attachments(game).stream().map(Attachment::getName).toList()
        .indexOf(name);
  }

  /** A new trigger of Russians on minimap: when they own red capitol, these effects, once. */
  private static Trigger givenDraft(final Game game, final Option... effects) {
    final Attachment condition = TriggerXml.withOptions(
        AttachmentsXml.newAttachment(TriggerXml.CONDITION,
            TriggerXml.newName(game, TriggerXml.CONDITION, "_Russians_hold", Set.of()),
            "Russians"),
        List.of(TriggerXml.option("directOwnershipTerritories", "red capitol")));
    final Trigger fresh = TriggerXml.newTrigger(
        TriggerXml.newName(game, TriggerXml.TRIGGER, "", Set.of()), "Russians");
    return new Trigger(-1,
        TriggerXml.withOptions(fresh.attachment(), Stream.concat(
            Stream.of(TriggerXml.option("conditions", condition.getName()),
                TriggerXml.option("uses", "1")),
            Stream.of(effects)).toList()),
        List.of(condition));
  }

  private static <T extends Component> List<T> found(final Component in, final Class<T> type) {
    final Stream<T> inside = in instanceof Container container
        ? Stream.of(container.getComponents()).flatMap(c -> found(c, type).stream())
        : Stream.of();
    return Stream.concat(Stream.of(in).filter(type::isInstance).map(type::cast), inside)
        .toList();
  }

  private static JSpinner spinner(final TriggerAssistant assistant, final String name) {
    return found(assistant.content(), JSpinner.class).stream()
        .filter(s -> name.equals(s.getAccessibleContext().getAccessibleName()))
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("Every Global 1940 trigger opened and taken back unchanged gives the same game:"
      + " PUs, relationship tables and kept options")
  @Test
  void everyTriggerComesBackUnchanged() throws Exception {
    final MapProject project = givenG40();
    final Game game = project.game();
    final List<Trigger> triggers = TriggerXml.triggers(game);

    final List<String> changed = triggers.stream()
        .filter(t -> TriggerXml.withTrigger(game, new TriggerAssistant(project, t).trigger())
            != game)
        .map(Trigger::name)
        .toList();

    assertThat(triggers).as("Global 1940 has triggers").hasSizeGreaterThan(40);
    assertThat(changed).as("triggers that changed by only opening them").isEmpty();
  }

  @DisplayName("A trigger with many relationship changes shows them as one table, a row each")
  @Test
  void showsRelationshipChangesAsOneTable() throws Exception {
    final MapProject project = givenG40();
    final Trigger trigger = TriggerXml.trigger(project.game(),
        indexOf(project.game(), WAR_JAPANESE));

    final TriggerAssistant assistant = new TriggerAssistant(project, trigger);

    final List<JTable> tables = found(assistant.content(), JTable.class);
    assertThat(tables).as("one table for all changes").hasSize(1);
    assertThat(tables.getFirst().getRowCount())
        .as("a row per relationship change")
        .isEqualTo(TriggerXml.values(trigger.attachment(), "relationshipChange").size())
        .isGreaterThan(8);
    assertThat(TriggerXml.withTrigger(project.game(), assistant.trigger()))
        .as("the rows written back as they were")
        .isSameAs(project.game());
  }

  @DisplayName("A new trigger giving 10 PUs, saved, loads in TripleA and one undo takes it back")
  @Test
  void newPayingTriggerLoadsAndUndoes() throws Exception {
    final MapProject project = givenMap("minimap.xml");
    final Game before = project.game();
    final TriggerAssistant assistant = new TriggerAssistant(project,
        givenDraft(before, TriggerXml.resourceOptions("10", "PUs").toArray(Option[]::new)));

    assistant.finish();
    final Game saved = project.game();
    final Optional<String> problem = whyNotLoading(saved);
    project.undo();

    assertThat(TriggerXml.triggers(saved)).as("the trigger written with its amount")
        .anyMatch(t -> TriggerXml.value(t.attachment(), "resourceCount").equals(Optional.of("10"))
            && TriggerXml.value(t.attachment(), "resource").equals(Optional.of("PUs")));
    assertThat(problem).as("TripleA loads it").isEmpty();
    assertThat(project.game()).as("one undo: the game as before").isSameAs(before);
  }

  @DisplayName("A resource without an amount stays alone: no resourceCount is added, and it loads")
  @Test
  void loneResourceStaysAlone() throws Exception {
    final MapProject project = givenMap("minimap.xml");
    final TriggerAssistant assistant = new TriggerAssistant(project,
        givenDraft(project.game(), TriggerXml.option("resource", "PUs")));

    final Trigger back = assistant.trigger();
    assistant.finish();

    assertThat(AttachmentsXml.options(back.attachment()))
        .as("the resource, no amount made up")
        .extracting(Option::getName)
        .contains("resource")
        .doesNotContain("resourceCount");
    assertThat(whyNotLoading(project.game())).as("TripleA loads it").isEmpty();
  }

  @DisplayName("New triggers are added, existing ones saved; the title has the short name")
  @Test
  void saysAddOrSave() throws Exception {
    final MapProject project = givenG40();
    final Game game = project.game();

    final TriggerAssistant fresh = new TriggerAssistant(project,
        TriggerXml.newTrigger("triggerAttachmentNew", "Germans"));
    final TriggerAssistant existing = new TriggerAssistant(project,
        TriggerXml.trigger(game, indexOf(game, IRON_ORE)));

    assertThat(fresh.verb().getText()).as("a new trigger").isEqualTo("Add trigger");
    assertThat(existing.verb().getText()).as("an existing trigger").isEqualTo("Save trigger");
    assertThat(existing.title()).as("without the prefix, with spaces")
        .isEqualTo("Trigger: Germans 4 Swedish Iron Ore");
  }

  @DisplayName("The rounds and chance numbers show only when they are chosen")
  @Test
  void hidesNumbersUntilChosen() throws Exception {
    final MapProject project = givenG40();
    final Game game = project.game();
    final Trigger often = givenTrigger(game, "uses", "3", "chance", "1:6");

    final TriggerAssistant plain = new TriggerAssistant(project,
        TriggerXml.trigger(game, indexOf(game, IRON_ORE)));
    final TriggerAssistant chosen = new TriggerAssistant(project, often);

    final String rounds = "In how many rounds the trigger may fire; 0 is used up";
    final String roll = "The roll it needs: this or less";
    assertThat(List.of(spinner(plain, rounds).isVisible(), spinner(plain, roll).isVisible()))
        .as("every round, no chance: no numbers")
        .containsExactly(false, false);
    assertThat(List.of(spinner(chosen, rounds).isVisible(), spinner(chosen, roll).isVisible()))
        .as("in 3 rounds by chance: both numbers")
        .containsExactly(true, true);
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

  /** The trigger with the places an example leaves empty filled in: a territory, a unit type. */
  private static Trigger givenFilledIn(final Game game, final Trigger trigger) {
    return new Trigger(trigger.index(), filledIn(game, trigger.attachment()),
        trigger.conditions().stream().map(c -> filledIn(game, c)).toList());
  }

  private static Attachment filledIn(final Game game, final Attachment attachment) {
    return TriggerXml.withOptions(attachment, AttachmentsXml.options(attachment).stream()
        .map(o -> {
          final String joined = TriggerXml.joined(o);
          return TriggerXml.spec(o.getName()).map(spec -> {
            final List<String> pieces = TriggerXml.split(spec, joined);
            return TriggerXml.option(o.getName(), TriggerXml.join(spec,
                IntStream.range(0, pieces.size()).mapToObj(i -> {
                  final Piece piece = spec.pieces().get(i);
                  final boolean place = List.of(Part.TERRITORY, Part.UNIT).contains(piece.part());
                  return pieces.get(i).isEmpty() && place && piece.shape() != Shape.OPTIONAL
                      ? piece.part().names(game).getFirst() : pieces.get(i);
                }).toList()));
          }).orElse(TriggerXml.option(o.getName(), joined));
        })
        .toList());
  }

  @DisplayName("Every example, its places chosen, makes a trigger TripleA loads")
  @ParameterizedTest(name = "{0}")
  @EnumSource(Pattern.class)
  void everyExampleLoads(final Pattern pattern) throws Exception {
    // Global 1940, as declaring war needs a map with relationship types
    final MapProject project = givenG40();
    final TriggerAssistant assistant = new TriggerAssistant(project,
        TriggerXml.newTrigger("triggerAttachment1", "Russians"));

    assistant.apply(pattern, "Russians");
    final Trigger made = givenFilledIn(project.game(), assistant.trigger());
    final Game saved = TriggerXml.withTriggersOn(TriggerXml.withTrigger(project.game(), made));

    assertThat(whyNotLoading(saved)).as("TripleA loads the trigger of the example").isEmpty();
    assertThat(made.name()).as("named after the example")
        .isEqualTo("triggerAttachment" + (pattern.name("Russians").isEmpty() ? "1"
            : "_" + pattern.name("Russians").replace(' ', '_')));
  }

  @DisplayName("A map without relationship types does not offer to declare war")
  @Test
  void noWarWithoutRelationshipTypes() throws Exception {
    final MapProject minimap = givenMap("minimap.xml");
    final MapProject g40 = givenG40();

    final List<String> withoutTypes = shownExamples(minimap);
    final List<String> withTypes = shownExamples(g40);

    assertThat(withoutTypes).as("minimap has no relationship types")
        .doesNotContain("Change relationships (declare war) in a round")
        .contains("Give PUs when a nation holds territories");
    assertThat(withTypes).as("Global 1940 has a war type")
        .contains("Change relationships (declare war) in a round");
  }

  private static List<String> shownExamples(final MapProject project) {
    final TriggerAssistant assistant = new TriggerAssistant(project,
        TriggerXml.newTrigger("triggerAttachment1", MapXml.nations(project.game()).getFirst()));
    return found(assistant.page(Step.START), JRadioButton.class).stream()
        .filter(Component::isVisible)
        .map(JRadioButton::getText)
        .toList();
  }

  @DisplayName("Give PUs: the example, saved, is one step, loads, and one undo takes it back")
  @Test
  void givePusExampleIsOneStep() throws Exception {
    final MapProject project = givenMap("minimap.xml");
    final Game before = project.game();
    final MapTexts texts = project.texts();
    final TriggerAssistant assistant = new TriggerAssistant(project,
        givenFilledIn(before, Pattern.GIVE_PUS.draft(before, "Russians")));

    assistant.finish();
    final Game saved = project.game();
    project.undo();

    assertThat(TriggerXml.triggers(saved)).as("gives 5 PUs")
        .anyMatch(t -> TriggerXml.value(t.attachment(), "resourceCount").equals(Optional.of("5")));
    assertThat(whyNotLoading(saved)).as("TripleA loads it").isEmpty();
    assertThat(project.game()).as("one undo: the game as before").isSameAs(before);
    assertThat(project.texts()).as("one undo: the texts as before").isEqualTo(texts);
  }

  @DisplayName("A new trigger starts on Start and walks with Next to Review; an existing one"
      + " opens on Review")
  @Test
  void walksTheSteps() throws Exception {
    final MapProject project = givenG40();
    final Game game = project.game();
    final TriggerAssistant fresh = new TriggerAssistant(project,
        TriggerXml.newTrigger("triggerAttachmentNew", "Germans"));
    final TriggerAssistant existing = new TriggerAssistant(project,
        TriggerXml.trigger(game, indexOf(game, IRON_ORE)));
    final Step first = fresh.shown();
    final boolean verbOnStart = fresh.verb().isVisible();

    final List<Step> walked = Stream.generate(() -> {
      fresh.next().doClick();
      return fresh.shown();
    }).limit(4).toList();

    assertThat(first).as("a new trigger starts on Start").isEqualTo(Step.START);
    assertThat(verbOnStart).as("Add trigger only on Review").isFalse();
    assertThat(walked).as("Next goes through the steps")
        .containsExactly(Step.IF, Step.THEN, Step.WHEN, Step.REVIEW);
    assertThat(fresh.verb().isVisible()).as("Review has Add trigger").isTrue();
    assertThat(existing.shown()).as("an existing trigger opens on Review").isEqualTo(Step.REVIEW);
    assertThat(existing.page(Step.START)).as("no Start for an existing trigger").isNull();
  }

  @DisplayName("The footer's problem of an empty trigger leads to Then")
  @Test
  void footerProblemLeadsToItsStep() throws Exception {
    final MapProject project = givenMap("minimap.xml");
    final TriggerAssistant assistant = new TriggerAssistant(project,
        TriggerXml.newTrigger("triggerAttachment1", "Russians"));

    assistant.footerProblemButton().doClick();

    assertThat(assistant.footerProblem()).as("the problem shown")
        .startsWith("The trigger does nothing");
    assertThat(assistant.shown()).as("the step to fix it in").isEqualTo(Step.THEN);
  }

  @DisplayName("A new trigger tells what to do next in a step not reached yet, in plain words;"
      + " the step reached shows the problem")
  @Test
  void newTriggerHintsTheNextStep() throws Exception {
    final MapProject project = givenMap("minimap.xml");
    final TriggerAssistant assistant = new TriggerAssistant(project,
        TriggerXml.newTrigger("triggerAttachment1", "Russians"));
    assistant.apply(Pattern.GIVE_PUS, "Russians");

    final String onStart = assistant.footerProblemButton().getText();
    final String ifSummary = found(assistant.content(), JToggleButton.class).stream()
        .filter(b -> "Step If".equals(b.getAccessibleContext().getAccessibleName()))
        .findFirst().orElseThrow().getToolTipText();
    assistant.go(Step.IF);

    assertThat(onStart).as("a hint, no problem and no condition's name")
        .isEqualTo("Next: choose the territories");
    assertThat(ifSummary).as("the empty If says what to choose")
        .contains("Russians own … (choose territories)");
    assertThat(assistant.footerProblemButton().getText()).as("on If, the problem itself")
        .startsWith(Theme.Status.PROBLEM.mark() + " ");
  }

  @DisplayName("A new condition shows in plain words with (new), its id as the tool tip, in its"
      + " card and in the footer's problem; the add buttons say what they add")
  @Test
  void newConditionInPlainWords() throws Exception {
    final MapProject project = givenMap("minimap.xml");
    final TriggerAssistant assistant = new TriggerAssistant(project,
        TriggerXml.newTrigger("triggerAttachment1", "Russians"));
    assistant.apply(Pattern.GIVE_PUS, "Russians");
    assistant.go(Step.IF);

    final String id = assistant.trigger().conditions().getFirst().getName();
    final JLabel title = found(assistant.content(), JLabel.class).stream()
        .filter(l -> l.getText().endsWith(" (new)"))
        .findFirst().orElseThrow();

    assertThat(id).as("the name the game's XML gets").startsWith("conditionAttachment");
    assertThat(title.getText()).as("the card's title, without prefix or underscores")
        .isEqualTo(TriggersEditor.conditionTitle(id) + " (new)")
        .doesNotContain("conditionAttachment").doesNotContain("_");
    assertThat(title.getToolTipText()).as("the id as its tool tip").isEqualTo(id);
    assertThat(assistant.footerProblem()).as("the problem as checked names the id").contains(id);
    assertThat(assistant.footerProblemButton().getText())
        .as("the footer says it in plain words")
        .contains(TriggersEditor.conditionTitle(id) + " (new)")
        .doesNotContain(id);
    assertThat(buttonsSaying(assistant, "+ Add effect")).as("what a trigger does is an effect")
        .hasSize(1);
  }

  @DisplayName("Each problem belongs to the step that fixes it")
  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(delimiter = '|', value = {
      "The game has no condition x.|IF",
      "Condition x belongs to no nation.|IF",
      "Combine conditions as AND, OR, a number or a range like 2-3, not y.|IF",
      "The trigger does nothing yet: add an effect.|THEN",
      "Give PUs or a resource: no amount yet, so it gives nothing.|THEN",
      "Place units: Territory is missing.|THEN",
      "Message x has no text yet.|THEN",
      "A chance is x in y, with 0 ≤ x ≤ y ≤ 120, not 7:6.|WHEN",
      "when: Step x is not in the game.|WHEN",
      "No nation is called x.|REVIEW",
      "The game option Use Triggers is off: no trigger fires.|REVIEW"})
  void problemsBelongToSteps(final String problem, final Step step) {
    assertThat(TriggerAssistant.stepOf(problem)).as("where it is fixed").isEqualTo(step);
  }

  @DisplayName("Another nation on Start, the example unchanged, makes it that nation's example")
  @Test
  void nationFollowsTheExample() throws Exception {
    final MapProject project = givenMap("minimap.xml");
    final TriggerAssistant assistant = new TriggerAssistant(project,
        TriggerXml.newTrigger("triggerAttachment1", "Russians"));
    assistant.apply(Pattern.GIVE_PUS, "Russians");
    @SuppressWarnings("unchecked")
    final JComboBox<String> nation = found(assistant.page(Step.START), JComboBox.class)
        .getFirst();
    final String other = MapXml.nations(project.game()).stream()
        .filter(n -> !n.equals("Russians")).findFirst().orElseThrow();

    nation.setSelectedItem(other);
    final Trigger made = assistant.trigger();

    assertThat(made.conditions()).as("its condition is the other nation's")
        .extracting(Attachment::getAttachTo).containsExactly(other);
    assertThat(made.name()).as("named after the other nation")
        .isEqualTo("triggerAttachment_" + other.replace(' ', '_') + "_hold_territories");
  }

  private static JToggleButton railItem(final TriggerAssistant assistant, final String label) {
    return found(assistant.content(), JToggleButton.class).stream()
        .filter(b -> ("Step " + label).equals(b.getAccessibleContext().getAccessibleName()))
        .findFirst()
        .orElseThrow();
  }

  private static List<JButton> buttonsSaying(final TriggerAssistant assistant,
      final String text) {
    return found(assistant.content(), JButton.class).stream()
        .filter(b -> text.equals(b.getText()))
        .toList();
  }

  @DisplayName("The rail marks the step shown as current, not as done")
  @Test
  void railMarksTheCurrentStep() throws Exception {
    // given
    final MapProject project = givenG40();
    final TriggerAssistant fresh = new TriggerAssistant(project,
        TriggerXml.newTrigger("triggerAttachment1", "Germans"));
    final TriggerAssistant existing = new TriggerAssistant(project,
        TriggerXml.trigger(project.game(), indexOf(project.game(), IRON_ORE)));

    // when
    final String start = railItem(fresh, "Start").getText();
    final String review = railItem(existing, "Review").getText();
    existing.go(Step.IF);

    // then
    assertThat(start).as("a new trigger opens on Start, which is not done yet")
        .contains("●").doesNotContain("✓");
    assertThat(review).as("an existing trigger opens on Review").contains("●");
    assertThat(railItem(existing, "Review").getText()).as("left, Review is done")
        .contains("✓").doesNotContain("●");
    assertThat(railItem(existing, "If").getText()).as("now If is current").contains("●");
  }

  @DisplayName("If offers one way to add a check, When adds a step with a +")
  @Test
  void oneAddCheckAndAPlusOnAddStep() throws Exception {
    // given
    final MapProject project = givenG40();
    final TriggerAssistant existing = new TriggerAssistant(project,
        TriggerXml.trigger(project.game(), indexOf(project.game(), IRON_ORE)));
    final TriggerAssistant empty = new TriggerAssistant(project,
        TriggerXml.newTrigger("triggerAttachment1", "Germans"));

    // when
    final List<JButton> outer = buttonsSaying(existing, "+ Add a check");
    final List<JButton> inner = buttonsSaying(existing, "+ Add check");

    // then
    assertThat(inner).as("each condition box adds its own checks").isNotEmpty();
    assertThat(outer).as("the button below the boxes is there")
        .singleElement().matches(b -> !b.isVisible(), "hidden while boxes add checks");
    assertThat(buttonsSaying(empty, "+ Add a check")).as("without conditions it adds the first")
        .singleElement().matches(JButton::isVisible, "visible");
    assertThat(buttonsSaying(existing, "+ Add step")).as("like every other add button")
        .hasSize(1);
  }

  @DisplayName("Typing waits for a pause before checking, Save checks what was typed at once")
  @Test
  void typingChecksAfterAPause() throws Exception {
    // given
    final MapProject project = givenG40();
    final Game before = project.game();
    final TriggerAssistant assistant = new TriggerAssistant(project,
        TriggerXml.trigger(before, indexOf(before, IRON_ORE)));
    @SuppressWarnings("unchecked")
    final JComboBox<String> resource = found(assistant.content(), JComboBox.class).stream()
        .filter(c -> c.isEditable() && "PUs".equals(c.getSelectedItem()))
        .findFirst()
        .orElseThrow();
    final boolean savable = assistant.verb().isEnabled();

    // when
    ((JTextComponent) resource.getEditor().getEditorComponent()).setText("Gold");
    final boolean whileTyping = assistant.verb().isEnabled();
    assistant.finish();

    // then
    assertThat(savable).as("the trigger as the game has it can be saved").isTrue();
    assertThat(whileTyping).as("no check over the whole game on each key").isTrue();
    assertThat(assistant.verb().isEnabled()).as("Save checked what was typed").isFalse();
    assertThat(project.game()).as("a resource the game lacks is not saved").isSameAs(before);
  }

  @Test
  @DisplayName("The If page's fields, used by another tool, give a condition back unchanged")
  void checksOfAnotherToolComeBackUnchanged() throws Exception {
    // given every condition of G40, objectives too
    final MapProject project = givenG40();
    final List<Attachment> conditions = TriggerXml.conditions(project.game());
    final List<String> told = new ArrayList<>();

    // when
    final List<Attachment> back =
        conditions.stream()
            .map(c -> TriggerAssistant.checksOf(project, c, () -> told.add(c.getName())))
            .map(TriggerAssistant.Checks::attachment)
            .toList();

    // then
    assertThat(conditions).as("G40 has conditions").hasSizeGreaterThan(20);
    assertThat(IntStream.range(0, back.size()).filter(i -> back.get(i) != conditions.get(i)))
        .as("conditions the fields would change without the map maker doing anything")
        .isEmpty();
    assertThat(told).as("filling the fields is no change to tell").isEmpty();
  }

  @Test
  @DisplayName("A check added in another tool's fields is told, and does not read while empty")
  void addedCheckIsTold() throws Exception {
    // given
    final MapProject project = givenG40();
    final Attachment trade =
        TriggerXml.condition(project.game(), "objectiveAttachment_Germans_1_Trade_with_Russia")
            .orElseThrow();
    final List<String> told = new ArrayList<>();
    final TriggerAssistant.Checks checks =
        TriggerAssistant.checksOf(project, trade, () -> told.add("changed"));

    // when
    found(checks.rows(), JButton.class).stream()
        .filter(b -> "+ Add check".equals(b.getText()))
        .findFirst()
        .orElseThrow()
        .doClick();

    // then
    assertThat(told).as("the tool is told at once").containsExactly("changed");
    assertThat(AttachmentsXml.options(checks.attachment()))
        .as("the new check after the options the objective had")
        .hasSize(AttachmentsXml.options(trade).size() + 1);
    assertThat(TriggerXml.checkProblem(project.game(), checks.attachment()))
        .as("not filled in yet: the game would not read it")
        .isPresent();
    assertThat(TriggerXml.checkProblem(project.game(), trade))
        .as("the map's own check reads")
        .isEmpty();
  }
}
