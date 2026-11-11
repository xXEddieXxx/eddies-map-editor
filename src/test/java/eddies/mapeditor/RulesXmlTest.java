package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.triplea.map.xml.writer.GameXmlWriter;

final class RulesXmlTest {
  private static final String TRUCE = "Truce";
  private final Game minimap = MapXmlTest.givenMinimap();

  private static List<String> stepNames(final Game game) {
    return RulesXml.steps(game).stream().map(Step::getName).toList();
  }

  static GameData givenLoaded(final Game game, final Path folder) {
    final Path xml = folder.resolve("changed.xml");
    folder.toFile().mkdirs();
    GameXmlWriter.exportXml(game, xml);
    assertThat(MapChecks.findError(xml)).as("the engine loads the changed game").isEmpty();
    return GameParser.parse(xml, false).orElseThrow();
  }

  @DisplayName("Steps are added, moved, changed and removed, each a new game")
  @Test
  void changesTheTurnOrder() {
    final Game added = RulesXml.withStepAdded(minimap, 6, "purchase", "Russians");
    final Game moved = RulesXml.withStepMoved(added, 6, 5);
    final Step step = RulesXml.steps(moved).get(5);
    final Game changed =
        RulesXml.withStep(
            moved,
            5,
            RulesXml.withStepProperty(
                Builders.toBuilder(step).display("Second purchase").maxRunCount(1).build(),
                GameStep.PropertyKeys.SKIP_POSTING,
                "true"));
    final Game removed = RulesXml.withoutStep(changed, 0);

    assertThat(stepNames(added).get(6))
        .as("a name no other step has, as russianPurchase is taken")
        .isEqualTo("russiansPurchase");
    assertThat(stepNames(moved).subList(4, 7))
        .as("moved one up")
        .containsExactly("italianBidPlace", "russiansPurchase", "russianPurchase");
    assertThat(RulesXml.stepProperties(RulesXml.steps(changed).get(5)))
        .as("the property the engine reads")
        .singleElement()
        .satisfies(p -> assertThat(p.getValue()).as("its value").isEqualTo("true"));
    assertThat(stepNames(removed)).as("the first step gone").doesNotContain("gameInitDelegate");
    assertThat(stepNames(minimap))
        .as("the game it started from stays, for undo")
        .doesNotContain("russiansPurchase")
        .hasSize(18);
  }

  @DisplayName("A changed turn order loads in the engine with its steps, names and properties")
  @Test
  void changedTurnOrderLoads(@TempDir final Path folder) {
    final Game added = RulesXml.withStepAdded(minimap, 6, "purchase", "Russians");
    final Step step = RulesXml.steps(added).get(6);
    final Game changed =
        RulesXml.withStep(
            added,
            6,
            RulesXml.withStepProperty(
                Builders.toBuilder(step).display("Second purchase").maxRunCount(2).build(),
                GameStep.PropertyKeys.TURN_SUMMARY_PLAYERS,
                "Russians:Italians"));

    final GameData data = givenLoaded(RulesXml.withoutStep(changed, 0), folder);

    final GameStep loaded = data.getSequence().getStep(5);
    assertThat(loaded.getName()).as("the added step, one up as the first is gone")
        .isEqualTo("russiansPurchase");
    assertThat(loaded.getDisplayName()).as("shown as typed").isEqualTo("Second purchase");
    assertThat(loaded.getPlayerId().getName()).as("its nation").isEqualTo("Russians");
    assertThat(loaded.getProperties().getProperty(GameStep.PropertyKeys.TURN_SUMMARY_PLAYERS))
        .as("its property")
        .isEqualTo("Russians:Italians");
    assertThat(data.getSequence().size()).as("18 steps, one added, one removed").isEqualTo(18);
  }

  /** The minimap with a neutral type "Truce" the Russians and Italians start in. */
  private Game givenTruce() {
    return RulesXml.withRelationship(
        RulesXml.withRelationshipType(minimap, TRUCE, Constants.RELATIONSHIP_ARCHETYPE_NEUTRAL),
        "Italians",
        "Russians",
        TRUCE);
  }

  @DisplayName("Relationship types and how two nations start change, either way round")
  @Test
  void changesPolitics() {
    final Game truce = givenTruce();
    final Game allied =
        RulesXml.withArchetype(truce, TRUCE, Constants.RELATIONSHIP_ARCHETYPE_ALLIED);

    assertThat(RulesXml.relationship(truce, "Russians", "Italians"))
        .as("a relationship holds both ways")
        .contains(TRUCE);
    assertThat(RulesXml.archetype(allied, TRUCE)).as("its archetype").isEqualTo("allied");
    assertThat(
            RulesXml.relationship(
                RulesXml.withoutRelationship(truce, "Russians", "Italians"),
                "Italians",
                "Russians"))
        .as("none: by alliance again")
        .isEmpty();
    final Game removed = RulesXml.withoutRelationshipType(truce, TRUCE);
    assertThat(RulesXml.relationshipTypes(removed)).as("the type gone").doesNotContain(TRUCE);
    assertThat(RulesXml.relationship(removed, "Russians", "Italians"))
        .as("with its relationships")
        .isEmpty();
    assertThat(RulesXml.isNamedInAttachments(removed, TRUCE))
        .as("and its attachment")
        .isFalse();
    assertThat(RulesXml.relationshipTypes(minimap))
        .as("the game it started from stays, for undo")
        .isEmpty();
  }

  @DisplayName("Changed politics load in the engine with the nations' relationship")
  @Test
  void changedPoliticsLoad(@TempDir final Path folder) {
    final GameData data = givenLoaded(givenTruce(), folder);

    final RelationshipType loaded =
        data.getRelationshipTracker()
            .getRelationshipType(
                data.getPlayerList().getPlayerId("Russians"),
                data.getPlayerList().getPlayerId("Italians"));
    assertThat(loaded.getName()).as("the new type").isEqualTo(TRUCE);
    assertThat(loaded.getRelationshipTypeAttachment().isNeutral()).as("neutral").isTrue();
  }

  /**
   * The minimap with the Italians in the turn summaries of russianPurchase, beside the Russians,
   * and of russianCombatMove.
   */
  private Game givenItaliansInSummaries() {
    final String summary = GameStep.PropertyKeys.TURN_SUMMARY_PLAYERS;
    final List<Step> steps = RulesXml.steps(minimap);
    final Game one =
        RulesXml.withStep(
            minimap, 5, RulesXml.withStepProperty(steps.get(5), summary, "Russians:Italians"));
    return RulesXml.withStep(
        one, 6, RulesXml.withStepProperty(steps.get(6), summary, "Italians"));
  }

  private static Step step(final Game game, final String name) {
    return RulesXml.steps(game).stream()
        .filter(s -> name.equals(s.getName()))
        .findFirst()
        .orElseThrow();
  }

  @DisplayName("A removed nation leaves the steps' lists of nations, and the game still loads")
  @Test
  void removedNationLeavesStepLists(@TempDir final Path folder) {
    final Game without = MapXml.withoutNation(givenItaliansInSummaries(), "Italians");

    assertThat(RulesXml.stepProperties(step(without, "russianPurchase")))
        .as("the Russians stay in the list")
        .singleElement()
        .satisfies(p -> assertThat(p.getValue()).as("without the Italians").isEqualTo("Russians"));
    assertThat(RulesXml.stepProperties(step(without, "russianCombatMove")))
        .as("a list left empty goes")
        .isEmpty();
    assertThat(RulesXml.whereNamed(without, "Italians")).as("named nowhere").isEmpty();
    givenLoaded(without, folder);
  }

  @DisplayName("A nation an attachment still names is not removed, and where is told")
  @Test
  void nationNamedInAttachmentsStays() {
    final Game named =
        MapXml.withOption(
            minimap,
            new MapXml.AttachmentKey(
                Constants.TERRITORY_ATTACHMENT_NAME,
                TerritoryAttachment.class.getName(),
                "territory",
                "red north 1"),
            "originalOwner",
            "Italians");
    final MapProject project = new MapProject(Path.of("minimap.xml"), named, MapFiles.none());

    final List<String> where = project.removeNation("Italians");

    assertThat(where)
        .as("the option that names it")
        .containsExactly("territoryAttachment of red north 1: originalOwner");
    assertThat(MapXml.nations(project.game())).as("not removed").contains("Italians");
    assertThat(project.canUndo()).as("no step made").isFalse();
  }

  @DisplayName("A nation's flags of the player list change and load in the engine")
  @Test
  void playerFlagsLoad(@TempDir final Path folder) {
    final Game changed =
        RulesXml.withPlayer(
            minimap,
            "Italians",
            b -> b.optional(true).canBeDisabled(true).defaultType("AI").isHidden(true));

    final GamePlayer loaded = givenLoaded(changed, folder).getPlayerList().getPlayerId("Italians");

    assertThat(loaded.getOptional()).as("optional").isTrue();
    assertThat(loaded.getCanBeDisabled()).as("can be disabled").isTrue();
    assertThat(loaded.isDefaultTypeAi()).as("played by the computer").isTrue();
    assertThat(loaded.isHidden()).as("hidden").isTrue();
    assertThat(RulesXml.player(minimap, "Italians").getIsHidden())
        .as("the game it started from stays, for undo")
        .isNull();
    assertThat(MapXml.nations(changed)).as("the nation keeps its place").startsWith("Russians");
  }

  @DisplayName("A nation's rule is set in a new playerAttachment, loads, and is taken out again")
  @Test
  void playerOptionIsSetAndCleared(@TempDir final Path folder) {
    final Game set = RulesXml.withPlayerOption(minimap, "Russians", "retainCapitalNumber", "2");
    final Game cleared = RulesXml.withoutPlayerOption(set, "Russians", "retainCapitalNumber");

    final GamePlayer loaded = givenLoaded(set, folder).getPlayerList().getPlayerId("Russians");

    assertThat(PlayerAttachment.get(loaded).getRetainCapitalNumber())
        .as("the engine reads the rule")
        .isEqualTo(2);
    assertThat(RulesXml.playerOption(cleared, "Russians", "retainCapitalNumber"))
        .as("taken out, so the default applies")
        .isEmpty();
    assertThat(RulesXml.withPlayerOption(set, "Russians", "retainCapitalNumber", "2"))
        .as("setting the value it has makes no step")
        .isSameAs(set);
  }

  @DisplayName("A relationship type's rule beyond its kind loads; As its kind takes it out")
  @Test
  void relationshipOptionsLoad(@TempDir final Path folder) {
    final Game set =
        RulesXml.withRelationshipOption(givenTruce(), TRUCE, "canMoveThroughCanals", "false");
    final Game back =
        RulesXml.withRelationshipOption(
            set, TRUCE, "canMoveThroughCanals", Constants.RELATIONSHIP_PROPERTY_DEFAULT);

    final RelationshipType loaded =
        givenLoaded(set, folder).getRelationshipTypeList().getRelationshipType(TRUCE);

    assertThat(loaded.getRelationshipTypeAttachment().canMoveThroughCanals())
        .as("a neutral type that may not use canals")
        .isFalse();
    assertThat(MapXml.option(back, "relationshipTypeAttachment", TRUCE, "canMoveThroughCanals"))
        .as("as its kind again: the option is gone")
        .isEmpty();
    assertThat(RulesXml.archetype(back, TRUCE)).as("its kind stays").isEqualTo("neutral");
  }

  @DisplayName("A nation list given twice is read whole and written back as one, still loading")
  @Test
  void repeatedNationListIsReadAndReplacedWhole(@TempDir final Path folder) {
    final Game twice =
        RulesXml.withPlayerOptionAdded(
            RulesXml.withPlayerOptionAdded(
                ActionsXmlTest.givenGlobal(), "Germans", "shareTechnology", "Italians"),
            "Germans",
            "shareTechnology",
            "Japanese");

    final List<String> read = RulesXml.playerNames(twice, "Germans", "shareTechnology");
    final Game changed =
        RulesXml.withPlayerNames(twice, "Germans", "shareTechnology", List.of("Japanese"));
    final GamePlayer loaded = givenLoaded(changed, folder).getPlayerList().getPlayerId("Germans");

    assertThat(read)
        .as("both options, as TripleA adds them up")
        .containsExactly("Italians", "Japanese");
    assertThat(
            RulesXml.playerOptions(changed, "Germans").stream()
                .filter(o -> "shareTechnology".equals(o.getName())))
        .as("one option left, no hidden second")
        .singleElement()
        .satisfies(o -> assertThat(o.getValue()).as("the chosen nation").isEqualTo("Japanese"));
    assertThat(PlayerAttachment.get(loaded).getShareTechnology())
        .as("the engine shares with the chosen nation only")
        .extracting(GamePlayer::getName)
        .containsExactly("Japanese");
  }
}
